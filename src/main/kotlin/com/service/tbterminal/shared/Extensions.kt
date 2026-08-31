package com.service.tbterminal.shared

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import java.util.UUID

/**
 * Extension untuk mengambil ID User dari JWT Principal.
 * Melempar UnauthorizedException jika token tidak valid atau tidak ada.
 */
fun ApplicationCall.getUserId(): UUID {
    val principal = principal<AuthenticatedUserPrincipal>()
        ?: throw UnauthorizedException("Token tidak valid atau tidak ditemukan")

    return principal.userId
}

fun ApplicationCall.getUserRole(): String {
    return principal<AuthenticatedUserPrincipal>()?.role
        ?: throw UnauthorizedException("Role user tidak tersedia")
}

/** Memvalidasi izin bisnis melalui matriks akses terpusat. */
fun ApplicationCall.requirePermission(permission: Permission) {
    val principal = principal<AuthenticatedUserPrincipal>()
        ?: throw UnauthorizedException("Role user tidak tersedia")
    AccessPolicy.require(principal.role, permission)
}

/**
 * Helper extension untuk merespon error secara eksplisit tanpa melempar Exception.
 * Biasanya digunakan ketika tidak ingin menggunakan StatusPages untuk case spesifik.
 * Secara default, disarankan untuk throw Exception dan biarkan StatusPages menangani.
 */
suspend fun ApplicationCall.respondError(exception: Throwable) {
    val (status, code, message) = when (exception) {
        is UnauthorizedException -> Triple(HttpStatusCode.Unauthorized, "UNAUTHORIZED", exception.message ?: "Tidak ada akses")
        is ForbiddenException -> Triple(HttpStatusCode.Forbidden, "FORBIDDEN", exception.message ?: "Akses ditolak")
        is NotFoundException -> Triple(HttpStatusCode.NotFound, "NOT_FOUND", exception.message ?: "Data tidak ditemukan")
        is ValidationException -> Triple(HttpStatusCode.BadRequest, "VALIDATION_ERROR", exception.message ?: "Validasi gagal")
        else -> Triple(HttpStatusCode.InternalServerError, "INTERNAL_SERVER_ERROR", "Terjadi kesalahan sistem")
    }
    
    respond(status, ErrorResponse(code, message))
}

/**
 * Custom serializer untuk java.math.BigDecimal agar dapat digunakan oleh kotlinx.serialization
 */
object BigDecimalSerializer : kotlinx.serialization.KSerializer<java.math.BigDecimal> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("java.math.BigDecimal", kotlinx.serialization.descriptors.PrimitiveKind.STRING)

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): java.math.BigDecimal {
        return java.math.BigDecimal(decoder.decodeString())
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: java.math.BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }
}
