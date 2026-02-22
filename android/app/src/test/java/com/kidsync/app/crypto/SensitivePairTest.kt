package com.kidsync.app.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain

/**
 * Tests for [SensitivePair] covering:
 * - close() zeros ByteArray values
 * - use {} block pattern works
 * - double-close is safe (idempotent)
 * - toString doesn't leak data
 * - non-ByteArray values are not affected by close
 * - equals/hashCode before and after close
 */
class SensitivePairTest : FunSpec({

    test("close zeros ByteArray first value") {
        val first = byteArrayOf(1, 2, 3, 4, 5)
        val second = byteArrayOf(10, 20, 30, 40, 50)
        val pair = SensitivePair(first, second)

        pair.close()

        first.all { it == 0.toByte() } shouldBe true
        second.all { it == 0.toByte() } shouldBe true
    }

    test("close zeros ByteArray second value") {
        val first = "non-sensitive"
        val second = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val pair = SensitivePair(first, second)

        pair.close()

        // String is unaffected
        pair.first shouldBe "non-sensitive"
        // ByteArray is zeroed
        second.all { it == 0.toByte() } shouldBe true
    }

    test("use block pattern automatically zeros on scope exit") {
        val privateKey = byteArrayOf(42, 43, 44, 45)
        val publicKey = byteArrayOf(100, 101, 102, 103)

        SensitivePair(publicKey, privateKey).use { (pub, priv) ->
            pub[0] shouldBe 100.toByte()
            priv[0] shouldBe 42.toByte()
        }

        // After use block, the ByteArrays should be zeroed
        privateKey.all { it == 0.toByte() } shouldBe true
        publicKey.all { it == 0.toByte() } shouldBe true
    }

    test("double close is safe and idempotent") {
        val data = byteArrayOf(1, 2, 3)
        val pair = SensitivePair(data, "safe")

        pair.close()
        pair.close() // Should not throw

        data.all { it == 0.toByte() } shouldBe true
    }

    test("toString does not leak data") {
        val sensitiveKey = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val pair = SensitivePair(sensitiveKey, "secret-password")

        val str = pair.toString()

        str shouldNotContain "DEAD"
        str shouldNotContain "secret"
        str shouldNotContain "password"
        str shouldBe "SensitivePair([REDACTED], [REDACTED])"
    }

    test("non-ByteArray values are not affected by close") {
        val pair = SensitivePair("hello", 42)

        pair.close()

        pair.first shouldBe "hello"
        pair.second shouldBe 42
    }

    test("equals works with ByteArray values before close") {
        val pair1 = SensitivePair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val pair2 = SensitivePair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))

        pair1 shouldBe pair2
    }

    test("hashCode is consistent with ByteArray content") {
        val pair1 = SensitivePair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val pair2 = SensitivePair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))

        pair1.hashCode() shouldBe pair2.hashCode()
    }

    test("equals returns false for different ByteArray contents") {
        val pair1 = SensitivePair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val pair2 = SensitivePair(byteArrayOf(7, 8, 9), byteArrayOf(4, 5, 6))

        pair1 shouldNotBe pair2
    }

    test("equals works after close when both are zeroed") {
        val pair1 = SensitivePair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val pair2 = SensitivePair(byteArrayOf(7, 8, 9), byteArrayOf(10, 11, 12))

        pair1.close()
        pair2.close()

        // Both are now zeroed, so they should be equal
        pair1 shouldBe pair2
    }

    test("destructuring works correctly") {
        val pair = SensitivePair(byteArrayOf(1), byteArrayOf(2))
        val (first, second) = pair

        first[0] shouldBe 1.toByte()
        second[0] shouldBe 2.toByte()
    }

    test("null values are handled safely") {
        val pair = SensitivePair<ByteArray?, ByteArray?>(null, null)

        pair.close() // Should not throw

        pair.first shouldBe null
        pair.second shouldBe null
    }
})
