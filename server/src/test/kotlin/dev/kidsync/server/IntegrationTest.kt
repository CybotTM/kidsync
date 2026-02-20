package dev.kidsync.server

import dev.kidsync.server.models.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntegrationTest {

    private fun ApplicationTestBuilder.createJsonClient() = createClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
        }
    }

    @Test
    fun `full registration and family creation flow`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // Register
        val regResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "integration@example.com", password = "strong-password-12345"))
        }
        assertEquals(HttpStatusCode.Created, regResponse.status)
        val reg = regResponse.body<RegisterResponse>()

        // Create family
        val familyResponse = client.post("/families") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
            setBody(CreateFamilyRequest(name = "Integration Family"))
        }
        assertEquals(HttpStatusCode.Created, familyResponse.status)
        val family = familyResponse.body<CreateFamilyResponse>()
        assertNotNull(family.familyId)

        // Register device
        val deviceResponse = client.post("/devices") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
            setBody(RegisterDeviceRequest(deviceName = "Test Phone", publicKey = "test-key-123"))
        }
        assertEquals(HttpStatusCode.Created, deviceResponse.status)
        val device = deviceResponse.body<RegisterDeviceResponse>()
        assertNotNull(device.deviceId)

        // List devices
        val devicesResponse = client.get("/devices") {
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
        }
        assertEquals(HttpStatusCode.OK, devicesResponse.status)
        val devices = devicesResponse.body<DeviceListResponse>()
        assertTrue(devices.devices.size >= 2) // Initial device + registered device
    }

    @Test
    fun `invite and join family flow`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // Register user 1
        val reg1 = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "inviter-${System.nanoTime()}@example.com", password = "strong-password-12345"))
        }.body<RegisterResponse>()

        // Create family
        val family = client.post("/families") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg1.token}")
            setBody(CreateFamilyRequest(name = "Join Test Family"))
        }.body<CreateFamilyResponse>()

        // Create invite
        val inviteResponse = client.post("/families/${family.familyId}/invite") {
            header(HttpHeaders.Authorization, "Bearer ${reg1.token}")
        }
        assertEquals(HttpStatusCode.Created, inviteResponse.status)
        val invite = inviteResponse.body<InviteResponse>()
        assertNotNull(invite.inviteToken)

        // Register user 2
        val reg2 = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "joiner-${System.nanoTime()}@example.com", password = "strong-password-12345"))
        }.body<RegisterResponse>()

        // User 2 joins
        val joinResponse = client.post("/families/${family.familyId}/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg2.token}")
            setBody(JoinFamilyRequest(inviteToken = invite.inviteToken, devicePublicKey = "joiner-key"))
        }
        assertEquals(HttpStatusCode.OK, joinResponse.status)
        val joinBody = joinResponse.body<JoinFamilyResponse>()
        assertEquals(2, joinBody.members.size)
    }

    @Test
    fun `device revocation flow`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val reg = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "revoke-${System.nanoTime()}@example.com", password = "strong-password-12345"))
        }.body<RegisterResponse>()

        // Register a new device
        val device = client.post("/devices") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
            setBody(RegisterDeviceRequest(deviceName = "To Revoke", publicKey = "key"))
        }.body<RegisterDeviceResponse>()

        // Revoke it
        val revokeResponse = client.delete("/devices/${device.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
        }
        assertEquals(HttpStatusCode.NoContent, revokeResponse.status)

        // Verify device list shows revocation
        val devices = client.get("/devices") {
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
        }.body<DeviceListResponse>()

        val revokedDevice = devices.devices.find { it.deviceId == device.deviceId }
        assertNotNull(revokedDevice)
        assertNotNull(revokedDevice.revokedAt)
    }

    @Test
    fun `push token registration`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val reg = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "push-${System.nanoTime()}@example.com", password = "strong-password-12345"))
        }.body<RegisterResponse>()

        val response = client.post("/push/register") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
            setBody(RegisterPushRequest(token = "fcm-token-123", platform = "FCM"))
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `wrapped key upload and retrieval`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // Two users in same family
        val email1 = "keyu1-${System.nanoTime()}@example.com"
        val email2 = "keyu2-${System.nanoTime()}@example.com"

        val reg1 = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email1, password = "strong-password-12345"))
        }.body<RegisterResponse>()

        val family = client.post("/families") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg1.token}")
            setBody(CreateFamilyRequest(name = "Key Family"))
        }.body<CreateFamilyResponse>()

        val invite = client.post("/families/${family.familyId}/invite") {
            header(HttpHeaders.Authorization, "Bearer ${reg1.token}")
        }.body<InviteResponse>()

        val reg2 = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email2, password = "strong-password-12345"))
        }.body<RegisterResponse>()

        client.post("/families/${family.familyId}/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg2.token}")
            setBody(JoinFamilyRequest(inviteToken = invite.inviteToken, devicePublicKey = "key"))
        }

        // Re-login user1 to get family in JWT
        val login1 = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email1, password = "strong-password-12345"))
        }.body<LoginResponse>()

        val login2 = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email2, password = "strong-password-12345"))
        }.body<LoginResponse>()

        // User1 uploads wrapped key for user2's device
        val uploadKeyResponse = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${login1.token}")
            setBody(UploadWrappedKeyRequest(targetDeviceId = reg2.deviceId, wrappedDek = "wrapped-dek-data", keyEpoch = 1))
        }
        assertEquals(HttpStatusCode.Created, uploadKeyResponse.status)

        // User2 retrieves their wrapped key
        val getKeyResponse = client.get("/keys/wrapped/${reg2.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${login2.token}")
        }
        assertEquals(HttpStatusCode.OK, getKeyResponse.status)
        val key = getKeyResponse.body<WrappedKeyResponse>()
        assertEquals("wrapped-dek-data", key.wrappedDek)
        assertEquals(1, key.keyEpoch)
    }

    @Test
    fun `recovery blob upload and retrieval`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val reg = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "recovery-${System.nanoTime()}@example.com", password = "strong-password-12345"))
        }.body<RegisterResponse>()

        // Upload recovery blob
        val uploadResponse = client.post("/keys/recovery") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
            setBody(UploadRecoveryBlobRequest(encryptedRecoveryBlob = "recovery-blob-data"))
        }
        assertEquals(HttpStatusCode.Created, uploadResponse.status)

        // Retrieve recovery blob
        val getResponse = client.get("/keys/recovery") {
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
        }
        assertEquals(HttpStatusCode.OK, getResponse.status)
        val blob = getResponse.body<RecoveryBlobResponse>()
        assertEquals("recovery-blob-data", blob.encryptedRecoveryBlob)
    }

    @Test
    fun `unauthenticated requests are rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/sync/ops?since=0") {}
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `snapshot not found returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val email = "snap-${System.nanoTime()}@example.com"
        val reg = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email, password = "strong-password-12345"))
        }.body<RegisterResponse>()

        val family = client.post("/families") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
            setBody(CreateFamilyRequest(name = "Snap Family"))
        }.body<CreateFamilyResponse>()

        val login = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email, password = "strong-password-12345"))
        }.body<LoginResponse>()

        val response = client.get("/sync/snapshot/latest") {
            header(HttpHeaders.Authorization, "Bearer ${login.token}")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
