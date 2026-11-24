package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ServiceUnavailableException
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudinaryMediaStorageTest {

    @Test
    fun `signParams sorts keys alphabetically and computes sha1 hex with secret`() {
        val signature = CloudinaryMediaStorage.signParams(
            params = linkedMapOf(
                "timestamp" to "1700000000",
                "folder" to "tbterminal/products"
            ),
            apiSecret = "super-secret-value"
        )
        val expectedInput = "folder=tbterminal/products&timestamp=1700000000super-secret-value"
        val expected = MessageDigest.getInstance("SHA-1")
            .digest(expectedInput.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, signature)
    }

    @Test
    fun `detectImageMediaType recognizes JPEG PNG WebP and rejects invalid bytes`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A, 0x00
        )
        val webp = "RIFF1234WEBPVP8 ".toByteArray(StandardCharsets.US_ASCII)
        val gif = "GIF89a......".toByteArray(StandardCharsets.US_ASCII)
        val plain = "hello world".toByteArray(StandardCharsets.US_ASCII)

        assertEquals(ImageMediaType.JPEG, detectImageMediaType(jpeg))
        assertEquals(ImageMediaType.PNG, detectImageMediaType(png))
        assertEquals(ImageMediaType.WEBP, detectImageMediaType(webp))
        assertNull(detectImageMediaType(gif))
        assertNull(detectImageMediaType(plain))
        assertNull(detectImageMediaType(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }

    @Test
    fun `uploadProductPhoto sends signed multipart without overwrite and parses response`() = runBlocking {
        var capturedPath = ""
        var capturedBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1_1/demo-cloud/image/upload") { exchange ->
            capturedPath = exchange.requestURI.path
            capturedBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val responseJson = """{"secure_url":"https://res.cloudinary.com/demo-cloud/image/upload/v1/tbterminal/products/rnd123.jpg","public_id":"tbterminal/products/rnd123"}"""
            val bytes = responseJson.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val storage = CloudinaryMediaStorage(
                cloudName = "demo-cloud",
                apiKey = "key-123",
                apiSecret = "secret-xyz",
                folder = "tbterminal/products",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                clockSeconds = { 1700000123L }
            )
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

            val uploaded = storage.uploadProductPhoto(jpeg, ImageMediaType.JPEG)

            assertEquals("/v1_1/demo-cloud/image/upload", capturedPath)
            assertEquals("https://res.cloudinary.com/demo-cloud/image/upload/v1/tbterminal/products/rnd123.jpg", uploaded.secureUrl)
            assertEquals("tbterminal/products/rnd123", uploaded.publicId)
            assertTrue(capturedBody.contains("name=\"api_key\"\r\n\r\nkey-123"))
            assertTrue(capturedBody.contains("name=\"folder\"\r\n\r\ntbterminal/products"))
            assertTrue(capturedBody.contains("name=\"timestamp\"\r\n\r\n1700000123"))
            assertFalse(capturedBody.contains("name=\"overwrite\""))
            assertFalse(capturedBody.contains("secret-xyz"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `uploadProductPhoto throws ServiceUnavailableException on error and does not leak secret`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1_1/demo-cloud/image/upload") { exchange ->
            val err = """{"error":{"message":"Invalid signature secret-xyz"}}"""
            val bytes = err.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val storage = CloudinaryMediaStorage(
                cloudName = "demo-cloud",
                apiKey = "key-123",
                apiSecret = "secret-xyz",
                folder = "tbterminal/products",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                clockSeconds = { 1700000123L }
            )
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val ex = assertFailsWith<ServiceUnavailableException> {
                storage.uploadProductPhoto(png, ImageMediaType.PNG)
            }
            assertFalse(ex.message.orEmpty().contains("secret-xyz"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `destroyProductPhoto posts signed destroy request and is best-effort on failure`() = runBlocking {
        var capturedBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1_1/demo-cloud/image/destroy") { exchange ->
            capturedBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(500, -1)
        }
        server.start()

        try {
            val storage = CloudinaryMediaStorage(
                cloudName = "demo-cloud",
                apiKey = "key-123",
                apiSecret = "secret-xyz",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                clockSeconds = { 1700000999L }
            )
            storage.destroyProductPhoto("tbterminal/products/old999")
            assertTrue(capturedBody.contains("name=\"public_id\"\r\n\r\ntbterminal/products/old999"))
            assertTrue(capturedBody.contains("name=\"timestamp\"\r\n\r\n1700000999"))
        } finally {
            server.stop(0)
        }
    }
}
