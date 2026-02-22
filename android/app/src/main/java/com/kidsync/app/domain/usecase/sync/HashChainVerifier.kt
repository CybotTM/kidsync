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
/**
 * SEC6-A-04: Now supports verifying hash chain continuity against local state
 * via [verifyChains] with the [localLastHashes] parameter. When receiving new ops,
 * the first op's devicePrevHash is checked against the last known hash for that
 * device stored locally, preventing gaps or substitutions in the chain.
 */
class HashChainVerifier @Inject constructor() {

    companion object {
        const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    }

    /**
     * Verify hash chains for a set of ops, grouped by device.
     * Checks both individual hash correctness and chain continuity
     * (each op's devicePrevHash must equal the previous op's currentHash).
     * Returns failure with details if any chain is broken.
     *
     * @param ops The batch of incoming ops to verify
     * @param localLastHashes SEC6-A-04: Optional map of deviceId -> last known currentHash
     *   from locally persisted ops. When provided, the first op for each device in the
     *   batch must have its devicePrevHash match the local last hash. If the device has
     *   no local ops yet, it is not present in the map (or maps to null), and the first
     *   op's prevHash is accepted as-is (could be GENESIS_HASH for a new device).
     */
    fun verifyChains(
        ops: List<OpLogEntry>,
        localLastHashes: Map<String, String>? = null
    ): Result<Unit> {
        // Group by device and sort by deviceSequence
        val byDevice = ops.groupBy { it.deviceId }

        for ((deviceId, deviceOps) in byDevice) {
            val sorted = deviceOps.sortedBy { it.deviceSequence }

            // SEC6-A-04: Verify continuity with local state
            if (localLastHashes != null && sorted.isNotEmpty()) {
                val localLastHash = localLastHashes[deviceId]
                if (localLastHash != null) {
                    val firstOp = sorted.first()
                    if (firstOp.devicePrevHash != localLastHash) {
                        return Result.failure(
                            HashChainContinuityException(
                                deviceId = deviceId,
                                expectedPrevHash = localLastHash,
                                actualPrevHash = firstOp.devicePrevHash,
                                deviceSequence = firstOp.deviceSequence
                            )
                        )
                    }
                }
            }

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

/**
 * SEC6-A-04: Thrown when the first op in an incoming batch does not
 * chain correctly against the last locally persisted op for that device.
 */
class HashChainContinuityException(
    val deviceId: String,
    val expectedPrevHash: String,
    val actualPrevHash: String,
    val deviceSequence: Long
) : Exception(
    "HASH_CHAIN_CONTINUITY_BREAK: device=$deviceId, seq=$deviceSequence, " +
            "expectedPrev=$expectedPrevHash, actualPrev=$actualPrevHash"
)
