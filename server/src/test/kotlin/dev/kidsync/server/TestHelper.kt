package dev.kidsync.server

import dev.kidsync.server.models.*
import dev.kidsync.server.util.HashUtil
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.assertEquals

data class TestUser(val token: String, val userId: String, val deviceId: String, val familyId: String)

fun testConfig(): AppConfig {
    val dbPath = "data/test-${System.nanoTime()}.db"
    return AppConfig(
        dbPath = dbPath,
        jwtSecret = "test-secret-that-is-at-least-32-characters-long!!",
        blobStoragePath = "data/test-blobs-${System.nanoTime()}",
        snapshotStoragePath = "data/test-snapshots-${System.nanoTime()}",
    )
}

object TestHelper {

    fun ApplicationTestBuilder.createJsonClient() = createClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
        }
    }

    /** Compute currentHash = SHA256(hexDecode(prevHash) + base64Decode(encryptedPayload)) */
    fun computeHash(devicePrevHash: String, encryptedPayload: String): String {
        val prevBytes = HashUtil.hexToBytes(devicePrevHash)
        val payloadBytes = Base64.getDecoder().decode(encryptedPayload)
        return HashUtil.sha256Hex(prevBytes, payloadBytes)
    }

    /**
     * Register a user, create a family, re-login to get familyIds in JWT.
     * Returns a [TestUser] with updated token, userId, deviceId, and familyId.
     */
    suspend fun setupUserWithFamily(
        client: io.ktor.client.HttpClient,
        email: String = "test-${System.nanoTime()}@example.com",
        familyName: String = "Test Family",
    ): TestUser {
        val regResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email, password = "strong-password-12345"))
        }
        assertEquals(HttpStatusCode.Created, regResponse.status,
            "Register failed: ${regResponse.status}")
        val reg = regResponse.body<RegisterResponse>()

        val familyResponse = client.post("/families") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
            setBody(CreateFamilyRequest(name = familyName))
        }
        assertEquals(HttpStatusCode.Created, familyResponse.status,
            "Family creation failed: ${familyResponse.status}")
        val family = familyResponse.body<CreateFamilyResponse>()

        // Re-login to get updated familyIds in JWT
        val loginResponse = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email, password = "strong-password-12345"))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status,
            "Login failed: ${loginResponse.status}")
        val login = loginResponse.body<LoginResponse>()

        return TestUser(login.token, reg.userId, reg.deviceId, family.familyId)
    }

    /**
     * Setup two users in the same family. Returns (parentA, parentB).
     */
    suspend fun setupTwoUserFamily(
        client: io.ktor.client.HttpClient,
    ): Pair<TestUser, TestUser> {
        val emailA = "parentA-${System.nanoTime()}@example.com"
        val emailB = "parentB-${System.nanoTime()}@example.com"

        // Register parent A and create family
        val regA = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = emailA, password = "strong-password-12345"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<RegisterResponse>()

        val family = client.post("/families") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${regA.token}")
            setBody(CreateFamilyRequest(name = "Two-Parent Family"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<CreateFamilyResponse>()

        // Create invite
        val invite = client.post("/families/${family.familyId}/invite") {
            header(HttpHeaders.Authorization, "Bearer ${regA.token}")
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<InviteResponse>()

        // Register parent B
        val regB = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = emailB, password = "strong-password-12345"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<RegisterResponse>()

        // Parent B joins family
        client.post("/families/${family.familyId}/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${regB.token}")
            setBody(JoinFamilyRequest(inviteToken = invite.inviteToken, devicePublicKey = "parentB-key"))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        // Re-login both to get updated JWT with familyIds
        val loginA = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = emailA, password = "strong-password-12345"))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<LoginResponse>()

        val loginB = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = emailB, password = "strong-password-12345"))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<LoginResponse>()

        return Pair(
            TestUser(loginA.token, regA.userId, regA.deviceId, family.familyId),
            TestUser(loginB.token, regB.userId, regB.deviceId, family.familyId),
        )
    }

    /**
     * Upload a chain of ops one at a time with a valid hash chain.
     * Returns the last currentHash.
     */
    suspend fun uploadOpsChain(
        client: io.ktor.client.HttpClient,
        user: TestUser,
        count: Int,
        startPrevHash: String = "0".repeat(64),
        localIdPrefix: String = "op",
    ): String {
        var prevHash = startPrevHash
        for (i in 1..count) {
            val payload = Base64.getEncoder().encodeToString("payload-$localIdPrefix-$i".toByteArray())
            val curHash = computeHash(prevHash, payload)
            val response = client.post("/sync/ops") {
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
                                localId = "$localIdPrefix-$i",
                            )
                        )
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, response.status, "Upload op $localIdPrefix-$i failed: ${response.bodyAsText()}")
            prevHash = curHash
        }
        return prevHash
    }

    /**
     * Upload a batch of ops in a single request with valid hash chain.
     * Returns the last currentHash.
     */
    suspend fun uploadOpsBatch(
        client: io.ktor.client.HttpClient,
        user: TestUser,
        count: Int,
        startPrevHash: String = "0".repeat(64),
        localIdPrefix: String = "op",
    ): String {
        val ops = mutableListOf<OpInput>()
        var prevHash = startPrevHash
        for (i in 1..count) {
            val payload = Base64.getEncoder().encodeToString("payload-$localIdPrefix-$i".toByteArray())
            val curHash = computeHash(prevHash, payload)
            ops.add(
                OpInput(
                    deviceId = user.deviceId,
                    encryptedPayload = payload,
                    devicePrevHash = prevHash,
                    currentHash = curHash,
                    keyEpoch = 1,
                    localId = "$localIdPrefix-$i",
                )
            )
            prevHash = curHash
        }
        val response = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(UploadOpsRequest(ops = ops))
        }
        assertEquals(HttpStatusCode.OK, response.status, "Batch upload failed: ${response.bodyAsText()}")
        val body = response.body<UploadOpsResponse>()
        assertEquals(count, body.accepted.size, "Expected $count accepted ops")
        return prevHash
    }
}
