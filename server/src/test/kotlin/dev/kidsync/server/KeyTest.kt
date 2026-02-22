package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.TestHelper.encodePublicKey
import dev.kidsync.server.TestHelper.generateSigningKeyPair
import dev.kidsync.server.models.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import java.security.Signature
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KeyTest {

    // ================================================================
    // Wrapped Key Upload and Retrieval
    // ================================================================

    @Test
    fun `upload wrapped DEK for target device returns 201`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val response = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "encrypted-dek-for-deviceB-epoch1",
                keyEpoch = 1,
            ))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `retrieve wrapped DEK for authenticated device at given epoch`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        // Device A wraps DEK for device B
        client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "wrapped-dek-epoch-1",
                keyEpoch = 1,
            ))
        }

        // Device B retrieves wrapped DEK
        val response = client.get("/keys/wrapped?epoch=1") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<WrappedKeyResponse>()
        assertEquals("wrapped-dek-epoch-1", body.wrappedDek)
        assertEquals(1, body.keyEpoch)
        assertEquals(deviceA.deviceId, body.wrappedBy)
    }

    @Test
    fun `retrieve wrapped DEK with no key at epoch returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.get("/keys/wrapped?epoch=99") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `upload wrapped DEK without cross-signature`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val response = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "wrapped-dek-no-sig",
                keyEpoch = 1,
            ))
        }
        assertEquals(HttpStatusCode.Created, response.status)

        // Device B retrieves wrapped DEK
        val getResp = client.get("/keys/wrapped?epoch=1") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, getResp.status)
        val body = getResp.body<WrappedKeyResponse>()
        assertEquals("wrapped-dek-no-sig", body.wrappedDek)
        assertEquals(deviceA.deviceId, body.wrappedBy)
    }

    @Test
    fun `upload wrapped DEK for multiple epochs`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        // Upload epoch 1
        client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "dek-epoch-1",
                keyEpoch = 1,
            ))
        }

        // Upload epoch 2
        client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "dek-epoch-2",
                keyEpoch = 2,
            ))
        }

        // Retrieve epoch 1
        val resp1 = client.get("/keys/wrapped?epoch=1") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }.body<WrappedKeyResponse>()
        assertEquals("dek-epoch-1", resp1.wrappedDek)
        assertEquals(1, resp1.keyEpoch)

        // Retrieve epoch 2
        val resp2 = client.get("/keys/wrapped?epoch=2") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }.body<WrappedKeyResponse>()
        assertEquals("dek-epoch-2", resp2.wrappedDek)
        assertEquals(2, resp2.keyEpoch)
    }

    // ================================================================
    // Key Attestations
    // ================================================================

    @Test
    fun `upload key attestation cross-signature`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        // Device A attests Device B's encryption key
        // Sign: Ed25519(attestedDevice || attestedKey)
        val message = "${deviceB.deviceId}${deviceB.encryptionKeyBase64}"
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(deviceA.signingKeyPair.private)
        signer.update(message.toByteArray(Charsets.UTF_8))
        val attestSig = Base64.getEncoder().encodeToString(signer.sign())

        val response = client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(KeyAttestationRequest(
                attestedDevice = deviceB.deviceId,
                attestedKey = deviceB.encryptionKeyBase64,
                signature = attestSig,
            ))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `retrieve attestations for a device`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        // Device A attests Device B
        val message = "${deviceB.deviceId}${deviceB.encryptionKeyBase64}"
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(deviceA.signingKeyPair.private)
        signer.update(message.toByteArray(Charsets.UTF_8))
        val attestSig = Base64.getEncoder().encodeToString(signer.sign())

        client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(KeyAttestationRequest(
                attestedDevice = deviceB.deviceId,
                attestedKey = deviceB.encryptionKeyBase64,
                signature = attestSig,
            ))
        }

        // Retrieve attestations for device B
        val response = client.get("/keys/attestations/${deviceB.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val attestations = response.body<AttestationListResponse>().attestations
        assertEquals(1, attestations.size)
        assertEquals(deviceA.deviceId, attestations[0].signerDevice)
        assertEquals(deviceB.deviceId, attestations[0].attestedDevice)
        assertEquals(deviceB.encryptionKeyBase64, attestations[0].attestedKey)
        assertEquals(attestSig, attestations[0].signature)
        assertNotNull(attestations[0].createdAt)
    }

    @Test
    fun `unique constraint one attestation per signer-attested pair`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val message = "${deviceB.deviceId}${deviceB.encryptionKeyBase64}"
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(deviceA.signingKeyPair.private)
        signer.update(message.toByteArray(Charsets.UTF_8))
        val attestSig = Base64.getEncoder().encodeToString(signer.sign())

        // First attestation succeeds
        val resp1 = client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(KeyAttestationRequest(
                attestedDevice = deviceB.deviceId,
                attestedKey = deviceB.encryptionKeyBase64,
                signature = attestSig,
            ))
        }
        assertEquals(HttpStatusCode.Created, resp1.status)

        // Second attestation with same signer+attested pair should fail
        val resp2 = client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(KeyAttestationRequest(
                attestedDevice = deviceB.deviceId,
                attestedKey = deviceB.encryptionKeyBase64,
                signature = attestSig,
            ))
        }

        assertEquals(HttpStatusCode.Conflict, resp2.status)
    }

    @Test
    fun `mutual cross-signing both devices attest each other`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        // Device A attests Device B
        val msgAB = "${deviceB.deviceId}${deviceB.encryptionKeyBase64}"
        val signerA = Signature.getInstance("Ed25519")
        signerA.initSign(deviceA.signingKeyPair.private)
        signerA.update(msgAB.toByteArray(Charsets.UTF_8))
        val sigAB = Base64.getEncoder().encodeToString(signerA.sign())

        client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(KeyAttestationRequest(
                attestedDevice = deviceB.deviceId,
                attestedKey = deviceB.encryptionKeyBase64,
                signature = sigAB,
            ))
        }

        // Device B attests Device A
        val msgBA = "${deviceA.deviceId}${deviceA.encryptionKeyBase64}"
        val signerB = Signature.getInstance("Ed25519")
        signerB.initSign(deviceB.signingKeyPair.private)
        signerB.update(msgBA.toByteArray(Charsets.UTF_8))
        val sigBA = Base64.getEncoder().encodeToString(signerB.sign())

        client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(KeyAttestationRequest(
                attestedDevice = deviceA.deviceId,
                attestedKey = deviceA.encryptionKeyBase64,
                signature = sigBA,
            ))
        }

        // Check attestations for device A
        val attestsA = client.get("/keys/attestations/${deviceA.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }.body<AttestationListResponse>().attestations
        assertEquals(1, attestsA.size)
        assertEquals(deviceB.deviceId, attestsA[0].signerDevice)

        // Check attestations for device B
        val attestsB = client.get("/keys/attestations/${deviceB.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<AttestationListResponse>().attestations
        assertEquals(1, attestsB.size)
        assertEquals(deviceA.deviceId, attestsB[0].signerDevice)
    }

    @Test
    fun `attestations for unknown device returns 403 - no shared bucket`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        // SEC4-S-03: Querying attestations for a device with no shared bucket should be denied
        val response = client.get("/keys/attestations/00000000-0000-0000-0000-000000000000") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ================================================================
    // Recovery Blob
    // ================================================================

    @Test
    fun `upload recovery blob`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.post("/recovery") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(RecoveryBlobRequest(encryptedBlob = "encrypted-recovery-data"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `retrieve recovery blob`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        // Upload
        client.post("/recovery") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(RecoveryBlobRequest(encryptedBlob = "my-recovery-blob"))
        }

        // Retrieve
        val response = client.get("/recovery") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<RecoveryBlobResponse>()
        assertEquals("my-recovery-blob", body.encryptedBlob)
        assertNotNull(body.createdAt)
    }

    @Test
    fun `recovery blob upserts per device`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        // Upload first recovery blob
        val resp1 = client.post("/recovery") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(RecoveryBlobRequest(encryptedBlob = "version-1"))
        }
        assertEquals(HttpStatusCode.Created, resp1.status)

        // SEC5-S-06: Immediate second upload is rate-limited (DB-based, max 1 per hour)
        val resp2 = client.post("/recovery") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(RecoveryBlobRequest(encryptedBlob = "version-2"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, resp2.status)

        // Retrieve -- should still get version-1 since overwrite was rate-limited
        val response = client.get("/recovery") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<RecoveryBlobResponse>()
        assertEquals("version-1", body.encryptedBlob)
    }

    @Test
    fun `recovery blob not found returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.get("/recovery") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
