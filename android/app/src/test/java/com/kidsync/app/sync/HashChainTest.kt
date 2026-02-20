package com.kidsync.app.sync

import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.usecase.sync.HashChainBreakException
import com.kidsync.app.domain.usecase.sync.HashChainVerifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.UUID

/**
 * Tests for HashChainVerifier using tv03 conformance vectors.
 *
 * Formula: currentHash = SHA256(bytes(devicePrevHash) + base64Decode(encryptedPayload))
 */
class HashChainTest : FunSpec({

    val verifier = HashChainVerifier()
    val deviceId = UUID.fromString("cc001122-3344-5566-7788-99aabbccddee")
    val familyId = UUID.fromString("fam00001-aaaa-bbbb-cccc-dddddddddddd")

    fun makeOp(
        seq: Long,
        prevHash: String,
        payload: String,
        currentHash: String
    ): OpLogEntry = OpLogEntry(
        globalSequence = seq,
        familyId = familyId,
        deviceId = deviceId,
        deviceSequence = seq,
        entityType = EntityType.CustodySchedule,
        entityId = UUID.randomUUID(),
        operation = OperationType.CREATE,
        keyEpoch = 1,
        encryptedPayload = payload,
        devicePrevHash = prevHash,
        currentHash = currentHash,
        clientTimestamp = Instant.now()
    )

    test("computeHash: first op using genesis hash") {
        val hash = verifier.computeHash(
            HashChainVerifier.GENESIS_HASH,
            "dGVzdC1wYXlsb2FkLTE="
        )
        hash shouldBe "4f3eb937b67738b3e9afef89501ca534612f364af3e7210a1a7b2f3ae64e9d72"
    }

    test("computeHash: chained op") {
        val hash = verifier.computeHash(
            "4f3eb937b67738b3e9afef89501ca534612f364af3e7210a1a7b2f3ae64e9d72",
            "dGVzdC1wYXlsb2FkLTI="
        )
        hash shouldBe "006f64a0874cf4832ee4818839ae6b2c9c7c389f2e030cc0b4141aa3754b9b64"
    }

    test("verifyChains: valid 5-op chain passes") {
        val ops = listOf(
            makeOp(1, "0000000000000000000000000000000000000000000000000000000000000000",
                "dGVzdC1wYXlsb2FkLTE=", "4f3eb937b67738b3e9afef89501ca534612f364af3e7210a1a7b2f3ae64e9d72"),
            makeOp(2, "4f3eb937b67738b3e9afef89501ca534612f364af3e7210a1a7b2f3ae64e9d72",
                "dGVzdC1wYXlsb2FkLTI=", "006f64a0874cf4832ee4818839ae6b2c9c7c389f2e030cc0b4141aa3754b9b64"),
            makeOp(3, "006f64a0874cf4832ee4818839ae6b2c9c7c389f2e030cc0b4141aa3754b9b64",
                "dGVzdC1wYXlsb2FkLTM=", "7ecac2978a55cebff594ce1c926146d111dc8497d4091e04cf088362fa9994d7"),
            makeOp(4, "7ecac2978a55cebff594ce1c926146d111dc8497d4091e04cf088362fa9994d7",
                "dGVzdC1wYXlsb2FkLTQ=", "964571cfc036ff673d72e2d80a212a2462a382b94d19946124b1a678dd9b25c1"),
            makeOp(5, "964571cfc036ff673d72e2d80a212a2462a382b94d19946124b1a678dd9b25c1",
                "dGVzdC1wYXlsb2FkLTU=", "c0c6de965d04a7102b62743e83c616dff2222a904d29c16b266eac3213053a26")
        )

        val result = verifier.verifyChains(ops)
        result.isSuccess shouldBe true
    }

    test("verifyChains: tampered payload detected") {
        val ops = listOf(
            makeOp(1, "0000000000000000000000000000000000000000000000000000000000000000",
                "dGVzdC1wYXlsb2FkLTE=", "4f3eb937b67738b3e9afef89501ca534612f364af3e7210a1a7b2f3ae64e9d72"),
            makeOp(2, "4f3eb937b67738b3e9afef89501ca534612f364af3e7210a1a7b2f3ae64e9d72",
                "dGVzdC1wYXlsb2FkLTI=", "006f64a0874cf4832ee4818839ae6b2c9c7c389f2e030cc0b4141aa3754b9b64"),
            // TAMPERED: payload changed but currentHash kept from original
            makeOp(3, "006f64a0874cf4832ee4818839ae6b2c9c7c389f2e030cc0b4141aa3754b9b64",
                "VEFNUEVSRUQtcGF5bG9hZC0z", "7ecac2978a55cebff594ce1c926146d111dc8497d4091e04cf088362fa9994d7")
        )

        val result = verifier.verifyChains(ops)
        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<HashChainBreakException>()

        val ex = result.exceptionOrNull() as HashChainBreakException
        ex.deviceSequence shouldBe 3
    }

    test("verifyChains: empty ops list succeeds") {
        val result = verifier.verifyChains(emptyList())
        result.isSuccess shouldBe true
    }

    test("verifyChains: single op chain") {
        val ops = listOf(
            makeOp(1, "0000000000000000000000000000000000000000000000000000000000000000",
                "dGVzdC1wYXlsb2FkLTE=", "4f3eb937b67738b3e9afef89501ca534612f364af3e7210a1a7b2f3ae64e9d72")
        )

        val result = verifier.verifyChains(ops)
        result.isSuccess shouldBe true
    }

    test("verifyChains: multiple devices verified independently") {
        val deviceA = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444")
        val deviceB = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444")

        val hashA = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "dGVzdC1wYXlsb2FkLTE=")
        val hashB = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "dGVzdC1wYXlsb2FkLTI=")

        val ops = listOf(
            OpLogEntry(1, familyId, deviceA, 1, EntityType.CustodySchedule, UUID.randomUUID(),
                OperationType.CREATE, 1, "dGVzdC1wYXlsb2FkLTE=", HashChainVerifier.GENESIS_HASH,
                hashA, Instant.now()),
            OpLogEntry(2, familyId, deviceB, 1, EntityType.CustodySchedule, UUID.randomUUID(),
                OperationType.CREATE, 1, "dGVzdC1wYXlsb2FkLTI=", HashChainVerifier.GENESIS_HASH,
                hashB, Instant.now())
        )

        val result = verifier.verifyChains(ops)
        result.isSuccess shouldBe true
    }

    test("buildChain: produces correct sequence of hashes") {
        val payloads = listOf("dGVzdC1wYXlsb2FkLTE=", "dGVzdC1wYXlsb2FkLTI=")
        val hashes = verifier.buildChain(payloads)

        hashes.size shouldBe 2
        hashes[0] shouldBe "4f3eb937b67738b3e9afef89501ca534612f364af3e7210a1a7b2f3ae64e9d72"
        hashes[1] shouldBe "006f64a0874cf4832ee4818839ae6b2c9c7c389f2e030cc0b4141aa3754b9b64"
    }

    test("buildChain: empty list returns empty") {
        val hashes = verifier.buildChain(emptyList())
        hashes.size shouldBe 0
    }
})
