package dev.kidsync.server

import dev.kidsync.server.TestHelper.computeHash
import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.models.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncTest {

    @Test
    fun `upload and pull ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = TestHelper.setupUserWithFamily(client)

        // Upload ops
        val prevHash1 = "0".repeat(64)
        val payload1 = "dGVzdCBwYXlsb2Fk"
        val uploadResponse = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user.deviceId,
                            encryptedPayload = payload1,
                            devicePrevHash = prevHash1,
                            currentHash = computeHash(prevHash1, payload1),
                            keyEpoch = 1,
                            localId = "op-1",
                        )
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, uploadResponse.status)
        val uploadBody = uploadResponse.body<UploadOpsResponse>()
        assertEquals(1, uploadBody.accepted.size)
        assertEquals("op-1", uploadBody.accepted[0].localId)
        assertTrue(uploadBody.accepted[0].globalSequence > 0)

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

        val user = TestHelper.setupUserWithFamily(client)

        val prevHash1 = "0".repeat(64)
        val payload1 = "cGF5bG9hZDE="
        val hash1 = computeHash(prevHash1, payload1)
        val payload2 = "cGF5bG9hZDI="
        val hash2 = computeHash(hash1, payload2)

        val uploadResponse = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user.deviceId,
                            encryptedPayload = payload1,
                            devicePrevHash = prevHash1,
                            currentHash = hash1,
                            keyEpoch = 1,
                            localId = "op-1",
                        ),
                        OpInput(
                            deviceId = user.deviceId,
                            encryptedPayload = payload2,
                            devicePrevHash = hash1,
                            currentHash = hash2,
                            keyEpoch = 1,
                            localId = "op-2",
                        ),
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, uploadResponse.status)
        val body = uploadResponse.body<UploadOpsResponse>()
        assertEquals(2, body.accepted.size)
        assertEquals(body.accepted[0].globalSequence + 1, body.accepted[1].globalSequence)
    }

    @Test
    fun `pull with pagination`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = TestHelper.setupUserWithFamily(client)

        // Upload 5 ops with valid hash chain
        var prevHash = "0".repeat(64)
        for (i in 1..5) {
            val payload = Base64.getEncoder().encodeToString("payload-$i".toByteArray())
            val curHash = computeHash(prevHash, payload)
            client.post("/sync/ops") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${user.token}")
                setBody(
                    UploadOpsRequest(
                        ops = listOf(
                            OpInput(
                                deviceId = user.deviceId,
                                encryptedPayload = payload,
                                devicePrevHash = prevHash,
                                currentHash = curHash,
                                keyEpoch = 1,
                                localId = "op-$i",
                            )
                        )
                    )
                )
            }
            prevHash = curHash
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

        val user = TestHelper.setupUserWithFamily(client)

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

        val user = TestHelper.setupUserWithFamily(client)

        val response = client.post("/sync/handshake") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(HandshakeRequest(familyId = user.familyId, deviceId = user.deviceId, protocolVersion = 1))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<HandshakeResponse>()
        assertEquals(1, body.serverVersion)
        assertEquals(0L, body.currentGlobalSequence)
        assertEquals(0L, body.pendingOpsCount)
    }

    @Test
    fun `handshake returns pending ops count`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = TestHelper.setupUserWithFamily(client)

        // Upload an op first
        val prevHash = "0".repeat(64)
        val payload = "dGVzdA=="
        client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user.deviceId,
                            encryptedPayload = payload,
                            devicePrevHash = prevHash,
                            currentHash = computeHash(prevHash, payload),
                            keyEpoch = 1,
                            localId = "op-1",
                        )
                    )
                )
            )
        }

        val response = client.post("/sync/handshake") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(HandshakeRequest(familyId = user.familyId, deviceId = user.deviceId, protocolVersion = 1, lastGlobalSequence = 0))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<HandshakeResponse>()
        assertEquals(1, body.serverVersion)
        assertTrue(body.currentGlobalSequence > 0)
        assertEquals(body.currentGlobalSequence, body.pendingOpsCount)
    }

    @Test
    fun `checkpoint returns null when no ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = TestHelper.setupUserWithFamily(client)

        val response = client.get("/sync/checkpoint") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<CheckpointResponse>()
        assertEquals(0L, body.latestSequence)
    }
}
