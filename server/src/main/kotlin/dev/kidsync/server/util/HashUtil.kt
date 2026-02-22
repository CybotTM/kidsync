package dev.kidsync.server.util

import java.security.MessageDigest
import java.util.Base64

object HashUtil {

    /**
     * Compute SHA-256 of concatenated byte arrays, returned as lowercase hex string.
     */
    fun sha256Hex(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            digest.update(part)
        }
        return digest.digest().toHexString()
    }

    /**
     * Verify hash chain: currentHash == SHA256(bytes(prevHash) + payloadBytes)
     * SEC-S-03: Uses constant-time comparison to prevent timing side-channel attacks.
     * SEC4-S-18: Accepts pre-decoded payload bytes to avoid redundant base64 decoding.
     */
    fun verifyHashChain(prevHash: String, payloadBytes: ByteArray, expectedCurrentHash: String): Boolean {
        val prevHashBytes = hexToBytes(prevHash)
        val computed = sha256Hex(prevHashBytes, payloadBytes)
        return MessageDigest.isEqual(computed.toByteArray(), expectedCurrentHash.toByteArray())
    }

    /**
     * Compute checkpoint hash: SHA256(concat of all encrypted payloads in range).
     */
    fun computeCheckpointHash(encryptedPayloads: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (payload in encryptedPayloads) {
            digest.update(Base64.getDecoder().decode(payload))
        }
        return digest.digest().toHexString()
    }

    /**
     * Convert hex string to byte array.
     */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Compute SHA-256 of a string, returned as lowercase hex.
     */
    fun sha256HexString(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).toHexString()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
