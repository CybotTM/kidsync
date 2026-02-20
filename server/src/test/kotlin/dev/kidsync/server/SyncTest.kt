package dev.kidsync.server

import dev.kidsync.server.models.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncTest {

    private fun ApplicationTestBuilder.createJsonClient() = createClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
        }
    }

    /**
     * Helper: register a user, create a family, return (token, userId, deviceId, familyId).
     */
    private suspend fun setupUserWithFamily(
        client: io.ktor.client.HttpClient,
        email: String = "sync-${System.nanoTime()}@example.com",
    ): TestUser {
        val regResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email, password = "strong-password-12345"))
        }
        val reg = regResponse.body<RegisterResponse>()

        val familyResponse = client.post("/families") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
            setBody(CreateFamilyRequest(name = "Test Family"))
        }
        val family = familyResponse.body<CreateFamilyResponse>()

        // Need to re-login to get updated familyIds in JWT
        val loginResponse = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email, password = "strong-password-12345"))
        }
        val login = loginResponse.body<LoginResponse>()

        return TestUser(login.token, reg.userId, reg.deviceId, family.familyId)
    }

    data class TestUser(val token: String, val userId: String, val deviceId: String, val familyId: String)

    @Test
    fun `upload and pull ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = setupUserWithFamily(client)

        // Upload ops
        val uploadResponse = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            localId = "op-1",
                            deviceId = user.deviceId,
                            encryptedPayload = "dGVzdCBwYXlsb2Fk",
                            devicePrevHash = "0".repeat(64),
                            keyEpoch = 1,
                        )
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, uploadResponse.status)
        val uploadBody = uploadResponse.body<UploadOpsResponse>()
        assertEquals(1, uploadBody.assignedSequences.size)
        assertEquals("op-1", uploadBody.assignedSequences[0].localId)
        assertTrue(uploadBody.assignedSequences[0].globalSequence > 0)

        // Pull ops
        val pullResponse = client.get("/sync/ops?since=0&limit=100") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
        }

        assertEquals(HttpStatusCode.OK, pullResponse.status)
        val pullBody = pullResponse.body<PullOpsResponse>()
        assertEquals(1, pullBody.ops.size)
        assertEquals("dGVzdCBwYXlsb2Fk", pullBody.ops[0].encryptedPayload)
        assertFalse(pullBody.hasMore)
    }

    @Test
    fun `upload multiple ops assigns contiguous sequences`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = setupUserWithFamily(client)

        val uploadResponse = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            localId = "op-1",
                            deviceId = user.deviceId,
                            encryptedPayload = "cGF5bG9hZDE=",
                            devicePrevHash = "0".repeat(64),
                            keyEpoch = 1,
                        ),
                        OpInput(
                            localId = "op-2",
                            deviceId = user.deviceId,
                            encryptedPayload = "cGF5bG9hZDI=",
                            devicePrevHash = "0".repeat(64), // simplified for test
                            keyEpoch = 1,
                        ),
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, uploadResponse.status)
        val body = uploadResponse.body<UploadOpsResponse>()
        assertEquals(2, body.assignedSequences.size)
        assertEquals(body.assignedSequences[0].globalSequence + 1, body.assignedSequences[1].globalSequence)
    }

    @Test
    fun `pull with pagination`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = setupUserWithFamily(client)

        // Upload 5 ops
        for (i in 1..5) {
            client.post("/sync/ops") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${user.token}")
                setBody(
                    UploadOpsRequest(
                        ops = listOf(
                            OpInput(
                                localId = "op-$i",
                                deviceId = user.deviceId,
                                encryptedPayload = "cGF5bG9hZA==",
                                devicePrevHash = "0".repeat(64),
                                keyEpoch = 1,
                            )
                        )
                    )
                )
            }
        }

        // Pull with limit 2
        val page1 = client.get("/sync/ops?since=0&limit=2") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
        }.body<PullOpsResponse>()

        assertEquals(2, page1.ops.size)
        assertTrue(page1.hasMore)

        // Pull next page
        val lastSeq = page1.ops.last().globalSequence
        val page2 = client.get("/sync/ops?since=$lastSeq&limit=2") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
        }.body<PullOpsResponse>()

        assertEquals(2, page2.ops.size)
        assertTrue(page2.hasMore)

        // Pull final page
        val lastSeq2 = page2.ops.last().globalSequence
        val page3 = client.get("/sync/ops?since=$lastSeq2&limit=2") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
        }.body<PullOpsResponse>()

        assertEquals(1, page3.ops.size)
        assertFalse(page3.hasMore)
    }

    @Test
    fun `upload rejects empty ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = setupUserWithFamily(client)

        val response = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(UploadOpsRequest(ops = emptyList()))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `handshake returns server info`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = setupUserWithFamily(client)

        val response = client.post("/sync/handshake") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(HandshakeRequest(protocolVersion = 1, appVersion = "0.1.0", deviceId = user.deviceId))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<HandshakeResponse>()
        assertTrue(body.ok)
        assertEquals(1, body.protocolVersion)
        assertNotNull(body.serverTime)
    }

    @Test
    fun `handshake rejects unsupported protocol version`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = setupUserWithFamily(client)

        val response = client.post("/sync/handshake") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(HandshakeRequest(protocolVersion = 99, appVersion = "0.1.0", deviceId = user.deviceId))
        }

        assertEquals(HttpStatusCode.UpgradeRequired, response.status)
    }

    @Test
    fun `checkpoint returns null when no ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = setupUserWithFamily(client)

        val response = client.get("/sync/checkpoint") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<CheckpointResponse>()
        assertEquals(0L, body.latestSequence)
    }
}
