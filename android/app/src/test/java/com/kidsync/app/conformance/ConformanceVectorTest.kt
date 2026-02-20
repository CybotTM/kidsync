package com.kidsync.app.conformance

import com.kidsync.app.crypto.CanonicalJsonSerializer
import com.kidsync.app.domain.usecase.sync.HashChainVerifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*

/**
 * Conformance tests using the official test vectors from tv03-hash-chain.json.
 *
 * Verifies:
 * - Hash chain computation formula: SHA256(bytes(devicePrevHash) + base64Decode(encryptedPayload))
 * - Genesis hash (all zeros)
 * - Valid chain verification
 * - Tampered chain detection
 * - Canonical serialization hash vectors from tv02
 */
class ConformanceVectorTest : FunSpec({

    val hashChainVerifier = HashChainVerifier()
    val serializer = CanonicalJsonSerializer()

    // ---- TV03: Hash Chain Verification ----

    test("TV03: genesis hash is 64 hex zeros") {
        HashChainVerifier.GENESIS_HASH shouldBe "0000000000000000000000000000000000000000000000000000000000000000"
        HashChainVerifier.GENESIS_HASH.length shouldBe 64
    }

    test("TV03: valid chain op 1 - SHA256(genesis + base64Decode('dGVzdC1wYXlsb2FkLTE='))") {
        val hash = hashChainVerifier.computeHash(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "dGVzdC1wYXlsb2FkLTE="
        )
        hash shouldBe "4f3eb937b67738b3e9afef89501ca534612f364af3e7210a1a7b2f3ae64e9d72"
    }

    test("TV03: valid chain op 2") {
        val hash = hashChainVerifier.computeHash(
            "4f3eb937b67738b3e9afef89501ca534612f364af3e7210a1a7b2f3ae64e9d72",
            "dGVzdC1wYXlsb2FkLTI="
        )
        hash shouldBe "006f64a0874cf4832ee4818839ae6b2c9c7c389f2e030cc0b4141aa3754b9b64"
    }

    test("TV03: valid chain op 3") {
        val hash = hashChainVerifier.computeHash(
            "006f64a0874cf4832ee4818839ae6b2c9c7c389f2e030cc0b4141aa3754b9b64",
            "dGVzdC1wYXlsb2FkLTM="
        )
        hash shouldBe "7ecac2978a55cebff594ce1c926146d111dc8497d4091e04cf088362fa9994d7"
    }

    test("TV03: valid chain op 4") {
        val hash = hashChainVerifier.computeHash(
            "7ecac2978a55cebff594ce1c926146d111dc8497d4091e04cf088362fa9994d7",
            "dGVzdC1wYXlsb2FkLTQ="
        )
        hash shouldBe "964571cfc036ff673d72e2d80a212a2462a382b94d19946124b1a678dd9b25c1"
    }

    test("TV03: valid chain op 5") {
        val hash = hashChainVerifier.computeHash(
            "964571cfc036ff673d72e2d80a212a2462a382b94d19946124b1a678dd9b25c1",
            "dGVzdC1wYXlsb2FkLTU="
        )
        hash shouldBe "c0c6de965d04a7102b62743e83c616dff2222a904d29c16b266eac3213053a26"
    }

    test("TV03: buildChain produces all correct hashes for 5-op valid chain") {
        val payloads = listOf(
            "dGVzdC1wYXlsb2FkLTE=",
            "dGVzdC1wYXlsb2FkLTI=",
            "dGVzdC1wYXlsb2FkLTM=",
            "dGVzdC1wYXlsb2FkLTQ=",
            "dGVzdC1wYXlsb2FkLTU="
        )

        val hashes = hashChainVerifier.buildChain(payloads)

        hashes.size shouldBe 5
        hashes[0] shouldBe "4f3eb937b67738b3e9afef89501ca534612f364af3e7210a1a7b2f3ae64e9d72"
        hashes[1] shouldBe "006f64a0874cf4832ee4818839ae6b2c9c7c389f2e030cc0b4141aa3754b9b64"
        hashes[2] shouldBe "7ecac2978a55cebff594ce1c926146d111dc8497d4091e04cf088362fa9994d7"
        hashes[3] shouldBe "964571cfc036ff673d72e2d80a212a2462a382b94d19946124b1a678dd9b25c1"
        hashes[4] shouldBe "c0c6de965d04a7102b62743e83c616dff2222a904d29c16b266eac3213053a26"
    }

    test("TV03: tampered chain - modified op 3 payload produces different hash") {
        // Original payload: "dGVzdC1wYXlsb2FkLTM=" (test-payload-3)
        // Tampered payload: "VEFNUEVSRUQtcGF5bG9hZC0z" (TAMPERED-payload-3)
        val tamperedHash = hashChainVerifier.computeHash(
            "006f64a0874cf4832ee4818839ae6b2c9c7c389f2e030cc0b4141aa3754b9b64",
            "VEFNUEVSRUQtcGF5bG9hZC0z"
        )

        // Tampered hash must NOT match the original chain hash
        tamperedHash shouldBe "583154844017458b00cda98d920c920a0dd3598f25caeabb98b7cf29468b65fc"
        (tamperedHash != "7ecac2978a55cebff594ce1c926146d111dc8497d4091e04cf088362fa9994d7") shouldBe true
    }

    test("TV03: tampered chain cascade - op 4 with tampered prevHash") {
        val hash = hashChainVerifier.computeHash(
            "583154844017458b00cda98d920c920a0dd3598f25caeabb98b7cf29468b65fc",
            "dGVzdC1wYXlsb2FkLTQ="
        )
        hash shouldBe "a37df64eb8a8119bdf617b30a8c2f6523739ec6295c1c9f6a868e5d56fbd9472"
    }

    test("TV03: tampered chain cascade - op 5 with tampered prevHash") {
        val hash = hashChainVerifier.computeHash(
            "a37df64eb8a8119bdf617b30a8c2f6523739ec6295c1c9f6a868e5d56fbd9472",
            "dGVzdC1wYXlsb2FkLTU="
        )
        hash shouldBe "e2b81976a645be2fb6cb54a9dead8c177230f6f9419dcba752f642ee2491ab17"
    }

    // ---- TV02: Canonical Serialization Hashes ----

    test("TV02: Device A custody schedule canonical SHA-256") {
        val payloadMap = mapOf<String, Any?>(
            "payloadType" to "SetCustodySchedule",
            "entityId" to "aaaa1111-2222-3333-4444-555566667777",
            "timestamp" to "2026-03-28T10:00:00.000Z",
            "operationType" to "CREATE",
            "scheduleId" to "aaaa1111-2222-3333-4444-555566667777",
            "childId" to "c1d2e3f4-5678-9abc-def0-123456789012",
            "anchorDate" to "2026-04-01",
            "cycleLengthDays" to 14,
            "pattern" to listOf(
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb"
            ),
            "effectiveFrom" to "2026-04-01T00:00:00.000Z",
            "timeZone" to "America/New_York"
        )

        val (_, hash) = serializer.serializeAndHash(payloadMap)
        hash shouldBe "f06fd00ed9d4d7d2da3873b56f2cc52856cc73b84efba2e40ad182645923c3ba"
    }

    test("TV02: Device B custody schedule canonical SHA-256") {
        val payloadMap = mapOf<String, Any?>(
            "payloadType" to "SetCustodySchedule",
            "entityId" to "bbbb1111-2222-3333-4444-555566667777",
            "timestamp" to "2026-03-28T10:05:00.000Z",
            "operationType" to "CREATE",
            "scheduleId" to "bbbb1111-2222-3333-4444-555566667777",
            "childId" to "c1d2e3f4-5678-9abc-def0-123456789012",
            "anchorDate" to "2026-04-01",
            "cycleLengthDays" to 14,
            "pattern" to listOf(
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
                "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb"
            ),
            "effectiveFrom" to "2026-04-01T00:00:00.000Z",
            "timeZone" to "America/New_York"
        )

        val (_, hash) = serializer.serializeAndHash(payloadMap)
        hash shouldBe "d57515e13c90fe4399f149e5554fe07129d2eb440922a1be1a74e627d3dc6586"
    }
})
