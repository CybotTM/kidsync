package com.kidsync.app.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Extended tests for CanonicalJsonSerializer covering edge cases:
 * - Empty object produces "{}"
 * - Deeply nested objects
 * - Very large string values
 * - Unicode in all text fields
 * - Special JSON characters (escapes)
 * - Boolean values
 * - Mixed types in arrays
 * - Float/Double precision
 */
class CanonicalJsonSerializerExtendedTest : FunSpec({

    val serializer = CanonicalJsonSerializer()

    // ── Empty Object ────────────────────────────────────────────────────────

    test("empty object produces {}") {
        val input = emptyMap<String, Any?>()
        val canonical = serializer.serialize(input)
        canonical shouldBe "{}"
    }

    // ── Deeply Nested Objects ───────────────────────────────────────────────

    test("deeply nested objects have sorted keys at every level") {
        val input = mapOf<String, Any?>(
            "z" to mapOf<String, Any?>(
                "zz" to mapOf<String, Any?>(
                    "zzz" to "deep",
                    "aaa" to "also deep"
                ),
                "aa" to "mid"
            ),
            "a" to "top"
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"a":"top","z":{"aa":"mid","zz":{"aaa":"also deep","zzz":"deep"}}}"""
    }

    test("three levels of nesting with correct key order") {
        val input = mapOf<String, Any?>(
            "level1b" to mapOf<String, Any?>(
                "level2b" to mapOf<String, Any?>(
                    "c" to 3,
                    "a" to 1,
                    "b" to 2
                ),
                "level2a" to "value"
            ),
            "level1a" to "value"
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"level1a":"value","level1b":{"level2a":"value","level2b":{"a":1,"b":2,"c":3}}}"""
    }

    // ── Large String Values ─────────────────────────────────────────────────

    test("very large string values are preserved") {
        val largeValue = "x".repeat(10_000)
        val input = mapOf("key" to largeValue)

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"key":"$largeValue"}"""
    }

    // ── Unicode Handling ────────────────────────────────────────────────────

    test("unicode characters in values are preserved") {
        val input = mapOf(
            "german" to "Kinderarzttermin",
            "emoji" to "Hello World",
            "japanese" to "子供の予定",
            "arabic" to "جدول الحضانة"
        )

        val canonical = serializer.serialize(input)
        canonical shouldContain "Kinderarzttermin"
        canonical shouldContain "子供の予定"
        canonical shouldContain "جدول الحضانة"
    }

    test("unicode in keys are handled") {
        val input = mapOf(
            "ä" to "a-umlaut",
            "a" to "regular-a"
        )

        val canonical = serializer.serialize(input)
        // 'a' < 'ä' in unicode code point order
        canonical shouldBe """{"a":"regular-a","ä":"a-umlaut"}"""
    }

    // ── Special Characters / Escaping ────────────────────────────────────────

    test("special characters are properly escaped") {
        val input = mapOf(
            "quote" to """He said "hello"""",
            "backslash" to "path\\to\\file",
            "newline" to "line1\nline2",
            "tab" to "col1\tcol2"
        )

        val canonical = serializer.serialize(input)
        canonical shouldContain "\\\"hello\\\""
        canonical shouldContain "path\\\\to\\\\file"
        canonical shouldContain "\\n"
        canonical shouldContain "\\t"
    }

    test("control characters are unicode-escaped") {
        val input = mapOf("ctrl" to "\u0001\u0002\u0003")
        val canonical = serializer.serialize(input)
        canonical shouldContain "\\u0001"
        canonical shouldContain "\\u0002"
        canonical shouldContain "\\u0003"
    }

    // ── Null Value Omission ─────────────────────────────────────────────────

    test("null values are omitted from output") {
        val input = mapOf<String, Any?>(
            "present" to "value",
            "absent" to null,
            "alsoPresent" to "another"
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"alsoPresent":"another","present":"value"}"""
        canonical shouldNotContain "absent"
        canonical shouldNotContain "null"
    }

    test("all-null values produce empty object") {
        val input = mapOf<String, Any?>(
            "a" to null,
            "b" to null
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe "{}"
    }

    // ── Array Order Preservation ────────────────────────────────────────────

    test("arrays preserve element order") {
        val input = mapOf<String, Any?>(
            "items" to listOf("c", "a", "b")
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"items":["c","a","b"]}"""
    }

    test("nested arrays preserve order") {
        val input = mapOf<String, Any?>(
            "matrix" to listOf(
                listOf(3, 2, 1),
                listOf(6, 5, 4)
            )
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"matrix":[[3,2,1],[6,5,4]]}"""
    }

    test("empty array") {
        val input = mapOf<String, Any?>(
            "items" to emptyList<Any>()
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"items":[]}"""
    }

    // ── Number Formatting ───────────────────────────────────────────────────

    test("integer values have no decimal point") {
        val input = mapOf<String, Any?>(
            "count" to 42
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"count":42}"""
    }

    test("double values preserve fractional parts") {
        val input = mapOf<String, Any?>(
            "ratio" to 0.75
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"ratio":0.75}"""
    }

    test("integer-valued doubles serialize as integers") {
        val input = mapOf<String, Any?>(
            "amount" to 4500.0
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"amount":4500}"""
    }

    test("zero serializes correctly") {
        val input = mapOf<String, Any?>(
            "zero" to 0
        )
        val canonical = serializer.serialize(input)
        canonical shouldBe """{"zero":0}"""
    }

    test("negative numbers serialize correctly") {
        val input = mapOf<String, Any?>(
            "negative" to -42
        )
        val canonical = serializer.serialize(input)
        canonical shouldBe """{"negative":-42}"""
    }

    // ── Boolean Values ──────────────────────────────────────────────────────

    test("boolean values serialize as true/false") {
        val input = mapOf<String, Any?>(
            "active" to true,
            "deleted" to false
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"active":true,"deleted":false}"""
    }

    // ── Canonicalize Existing JSON ──────────────────────────────────────────

    test("canonicalize sorts unsorted JSON string") {
        val unsorted = """{"z":"3","a":"1","m":"2"}"""
        val canonical = serializer.canonicalize(unsorted)
        canonical shouldBe """{"a":"1","m":"2","z":"3"}"""
    }

    test("canonicalize removes whitespace") {
        val formatted = """{ "b" : "2" , "a" : "1" }"""
        val canonical = serializer.canonicalize(formatted)
        canonical shouldBe """{"a":"1","b":"2"}"""
    }

    test("canonicalize handles nested JSON") {
        val nested = """{"outer":{"z":"last","a":"first"}}"""
        val canonical = serializer.canonicalize(nested)
        canonical shouldBe """{"outer":{"a":"first","z":"last"}}"""
    }

    // ── SHA-256 Hash ────────────────────────────────────────────────────────

    test("sha256Hex produces 64-character hex string") {
        val hash = serializer.sha256Hex("{}")
        hash.length shouldBe 64
    }

    test("sha256Hex is deterministic") {
        val hash1 = serializer.sha256Hex("""{"key":"value"}""")
        val hash2 = serializer.sha256Hex("""{"key":"value"}""")
        hash1 shouldBe hash2
    }

    test("sha256Hex of empty object is known") {
        val hash = serializer.sha256Hex("{}")
        // SHA-256 of "{}"
        hash shouldBe "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
    }

    // ── serializeAndHash ────────────────────────────────────────────────────

    test("serializeAndHash returns canonical JSON and its hash") {
        val input = mapOf("z" to "last", "a" to "first")
        val (canonical, hash) = serializer.serializeAndHash(input)

        canonical shouldBe """{"a":"first","z":"last"}"""
        hash shouldBe serializer.sha256Hex(canonical)
    }
})
