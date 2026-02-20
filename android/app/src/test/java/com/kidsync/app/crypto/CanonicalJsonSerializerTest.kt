package com.kidsync.app.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Tests for CanonicalJsonSerializer using tv07 conformance vectors.
 *
 * Verifies:
 * - Lexicographic key sorting at every nesting level
 * - Compact form (no whitespace)
 * - Null/absent field omission
 * - Integer vs decimal number formatting
 * - SHA-256 hash correctness
 */
class CanonicalJsonSerializerTest : FunSpec({

    val serializer = CanonicalJsonSerializer()
    val json = Json { ignoreUnknownKeys = true }

    // TV07 CS-01: CreateEvent with minimal fields
    test("CS-01: CreateEvent minimal - sorted keys, no optional fields") {
        val input = mapOf(
            "payloadType" to "CreateEvent",
            "entityId" to "11223344-5566-7788-99aa-bbccddeeff00",
            "timestamp" to "2026-03-16T12:00:00.000Z",
            "operationType" to "CREATE",
            "eventId" to "11223344-5566-7788-99aa-bbccddeeff00",
            "childId" to "c1d2e3f4-5678-9abc-def0-123456789012",
            "title" to "Doctor visit",
            "date" to "2026-04-10"
        )

        val canonical = serializer.serialize(input)
        val expectedJson = """{"childId":"c1d2e3f4-5678-9abc-def0-123456789012","date":"2026-04-10","entityId":"11223344-5566-7788-99aa-bbccddeeff00","eventId":"11223344-5566-7788-99aa-bbccddeeff00","operationType":"CREATE","payloadType":"CreateEvent","timestamp":"2026-03-16T12:00:00.000Z","title":"Doctor visit"}"""

        canonical shouldBe expectedJson

        val hash = serializer.sha256Hex(canonical)
        hash shouldBe "6976b87b304f356672fe22cfef97bc106ad58652af03505268282b5f87ca0b0f"
    }

    // TV07 CS-02: CreateEvent with ALL optional fields
    test("CS-02: CreateEvent with all optional fields") {
        val input = mapOf(
            "payloadType" to "CreateEvent",
            "entityId" to "11223344-5566-7788-99aa-bbccddeeff00",
            "timestamp" to "2026-03-16T12:00:00.000Z",
            "operationType" to "CREATE",
            "eventId" to "11223344-5566-7788-99aa-bbccddeeff00",
            "childId" to "c1d2e3f4-5678-9abc-def0-123456789012",
            "title" to "Parent-teacher conference",
            "date" to "2026-04-10",
            "time" to "15:30:00",
            "location" to "Grundschule am Park, Room 201",
            "notes" to "Bring last semester's report card."
        )

        val canonical = serializer.serialize(input)
        val expectedJson = """{"childId":"c1d2e3f4-5678-9abc-def0-123456789012","date":"2026-04-10","entityId":"11223344-5566-7788-99aa-bbccddeeff00","eventId":"11223344-5566-7788-99aa-bbccddeeff00","location":"Grundschule am Park, Room 201","notes":"Bring last semester's report card.","operationType":"CREATE","payloadType":"CreateEvent","time":"15:30:00","timestamp":"2026-03-16T12:00:00.000Z","title":"Parent-teacher conference"}"""

        canonical shouldBe expectedJson

        val hash = serializer.sha256Hex(canonical)
        hash shouldBe "eb057bc007e33ddd29ed4543d816c08776a8fe742521da724cf2f8871173b58f"
    }

    // TV07 CS-03: CreateExpense with numeric precision
    test("CS-03: CreateExpense - integer amountCents, decimal payerResponsibilityRatio") {
        val input = mapOf<String, Any?>(
            "payloadType" to "CreateExpense",
            "entityId" to "aabbccdd-1122-3344-5566-778899aabb00",
            "timestamp" to "2026-03-25T18:45:00.000Z",
            "operationType" to "CREATE",
            "expenseId" to "aabbccdd-1122-3344-5566-778899aabb00",
            "childId" to "c1d2e3f4-5678-9abc-def0-123456789012",
            "paidByUserId" to "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
            "amountCents" to 15099,
            "currencyCode" to "USD",
            "category" to "TRANSPORT",
            "description" to "Airport taxi for custody exchange",
            "incurredAt" to "2026-03-25",
            "payerResponsibilityRatio" to 0.6
        )

        val canonical = serializer.serialize(input)
        val expectedJson = """{"amountCents":15099,"category":"TRANSPORT","childId":"c1d2e3f4-5678-9abc-def0-123456789012","currencyCode":"USD","description":"Airport taxi for custody exchange","entityId":"aabbccdd-1122-3344-5566-778899aabb00","expenseId":"aabbccdd-1122-3344-5566-778899aabb00","incurredAt":"2026-03-25","operationType":"CREATE","paidByUserId":"d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa","payerResponsibilityRatio":0.6,"payloadType":"CreateExpense","timestamp":"2026-03-25T18:45:00.000Z"}"""

        canonical shouldBe expectedJson

        val hash = serializer.sha256Hex(canonical)
        hash shouldBe "4d4f480ccfcf834a9d1c5103780820eb13c2db4c40d3e354c9df3b98609422de"
    }

    // TV07 CS-04: CancelEvent minimal payload
    test("CS-04: CancelEvent minimal - smallest possible payload") {
        val input = mapOf(
            "payloadType" to "CancelEvent",
            "entityId" to "11223344-5566-7788-99aa-bbccddeeff00",
            "timestamp" to "2026-03-17T10:00:00.000Z",
            "operationType" to "DELETE",
            "eventId" to "11223344-5566-7788-99aa-bbccddeeff00"
        )

        val canonical = serializer.serialize(input)
        val expectedJson = """{"entityId":"11223344-5566-7788-99aa-bbccddeeff00","eventId":"11223344-5566-7788-99aa-bbccddeeff00","operationType":"DELETE","payloadType":"CancelEvent","timestamp":"2026-03-17T10:00:00.000Z"}"""

        canonical shouldBe expectedJson

        val hash = serializer.sha256Hex(canonical)
        hash shouldBe "25c0f1558000973561ed7ecdf914769b43ee37c5e1cc60d67f0766b3ddc6f1fc"
    }

    // TV07 CS-05: UpdateExpenseStatus with optional note
    test("CS-05: UpdateExpenseStatus WITH note field") {
        val input = mapOf(
            "payloadType" to "UpdateExpenseStatus",
            "entityId" to "a1234567-b890-cdef-1234-567890abcdef",
            "timestamp" to "2026-03-19T09:00:00.000Z",
            "operationType" to "UPDATE",
            "expenseId" to "a1234567-b890-cdef-1234-567890abcdef",
            "status" to "DISPUTED",
            "responderId" to "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
            "note" to "Amount seems too high for a standard visit. Can you share the invoice?"
        )

        val canonical = serializer.serialize(input)
        val expectedJson = """{"entityId":"a1234567-b890-cdef-1234-567890abcdef","expenseId":"a1234567-b890-cdef-1234-567890abcdef","note":"Amount seems too high for a standard visit. Can you share the invoice?","operationType":"UPDATE","payloadType":"UpdateExpenseStatus","responderId":"e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb","status":"DISPUTED","timestamp":"2026-03-19T09:00:00.000Z"}"""

        canonical shouldBe expectedJson

        val hash = serializer.sha256Hex(canonical)
        hash shouldBe "0d2b1f680c7dd0471aa5ac408e4e7810009c8912a7b49ab16358f17d8dc30638"
    }

    // TV07 CS-06: UpdateExpenseStatus WITHOUT optional note
    test("CS-06: UpdateExpenseStatus WITHOUT note - null omitted") {
        val input = mapOf<String, Any?>(
            "payloadType" to "UpdateExpenseStatus",
            "entityId" to "a1234567-b890-cdef-1234-567890abcdef",
            "timestamp" to "2026-03-19T09:00:00.000Z",
            "operationType" to "UPDATE",
            "expenseId" to "a1234567-b890-cdef-1234-567890abcdef",
            "status" to "ACKNOWLEDGED",
            "responderId" to "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb"
        )

        val canonical = serializer.serialize(input)
        val expectedJson = """{"entityId":"a1234567-b890-cdef-1234-567890abcdef","expenseId":"a1234567-b890-cdef-1234-567890abcdef","operationType":"UPDATE","payloadType":"UpdateExpenseStatus","responderId":"e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb","status":"ACKNOWLEDGED","timestamp":"2026-03-19T09:00:00.000Z"}"""

        canonical shouldBe expectedJson

        val hash = serializer.sha256Hex(canonical)
        hash shouldBe "048319bc43fedd256be41d58d6c251c3e424fb2cee5c7c55a578abfbc9f37a39"
    }

    // TV07 CS-07: CreateExpense with receipt blob fields
    test("CS-07: CreateExpense with receipt blob fields") {
        val input = mapOf<String, Any?>(
            "payloadType" to "CreateExpense",
            "entityId" to "dddddddd-1111-2222-3333-444444444444",
            "timestamp" to "2026-03-18T16:22:00.000Z",
            "operationType" to "CREATE",
            "expenseId" to "dddddddd-1111-2222-3333-444444444444",
            "childId" to "c1d2e3f4-5678-9abc-def0-123456789012",
            "paidByUserId" to "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
            "amountCents" to 4500,
            "currencyCode" to "EUR",
            "category" to "MEDICAL",
            "description" to "Pediatrician visit",
            "incurredAt" to "2026-03-18",
            "payerResponsibilityRatio" to 0.5,
            "receiptBlobId" to "deadbeef-1234-5678-9abc-def012345678",
            "receiptDecryptionKey" to "c2VjcmV0LWJsb2Ita2V5LTMyLWJ5dGVzLWhlcmUtcGFkZGVk"
        )

        val canonical = serializer.serialize(input)
        val expectedJson = """{"amountCents":4500,"category":"MEDICAL","childId":"c1d2e3f4-5678-9abc-def0-123456789012","currencyCode":"EUR","description":"Pediatrician visit","entityId":"dddddddd-1111-2222-3333-444444444444","expenseId":"dddddddd-1111-2222-3333-444444444444","incurredAt":"2026-03-18","operationType":"CREATE","paidByUserId":"d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa","payerResponsibilityRatio":0.5,"payloadType":"CreateExpense","receiptBlobId":"deadbeef-1234-5678-9abc-def012345678","receiptDecryptionKey":"c2VjcmV0LWJsb2Ita2V5LTMyLWJ5dGVzLWhlcmUtcGFkZGVk","timestamp":"2026-03-18T16:22:00.000Z"}"""

        canonical shouldBe expectedJson

        val hash = serializer.sha256Hex(canonical)
        hash shouldBe "4efa7234e685d6f7d389b43d1dd29f99158bcf33bbd33f8dbe1c9092bd60bd5b"
    }

    // Anti-pattern tests
    test("null values are omitted from serialization") {
        val input = mapOf<String, Any?>(
            "entityId" to "test-id",
            "note" to null,
            "operationType" to "UPDATE"
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"entityId":"test-id","operationType":"UPDATE"}"""
    }

    test("keys are sorted lexicographically") {
        val input = mapOf(
            "z-field" to "last",
            "a-field" to "first",
            "m-field" to "middle"
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"a-field":"first","m-field":"middle","z-field":"last"}"""
    }

    test("integer-valued doubles serialize without fractional part") {
        val input = mapOf<String, Any?>(
            "amountCents" to 4500,
            "ratio" to 0.5
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"amountCents":4500,"ratio":0.5}"""
    }

    test("canonicalize sorts unsorted JSON string") {
        val unsorted = """{"c":"3","a":"1","b":"2"}"""
        val canonical = serializer.canonicalize(unsorted)
        canonical shouldBe """{"a":"1","b":"2","c":"3"}"""
    }

    test("serializeAndHash returns both canonical JSON and hash") {
        val input = mapOf("key" to "value")
        val (canonical, hash) = serializer.serializeAndHash(input)

        canonical shouldBe """{"key":"value"}"""
        hash shouldBe serializer.sha256Hex(canonical)
    }

    test("nested objects have sorted keys") {
        val input = mapOf<String, Any?>(
            "outer" to mapOf<String, Any?>(
                "z" to "last",
                "a" to "first"
            )
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"outer":{"a":"first","z":"last"}}"""
    }

    test("arrays preserve element order") {
        val input = mapOf<String, Any?>(
            "items" to listOf("c", "a", "b")
        )

        val canonical = serializer.serialize(input)
        canonical shouldBe """{"items":["c","a","b"]}"""
    }
})
