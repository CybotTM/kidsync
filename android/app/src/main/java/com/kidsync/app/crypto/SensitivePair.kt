package com.kidsync.app.crypto

import java.io.Closeable
import java.util.Arrays

/**
 * SEC5-A-09: A [Closeable] wrapper for pairs of values where one or both may
 * contain sensitive ByteArray key material. When [close] is called, any ByteArray
 * values in the pair are zeroed out to minimize the window of key exposure in memory.
 *
 * Usage with Kotlin's [use] extension:
 * ```kotlin
 * keyManager.deriveEncryptionKeyPairSafe(seed).use { (publicKey, privateKey) ->
 *     // Use the keys...
 * }
 * // privateKey ByteArray is automatically zeroed on scope exit
 * ```
 *
 * Design notes:
 * - [toString] redacts values to prevent accidental logging of key material
 * - [close] is idempotent (safe to call multiple times)
 * - Only ByteArray values are zeroed; other types are unaffected
 * - Implements [Closeable] (not [AutoCloseable]) for Kotlin [use] compatibility
 */
class SensitivePair<out A, out B>(
    val first: A,
    val second: B
) : Closeable {

    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        zeroIfByteArray(first)
        zeroIfByteArray(second)
    }

    /**
     * Destructuring support: `val (a, b) = sensitivePair`
     */
    operator fun component1(): A = first
    operator fun component2(): B = second

    /**
     * Redacted toString to prevent accidental logging of sensitive key material.
     */
    override fun toString(): String = "SensitivePair([REDACTED], [REDACTED])"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SensitivePair<*, *>) return false
        return equalValues(first, other.first) && equalValues(second, other.second)
    }

    override fun hashCode(): Int {
        var result = hashValue(first)
        result = 31 * result + hashValue(second)
        return result
    }

    private fun equalValues(a: Any?, b: Any?): Boolean {
        return when {
            a is ByteArray && b is ByteArray -> a.contentEquals(b)
            else -> a == b
        }
    }

    private fun hashValue(value: Any?): Int {
        return when (value) {
            is ByteArray -> value.contentHashCode()
            else -> value?.hashCode() ?: 0
        }
    }

    companion object {
        private fun zeroIfByteArray(value: Any?) {
            if (value is ByteArray) {
                Arrays.fill(value, 0.toByte())
            }
        }
    }
}
