package com.kidsync.app.domain.usecase.sync

import com.kidsync.app.domain.model.OpLogEntry
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject

/**
 * Verifies per-device hash chains as defined in sync-protocol.md Section 6.
 *
 * Formula: currentHash = SHA256(bytes(devicePrevHash) + base64Decode(encryptedPayload))
 * where bytes() decodes the 64-character hex string to 32 raw bytes.
 *
 * Genesis value: 0000000000000000000000000000000000000000000000000000000000000000
 */
// TODO(SEC6-A-04): Verify hash chain continuity against local state. When receiving new ops,
// the first op's devicePrevHash should match the last known hash for that device stored locally.
// Currently we only verify intra-batch continuity but not continuity with previously persisted ops.
class HashChainVerifier @Inject constructor() {

    companion object {
        const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    }

    /**
     * Verify hash chains for a set of ops, grouped by device.
     * Checks both individual hash correctness and chain continuity
     * (each op's devicePrevHash must equal the previous op's currentHash).
     * Returns failure with details if any chain is broken.
     */
    fun verifyChains(ops: List<OpLogEntry>): Result<Unit> {
        // Group by device and sort by deviceSequence
        val byDevice = ops.groupBy { it.deviceId }

        for ((deviceId, deviceOps) in byDevice) {
            val sorted = deviceOps.sortedBy { it.deviceSequence }

            // Verify each op's hash is correctly computed
            for (op in sorted) {
                val expected = computeHash(op.devicePrevHash, op.encryptedPayload)
                if (expected != op.currentHash) {
                    return Result.failure(
                        HashChainBreakException(
                            deviceId = deviceId.toString(),
                            deviceSequence = op.deviceSequence,
                            expectedHash = expected,
                            actualHash = op.currentHash
                        )
                    )
                }
            }

            // Verify chain continuity: consecutive pairs must be linked
            for (i in 0 until sorted.size - 1) {
                val current = sorted[i]
                val next = sorted[i + 1]
                if (current.currentHash != next.devicePrevHash) {
                    return Result.failure(
                        HashChainBreakException(
                            deviceId = deviceId.toString(),
                            deviceSequence = next.deviceSequence,
                            expectedHash = current.currentHash,
                            actualHash = next.devicePrevHash
                        )
                    )
                }
            }
        }

        return Result.success(Unit)
    }

    /**
     * Compute a single hash chain link.
     *
     * currentHash = SHA256(bytes(devicePrevHash) + base64Decode(encryptedPayload))
     */
    fun computeHash(devicePrevHash: String, encryptedPayloadBase64: String): String {
        val prevHashBytes = hexToBytes(devicePrevHash)
        val payloadBytes = Base64.getDecoder().decode(encryptedPayloadBase64)

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(prevHashBytes)
        digest.update(payloadBytes)

        return bytesToHex(digest.digest())
    }

    /**
     * Build a complete chain from scratch, returning the final hash.
     */
    fun buildChain(encryptedPayloads: List<String>): List<String> {
        val hashes = mutableListOf<String>()
        var prevHash = GENESIS_HASH

        for (payload in encryptedPayloads) {
            val currentHash = computeHash(prevHash, payload)
            hashes.add(currentHash)
            prevHash = currentHash
        }

        return hashes
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

class HashChainBreakException(
    val deviceId: String,
    val deviceSequence: Long,
    val expectedHash: String,
    val actualHash: String
) : Exception(
    "HASH_CHAIN_BREAK: device=$deviceId, seq=$deviceSequence, " +
            "expected=$expectedHash, actual=$actualHash"
)
