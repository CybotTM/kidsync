package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.models.*
import dev.kidsync.server.services.PushService
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for SEC6-S-13: Push token encryption at rest.
 */
class PushServiceTest {

    @Test
    fun `encryption roundtrip with valid key`() {
        val keyBytes = ByteArray(32) { it.toByte() }
        val keyBase64 = Base64.getEncoder().encodeToString(keyBytes)
        val service = PushService(keyBase64)

        val original = "test-push-token-abc123"
        val encrypted = service.encryptToken(original)
        val decrypted = service.decryptToken(encrypted)

        assertEquals(original, decrypted)
    }

    @Test
    fun `encrypted token differs from original`() {
        val keyBytes = ByteArray(32) { it.toByte() }
        val keyBase64 = Base64.getEncoder().encodeToString(keyBytes)
        val service = PushService(keyBase64)

        val original = "test-push-token-abc123"
        val encrypted = service.encryptToken(original)

        assertNotEquals(original, encrypted, "Encrypted token should differ from plaintext")
        assertTrue(encrypted.contains(':'), "Encrypted format should be IV:ciphertext")
    }

    @Test
    fun `each encryption produces different ciphertext due to random IV`() {
        val keyBytes = ByteArray(32) { it.toByte() }
        val keyBase64 = Base64.getEncoder().encodeToString(keyBytes)
        val service = PushService(keyBase64)

        val original = "same-token"
        val encrypted1 = service.encryptToken(original)
        val encrypted2 = service.encryptToken(original)

        assertNotEquals(encrypted1, encrypted2, "Different IVs should produce different ciphertexts")

        // But both should decrypt to the same value
        assertEquals(original, service.decryptToken(encrypted1))
        assertEquals(original, service.decryptToken(encrypted2))
    }

    @Test
    fun `no encryption when key not configured`() {
        val service = PushService(null)

        val original = "plaintext-token"
        val stored = service.encryptToken(original)

        assertEquals(original, stored, "Without key, token should be stored as-is")
    }

    @Test
    fun `plaintext token passes through decryption when key not configured`() {
        val service = PushService(null)

        val plaintext = "plaintext-token-without-colon"
        val result = service.decryptToken(plaintext)

        assertEquals(plaintext, result)
    }

    @Test
    fun `invalid key format falls back to plaintext`() {
        val service = PushService("not-valid-base64!!!")

        val original = "test-token"
        val stored = service.encryptToken(original)

        // Should store as plaintext when key is invalid
        assertEquals(original, stored)
    }

    @Test
    fun `wrong-sized key falls back to plaintext`() {
        // 16 bytes instead of 32
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))
        val service = PushService(shortKey)

        val original = "test-token"
        val stored = service.encryptToken(original)

        assertEquals(original, stored, "Wrong-sized key should fall back to plaintext")
    }

    @Test
    fun `register push token endpoint works with encryption`() = testApplication {
        val keyBytes = ByteArray(32) { it.toByte() }
        val keyBase64 = Base64.getEncoder().encodeToString(keyBytes)
        val config = testConfig().copy(pushTokenEncryptionKey = keyBase64)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.post("/push/token") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(PushTokenRequest(token = "fcm-token-12345", platform = "FCM"))
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }
}
