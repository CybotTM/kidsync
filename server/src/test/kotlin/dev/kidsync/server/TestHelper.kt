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
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Represents a registered and authenticated test device with all cryptographic material.
 */
data class TestDevice(
    val deviceId: String,
    val sessionToken: String,
    val signingKeyPair: KeyPair,
    val encryptionKeyPair: KeyPair,
    val signingKeyBase64: String,
    val encryptionKeyBase64: String,
    val bucketId: String? = null,
)

fun testConfig(): AppConfig {
    return AppConfig(
        dbPath = ":memory:",
        blobStoragePath = "data/test-blobs-${System.nanoTime()}",
        snapshotStoragePath = "data/test-snapshots-${System.nanoTime()}",
        serverOrigin = "https://test.kidsync.app",
        sessionTtlSeconds = 3600L,
        challengeTtlSeconds = 60L,
        snapshotRateLimitPerHour = 100, // Relaxed for testing
    )
}

object TestHelper {

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun ApplicationTestBuilder.createJsonClient() = createClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
        }
    }

    // ---- Ed25519 Key Generation ----

    /**
     * Generate an Ed25519 signing keypair.
     * JDK 15+ provides Ed25519 via the "EdDSA" or "Ed25519" KeyPairGenerator.
     */
    fun generateSigningKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        return kpg.generateKeyPair()
    }

    /**
     * Generate an X25519 encryption keypair.
     * JDK 11+ provides X25519 via the "X25519" or "XDH" KeyPairGenerator.
     */
    fun generateEncryptionKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("X25519")
        return kpg.generateKeyPair()
    }

    /**
     * Encode a public key to base64 (raw encoded form).
     */
    fun encodePublicKey(key: PublicKey): String {
        return encoder.encodeToString(key.encoded)
    }

    /**
     * Sign a challenge message with an Ed25519 private key.
     *
     * SEC7-S-01/S-02: Strips "chal_" prefix before base64url-decoding the nonce,
     * and uses length-prefix encoding for variable-length fields to match the
     * server's updated format:
     *   nonce (32B) || signingKey || len(origin) (4B BE) || origin || len(ts) (4B BE) || ts
     */
    fun signChallenge(
        privateKey: PrivateKey,
        nonce: String,
        signingKeyBase64: String,
        serverOrigin: String = "https://test.kidsync.app",
        timestamp: String = Instant.now().toString(),
    ): Pair<String, String> {
        // Strip "chal_" prefix before base64url-decoding
        val nonceBase64 = if (nonce.startsWith("chal_")) nonce.removePrefix("chal_") else nonce
        val nonceBytes = Base64.getUrlDecoder().decode(nonceBase64)
        val signingKeyBytes = decoder.decode(signingKeyBase64)
        val originBytes = serverOrigin.toByteArray(Charsets.UTF_8)
        val timestampBytes = timestamp.toByteArray(Charsets.UTF_8)
        // Length-prefix encoding for variable-length fields
        val message = nonceBytes + signingKeyBytes +
            lengthPrefix(originBytes) + originBytes +
            lengthPrefix(timestampBytes) + timestampBytes
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(message)
        val signatureBytes = signer.sign()
        return Pair(encoder.encodeToString(signatureBytes), timestamp)
    }

    /**
     * Encode a 4-byte big-endian length prefix for a byte array.
     */
    private fun lengthPrefix(data: ByteArray): ByteArray {
        val len = data.size
        return byteArrayOf(
            (len shr 24 and 0xFF).toByte(),
            (len shr 16 and 0xFF).toByte(),
            (len shr 8 and 0xFF).toByte(),
            (len and 0xFF).toByte()
        )
    }

    // ---- Hash Chain ----

    /**
     * Compute currentHash = SHA256(hexDecode(prevHash) + base64Decode(encryptedPayload))
     */
    fun computeHash(prevHash: String, encryptedPayload: String): String {
        val prevBytes = HashUtil.hexToBytes(prevHash)
        val payloadBytes = decoder.decode(encryptedPayload)
        return HashUtil.sha256Hex(prevBytes, payloadBytes)
    }

    // ---- Device Registration ----

    /**
     * Register a new device with the server. Returns the deviceId and key material.
     * Does NOT authenticate (no session token).
     */
    suspend fun registerDevice(
        client: io.ktor.client.HttpClient,
        signingKeyPair: KeyPair = generateSigningKeyPair(),
        encryptionKeyPair: KeyPair = generateEncryptionKeyPair(),
    ): TestDevice {
        val signingKeyBase64 = encodePublicKey(signingKeyPair.public)
        val encryptionKeyBase64 = encodePublicKey(encryptionKeyPair.public)

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(signingKey = signingKeyBase64, encryptionKey = encryptionKeyBase64))
        }
        assertEquals(HttpStatusCode.Created, response.status,
            "Registration failed: ${response.bodyAsText()}")
        val body = response.body<RegisterResponse>()

        return TestDevice(
            deviceId = body.deviceId,
            sessionToken = "", // Not authenticated yet
            signingKeyPair = signingKeyPair,
            encryptionKeyPair = encryptionKeyPair,
            signingKeyBase64 = signingKeyBase64,
            encryptionKeyBase64 = encryptionKeyBase64,
        )
    }

    // ---- Authentication ----

    /**
     * Perform full challenge-response authentication for a registered device.
     * Returns updated TestDevice with session token.
     */
    suspend fun authenticateDevice(
        client: io.ktor.client.HttpClient,
        device: TestDevice,
        serverOrigin: String = "https://test.kidsync.app",
    ): TestDevice {
        // Step 1: Request challenge
        val challengeResponse = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }
        assertEquals(HttpStatusCode.OK, challengeResponse.status,
            "Challenge request failed: ${challengeResponse.bodyAsText()}")
        val challenge = challengeResponse.body<ChallengeResponse>()

        // Step 2: Sign challenge
        val timestamp = Instant.now().toString()
        val (signature, ts) = signChallenge(
            privateKey = device.signingKeyPair.private,
            nonce = challenge.nonce,
            signingKeyBase64 = device.signingKeyBase64,
            serverOrigin = serverOrigin,
            timestamp = timestamp,
        )

        // Step 3: Verify signature
        val verifyResponse = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challenge.nonce,
                signature = signature,
                timestamp = ts,
            ))
        }
        assertEquals(HttpStatusCode.OK, verifyResponse.status,
            "Verification failed: ${verifyResponse.bodyAsText()}")
        val verify = verifyResponse.body<VerifyResponse>()

        return device.copy(sessionToken = verify.sessionToken)
    }

    // ---- Combined Helpers ----

    /**
     * Register, authenticate, and create a bucket. Returns TestDevice with bucketId.
     */
    suspend fun setupDeviceWithBucket(
        client: io.ktor.client.HttpClient,
        signingKeyPair: KeyPair = generateSigningKeyPair(),
        encryptionKeyPair: KeyPair = generateEncryptionKeyPair(),
    ): TestDevice {
        val device = registerDevice(client, signingKeyPair, encryptionKeyPair)
        val authedDevice = authenticateDevice(client, device)

        val bucketResponse = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authedDevice.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Created, bucketResponse.status,
            "Bucket creation failed: ${bucketResponse.bodyAsText()}")
        val bucket = bucketResponse.body<BucketResponse>()

        return authedDevice.copy(bucketId = bucket.bucketId)
    }

    /**
     * Setup two devices sharing the same bucket via invite flow.
     * Returns (deviceA, deviceB) where both have access to the same bucket.
     */
    suspend fun setupTwoDeviceBucket(
        client: io.ktor.client.HttpClient,
    ): Pair<TestDevice, TestDevice> {
        // Device A creates bucket
        val deviceA = setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Device A creates invite
        val inviteToken = "test-invite-token-${System.nanoTime()}"
        val tokenHash = HashUtil.sha256HexString(inviteToken)
        val inviteResponse = client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = tokenHash))
        }
        assertEquals(HttpStatusCode.Created, inviteResponse.status,
            "Invite creation failed: ${inviteResponse.bodyAsText()}")

        // Device B registers and authenticates
        val deviceBReg = registerDevice(client)
        val deviceB = authenticateDevice(client, deviceBReg)

        // Device B joins bucket with invite token
        val joinResponse = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken))
        }
        assertEquals(HttpStatusCode.OK, joinResponse.status,
            "Join failed: ${joinResponse.bodyAsText()}")

        return Pair(deviceA, deviceB.copy(bucketId = bucketId))
    }

    // ---- Op Upload Helpers ----

    /**
     * Upload a chain of ops one at a time with a valid hash chain.
     * Returns the last currentHash.
     */
    suspend fun uploadOpsChain(
        client: io.ktor.client.HttpClient,
        device: TestDevice,
        count: Int,
        startPrevHash: String = "0".repeat(64),
        localIdPrefix: String = "op",
    ): String {
        val bucketId = device.bucketId ?: error("Device must have a bucketId")
        var prevHash = startPrevHash
        for (i in 1..count) {
            val payload = encoder.encodeToString("payload-$localIdPrefix-$i".toByteArray())
            val curHash = computeHash(prevHash, payload)
            val response = client.post("/buckets/$bucketId/ops") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
                setBody(OpsBatchRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = device.deviceId,
                            keyEpoch = 1,
                            encryptedPayload = payload,
                            prevHash = prevHash,
                            currentHash = curHash,
                        )
                    )
                ))
            }
            assertEquals(HttpStatusCode.Created, response.status,
                "Upload op $localIdPrefix-$i failed: ${response.bodyAsText()}")
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
        device: TestDevice,
        count: Int,
        startPrevHash: String = "0".repeat(64),
        localIdPrefix: String = "op",
    ): String {
        val bucketId = device.bucketId ?: error("Device must have a bucketId")
        val ops = mutableListOf<OpInput>()
        var prevHash = startPrevHash
        for (i in 1..count) {
            val payload = encoder.encodeToString("payload-$localIdPrefix-$i".toByteArray())
            val curHash = computeHash(prevHash, payload)
            ops.add(OpInput(
                deviceId = device.deviceId,
                keyEpoch = 1,
                encryptedPayload = payload,
                prevHash = prevHash,
                currentHash = curHash,
            ))
            prevHash = curHash
        }
        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(ops = ops))
        }
        assertEquals(HttpStatusCode.Created, response.status, "Batch upload failed: ${response.bodyAsText()}")
        val body = response.body<OpsBatchResponse>()
        assertEquals(count, body.accepted.size, "Expected $count accepted ops")
        return prevHash
    }
}
