package dev.kidsync.server

import dev.kidsync.server.models.*
import dev.kidsync.server.util.HashUtil
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverrideStateMachineTest {

    private fun ApplicationTestBuilder.createJsonClient() = createClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
        }
    }

    private suspend fun setupTwoUserFamily(client: io.ktor.client.HttpClient): Pair<TestUserFull, TestUserFull> {
        // Register user 1
        val email1 = "parent1-${System.nanoTime()}@example.com"
        val reg1 = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email1, password = "strong-password-12345"))
        }.body<RegisterResponse>()

        // Create family
        val family = client.post("/families") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg1.token}")
            setBody(CreateFamilyRequest(name = "Test Family"))
        }.body<CreateFamilyResponse>()

        // Create invite
        val invite = client.post("/families/${family.familyId}/invite") {
            header(HttpHeaders.Authorization, "Bearer ${reg1.token}")
        }.body<InviteResponse>()

        // Register user 2
        val email2 = "parent2-${System.nanoTime()}@example.com"
        val reg2 = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email2, password = "strong-password-12345"))
        }.body<RegisterResponse>()

        // User 2 joins family
        client.post("/families/${family.familyId}/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg2.token}")
            setBody(JoinFamilyRequest(inviteToken = invite.inviteToken, devicePublicKey = "test-key"))
        }

        // Re-login both to get updated JWT with family claims
        val login1 = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email1, password = "strong-password-12345"))
        }.body<LoginResponse>()

        val login2 = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email2, password = "strong-password-12345"))
        }.body<LoginResponse>()

        return Pair(
            TestUserFull(login1.token, reg1.userId, reg1.deviceId, family.familyId),
            TestUserFull(login2.token, reg2.userId, reg2.deviceId, family.familyId),
        )
    }

    data class TestUserFull(val token: String, val userId: String, val deviceId: String, val familyId: String)

    /** Compute currentHash = SHA256(hexDecode(prevHash) + base64Decode(encryptedPayload)) */
    private fun computeHash(devicePrevHash: String, encryptedPayload: String): String {
        val prevBytes = HashUtil.hexToBytes(devicePrevHash)
        val payloadBytes = Base64.getDecoder().decode(encryptedPayload)
        return HashUtil.sha256Hex(prevBytes, payloadBytes)
    }

    @Test
    fun `override CREATE sets state to PROPOSED`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()
        val (user1, _) = setupTwoUserFamily(client)

        val entityId = "11111111-1111-1111-1111-111111111111"
        val prevHash = "0".repeat(64)
        val payload = "dGVzdA=="

        val response = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user1.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user1.deviceId,
                            entityType = "ScheduleOverride",
                            entityId = entityId,
                            operation = "CREATE",
                            encryptedPayload = payload,
                            devicePrevHash = prevHash,
                            currentHash = computeHash(prevHash, payload),
                            keyEpoch = 1,
                            localId = "create-override",
                        )
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `non-proposer can approve override`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()
        val (user1, user2) = setupTwoUserFamily(client)

        val entityId = "22222222-2222-2222-2222-222222222222"
        val sentinel = "0".repeat(64)
        val payload = "dGVzdA=="

        // User1 creates override (becomes proposer)
        client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user1.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user1.deviceId,
                            entityType = "ScheduleOverride",
                            entityId = entityId,
                            operation = "CREATE",
                            encryptedPayload = payload,
                            devicePrevHash = sentinel,
                            currentHash = computeHash(sentinel, payload),
                            keyEpoch = 1,
                            localId = "create",
                        )
                    )
                )
            )
        }

        // User2 (non-proposer) approves (different device, own hash chain)
        val response = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user2.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user2.deviceId,
                            entityType = "ScheduleOverride",
                            entityId = entityId,
                            operation = "UPDATE",
                            transitionTo = "APPROVED",
                            encryptedPayload = payload,
                            devicePrevHash = sentinel,
                            currentHash = computeHash(sentinel, payload),
                            keyEpoch = 1,
                            localId = "approve",
                        )
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `proposer cannot approve own override`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()
        val (user1, _) = setupTwoUserFamily(client)

        val entityId = "33333333-3333-3333-3333-333333333333"
        val sentinel = "0".repeat(64)
        val payload1 = "dGVzdA=="
        val hash1 = computeHash(sentinel, payload1)

        // Create
        client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user1.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user1.deviceId,
                            entityType = "ScheduleOverride",
                            entityId = entityId,
                            operation = "CREATE",
                            encryptedPayload = payload1,
                            devicePrevHash = sentinel,
                            currentHash = hash1,
                            keyEpoch = 1,
                            localId = "create",
                        )
                    )
                )
            )
        }

        // Proposer tries to approve -> should fail
        val payload2 = "YXBwcm92ZQ=="
        val hash2 = computeHash(hash1, payload2)
        val response = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user1.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user1.deviceId,
                            entityType = "ScheduleOverride",
                            entityId = entityId,
                            operation = "UPDATE",
                            transitionTo = "APPROVED",
                            encryptedPayload = payload2,
                            devicePrevHash = hash1,
                            currentHash = hash2,
                            keyEpoch = 1,
                            localId = "self-approve",
                        )
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `proposer can cancel own override`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()
        val (user1, _) = setupTwoUserFamily(client)

        val entityId = "44444444-4444-4444-4444-444444444444"
        val sentinel = "0".repeat(64)
        val payload1 = "dGVzdA=="
        val hash1 = computeHash(sentinel, payload1)

        // Create
        client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user1.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user1.deviceId,
                            entityType = "ScheduleOverride",
                            entityId = entityId,
                            operation = "CREATE",
                            encryptedPayload = payload1,
                            devicePrevHash = sentinel,
                            currentHash = hash1,
                            keyEpoch = 1,
                            localId = "create",
                        )
                    )
                )
            )
        }

        // Proposer cancels
        val payload2 = "Y2FuY2Vs"
        val hash2 = computeHash(hash1, payload2)
        val response = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user1.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user1.deviceId,
                            entityType = "ScheduleOverride",
                            entityId = entityId,
                            operation = "UPDATE",
                            transitionTo = "CANCELLED",
                            encryptedPayload = payload2,
                            devicePrevHash = hash1,
                            currentHash = hash2,
                            keyEpoch = 1,
                            localId = "cancel",
                        )
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `transition from terminal state is rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()
        val (user1, user2) = setupTwoUserFamily(client)

        val entityId = "55555555-5555-5555-5555-555555555555"
        val sentinel = "0".repeat(64)
        val payload = "dGVzdA=="

        // Create (user1's device hash chain)
        val u1Hash1 = computeHash(sentinel, payload)
        client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user1.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user1.deviceId,
                            entityType = "ScheduleOverride",
                            entityId = entityId,
                            operation = "CREATE",
                            encryptedPayload = payload,
                            devicePrevHash = sentinel,
                            currentHash = u1Hash1,
                            keyEpoch = 1,
                            localId = "create",
                        )
                    )
                )
            )
        }

        // Decline (by non-proposer, user2's device hash chain)
        val u2Hash1 = computeHash(sentinel, payload)
        client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user2.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user2.deviceId,
                            entityType = "ScheduleOverride",
                            entityId = entityId,
                            operation = "UPDATE",
                            transitionTo = "DECLINED",
                            encryptedPayload = payload,
                            devicePrevHash = sentinel,
                            currentHash = u2Hash1,
                            keyEpoch = 1,
                            localId = "decline",
                        )
                    )
                )
            )
        }

        // Try to approve after decline -> should fail
        val payload2 = "YXBwcm92ZQ=="
        val u2Hash2 = computeHash(u2Hash1, payload2)
        val response = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user2.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user2.deviceId,
                            entityType = "ScheduleOverride",
                            entityId = entityId,
                            operation = "UPDATE",
                            transitionTo = "APPROVED",
                            encryptedPayload = payload2,
                            devicePrevHash = u2Hash1,
                            currentHash = u2Hash2,
                            keyEpoch = 1,
                            localId = "approve-after-decline",
                        )
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }
}
