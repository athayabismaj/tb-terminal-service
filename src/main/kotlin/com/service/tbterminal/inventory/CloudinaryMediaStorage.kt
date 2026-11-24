package com.service.tbterminal.inventory

import com.service.tbterminal.shared.EnvironmentConfig
import com.service.tbterminal.shared.ServiceUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

class DisabledProductMediaStorage(
    private val reason: String = "Fitur foto produk sedang nonaktif"
) : ProductMediaStorage {
    override val isEnabled: Boolean = false

    override fun ensureEnabled() {
        throw ServiceUnavailableException(reason)
    }

    override suspend fun uploadProductPhoto(bytes: ByteArray, mediaType: ImageMediaType): UploadedProductMedia {
        throw ServiceUnavailableException(reason)
    }

    override suspend fun destroyProductPhoto(publicId: String) = Unit
}

class CloudinaryMediaStorage(
    private val cloudName: String,
    private val apiKey: String,
    private val apiSecret: String,
    private val folder: String = "tbterminal/products",
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val clockSeconds: () -> Long = { Instant.now().epochSecond }
) : ProductMediaStorage {

    private val log = LoggerFactory.getLogger(CloudinaryMediaStorage::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override val isEnabled: Boolean = true

    override fun ensureEnabled() = Unit

    override suspend fun uploadProductPhoto(bytes: ByteArray, mediaType: ImageMediaType): UploadedProductMedia {
        val timestamp = clockSeconds().toString()
        val signedParams = linkedMapOf(
            "folder" to folder,
            "timestamp" to timestamp
        )
        val signature = signParams(signedParams, apiSecret)

        val boundary = "----tbterminal-${UUID.randomUUID()}"
        val bodyBytes = buildMultipartBody(
            boundary = boundary,
            fields = mapOf(
                "api_key" to apiKey,
                "folder" to folder,
                "timestamp" to timestamp,
                "signature" to signature
            ),
            fileFieldName = "file",
            fileName = "product.${mediaType.extension}",
            fileMimeType = mediaType.mimeType,
            fileBytes = bytes
        )

        val endpoint = "${baseUrl.trimEnd('/')}/v1_1/$cloudName/image/upload"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
            .build()

        val response = sendRequest(request, actionName = "upload")
        if (response.statusCode() !in 200..299) {
            log.warn("Cloudinary upload gagal dengan HTTP status={}", response.statusCode())
            throw ServiceUnavailableException("Gagal mengunggah foto produk ke layanan penyimpanan")
        }

        val parsed = runCatching {
            json.decodeFromString<CloudinaryUploadResponse>(response.body())
        }.getOrElse { cause ->
            log.warn("Gagal membaca response upload Cloudinary: {}", cause.message)
            throw ServiceUnavailableException("Response layanan penyimpanan foto tidak valid")
        }

        val secureUrl = parsed.secureUrl?.takeIf(String::isNotBlank)
        val publicId = parsed.publicId?.takeIf(String::isNotBlank)
        if (secureUrl == null || publicId == null) {
            log.warn("Response Cloudinary upload tidak menyertakan secure_url/public_id")
            throw ServiceUnavailableException("Response layanan penyimpanan foto tidak lengkap")
        }
        return UploadedProductMedia(secureUrl = secureUrl, publicId = publicId)
    }

    override suspend fun destroyProductPhoto(publicId: String) {
        if (publicId.isBlank()) return
        val timestamp = clockSeconds().toString()
        val signedParams = linkedMapOf(
            "public_id" to publicId,
            "timestamp" to timestamp
        )
        val signature = signParams(signedParams, apiSecret)

        val boundary = "----tbterminal-${UUID.randomUUID()}"
        val bodyBytes = buildMultipartBody(
            boundary = boundary,
            fields = mapOf(
                "public_id" to publicId,
                "api_key" to apiKey,
                "timestamp" to timestamp,
                "signature" to signature
            )
        )

        val endpoint = "${baseUrl.trimEnd('/')}/v1_1/$cloudName/image/destroy"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
            .build()

        try {
            val response = sendRequest(request, actionName = "destroy")
            if (response.statusCode() !in 200..299) {
                log.warn(
                    "Cloudinary destroy best-effort gagal untuk publicId={} status={}",
                    publicId,
                    response.statusCode()
                )
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            log.warn("Cloudinary destroy best-effort gagal untuk publicId={}: {}", publicId, cause.message)
        }
    }

    private suspend fun sendRequest(
        request: HttpRequest,
        actionName: String
    ): HttpResponse<String> = withContext(Dispatchers.IO) {
        try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            log.warn("Koneksi ke Cloudinary ({}) gagal: {}", actionName, cause.message)
            throw ServiceUnavailableException("Layanan penyimpanan foto sedang tidak terjangkau")
        }
    }

    private fun buildMultipartBody(
        boundary: String,
        fields: Map<String, String>,
        fileFieldName: String? = null,
        fileName: String? = null,
        fileMimeType: String? = null,
        fileBytes: ByteArray? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeAscii(value: String) {
            out.write(value.toByteArray(StandardCharsets.UTF_8))
        }

        fields.forEach { (name, value) ->
            writeAscii("--$boundary\r\n")
            writeAscii("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            writeAscii(value)
            writeAscii("\r\n")
        }

        if (fileFieldName != null && fileName != null && fileMimeType != null && fileBytes != null) {
            writeAscii("--$boundary\r\n")
            writeAscii("Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"\r\n")
            writeAscii("Content-Type: $fileMimeType\r\n\r\n")
            out.write(fileBytes)
            writeAscii("\r\n")
        }

        writeAscii("--$boundary--\r\n")
        return out.toByteArray()
    }

    @Serializable
    private data class CloudinaryUploadResponse(
        @SerialName("secure_url") val secureUrl: String? = null,
        @SerialName("public_id") val publicId: String? = null
    )

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.cloudinary.com"

        fun signParams(params: Map<String, String>, apiSecret: String): String {
            val payload = params.entries
                .filter { it.value.isNotBlank() }
                .sortedBy { it.key }
                .joinToString("&") { (k, v) -> "$k=$v" } + apiSecret
            val digest = MessageDigest.getInstance("SHA-1")
                .digest(payload.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun fromEnvironment(): ProductMediaStorage {
            if (!EnvironmentConfig.productPhotoEnabled) {
                return DisabledProductMediaStorage()
            }
            val cloudName = EnvironmentConfig.cloudinaryCloudName?.takeIf(String::isNotBlank)
            val apiKey = EnvironmentConfig.cloudinaryApiKey?.takeIf(String::isNotBlank)
            val apiSecret = EnvironmentConfig.cloudinaryApiSecret?.takeIf(String::isNotBlank)
            if (cloudName == null || apiKey == null || apiSecret == null) {
                return DisabledProductMediaStorage("Konfigurasi Cloudinary belum lengkap")
            }
            return CloudinaryMediaStorage(
                cloudName = cloudName,
                apiKey = apiKey,
                apiSecret = apiSecret,
                folder = EnvironmentConfig.cloudinaryProductFolder
            )
        }
    }
}
