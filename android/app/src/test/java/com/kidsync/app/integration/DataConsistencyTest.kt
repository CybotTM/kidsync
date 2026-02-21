package com.kidsync.app.integration

import com.kidsync.app.crypto.CanonicalJsonSerializer
import com.kidsync.app.crypto.TinkCryptoManager
import com.kidsync.app.domain.model.DecryptedPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.*

/**
 * Integration tests for data consistency:
 * - Canonical JSON -> encrypt -> decrypt -> parse -> verify all fields preserved
 * - Multi-entity roundtrip (CalendarEvent, Expense, CustodySchedule, InfoBankEntry)
 * - Unicode data preservation through encrypt/decrypt
 * - Large payload roundtrip
 * - DecryptedPayload field access after roundtrip
 * - AAD mismatch prevention
 */
class DataConsistencyTest : FunSpec({

    val cryptoManager = TinkCryptoManager()
    val canonicalSerializer = CanonicalJsonSerializer()

    val bucketId = "bucket-consistency"
    val deviceId = "device-consistency-001"

    fun makeAad(b: String = bucketId, d: String = deviceId) = "$b|$d"

    // ── CalendarEvent Roundtrip ───────────────────────────────────────────────

    test("CalendarEvent data preserved through encrypt-decrypt cycle") {
        val dek = cryptoManager.generateDek()
        val aad = makeAad()

        val eventData = mapOf(
            "data" to mapOf(
                "childId" to "child-001",
                "title" to "Parent-Teacher Conference",
                "date" to "2026-03-15",
                "time" to "14:00",
                "location" to "School Room 201",
                "notes" to "Bring report card"
            ),
            "deviceSequence" to 1,
            "entityId" to "evt-001",
            "entityType" to "CalendarEvent",
            "operation" to "CREATE",
            "protocolVersion" to 2
        )

        val canonical = canonicalSerializer.serialize(eventData)
        val encrypted = cryptoManager.encryptPayload(canonical, dek, aad)
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)

        // Parse and verify every field
        val parsed = Json.parseToJsonElement(decrypted).jsonObject
        parsed["entityType"]?.jsonPrimitive?.content shouldBe "CalendarEvent"
        parsed["entityId"]?.jsonPrimitive?.content shouldBe "evt-001"
        parsed["operation"]?.jsonPrimitive?.content shouldBe "CREATE"
        parsed["protocolVersion"]?.jsonPrimitive?.int shouldBe 2

        val data = parsed["data"]?.jsonObject
        data shouldNotBe null
        data!!["childId"]?.jsonPrimitive?.content shouldBe "child-001"
        data["title"]?.jsonPrimitive?.content shouldBe "Parent-Teacher Conference"
        data["location"]?.jsonPrimitive?.content shouldBe "School Room 201"
        data["notes"]?.jsonPrimitive?.content shouldBe "Bring report card"
    }

    // ── Expense Roundtrip ─────────────────────────────────────────────────────

    test("Expense data with amounts preserved through roundtrip") {
        val dek = cryptoManager.generateDek()
        val aad = makeAad()

        val expenseData = mapOf(
            "data" to mapOf(
                "childId" to "child-002",
                "paidByDeviceId" to deviceId,
                "amountCents" to 15000,
                "currencyCode" to "USD",
                "category" to "MEDICAL",
                "description" to "Pediatric checkup",
                "incurredAt" to "2026-03-10",
                "payerResponsibilityRatio" to 0.5
            ),
            "deviceSequence" to 2,
            "entityId" to "exp-001",
            "entityType" to "Expense",
            "operation" to "CREATE",
            "protocolVersion" to 2
        )

        val canonical = canonicalSerializer.serialize(expenseData)
        val encrypted = cryptoManager.encryptPayload(canonical, dek, aad)
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)

        val parsed = Json.parseToJsonElement(decrypted).jsonObject
        val data = parsed["data"]?.jsonObject!!
        data["amountCents"]?.jsonPrimitive?.int shouldBe 15000
        data["currencyCode"]?.jsonPrimitive?.content shouldBe "USD"
        data["category"]?.jsonPrimitive?.content shouldBe "MEDICAL"
        data["payerResponsibilityRatio"]?.jsonPrimitive?.double shouldBe 0.5
    }

    // ── CustodySchedule Roundtrip ─────────────────────────────────────────────

    test("CustodySchedule with pattern array preserved") {
        val dek = cryptoManager.generateDek()
        val aad = makeAad()

        val scheduleData = mapOf(
            "data" to mapOf(
                "childId" to "child-003",
                "anchorDate" to "2026-01-05",
                "cycleLengthDays" to 14,
                "pattern" to listOf("parent-a", "parent-a", "parent-b", "parent-b",
                    "parent-a", "parent-a", "parent-a",
                    "parent-b", "parent-b", "parent-a", "parent-a",
                    "parent-b", "parent-b", "parent-b"),
                "effectiveFrom" to "2026-01-01T00:00:00Z",
                "timeZone" to "America/New_York"
            ),
            "deviceSequence" to 1,
            "entityId" to "sched-001",
            "entityType" to "CustodySchedule",
            "operation" to "CREATE",
            "protocolVersion" to 2
        )

        val canonical = canonicalSerializer.serialize(scheduleData)
        val encrypted = cryptoManager.encryptPayload(canonical, dek, aad)
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)

        val parsed = Json.parseToJsonElement(decrypted).jsonObject
        val data = parsed["data"]?.jsonObject!!
        data["cycleLengthDays"]?.jsonPrimitive?.int shouldBe 14

        val pattern = data["pattern"]?.jsonArray
        pattern shouldNotBe null
        pattern!!.size shouldBe 14
        pattern[0].jsonPrimitive.content shouldBe "parent-a"
        pattern[2].jsonPrimitive.content shouldBe "parent-b"
    }

    // ── InfoBankEntry Roundtrip ────────────────────────────────────────────────

    test("InfoBankEntry with rich text preserved") {
        val dek = cryptoManager.generateDek()
        val aad = makeAad()

        val infoData = mapOf(
            "data" to mapOf(
                "childId" to "child-001",
                "category" to "medical",
                "label" to "Allergies",
                "value" to "Peanuts (anaphylactic), Shellfish (mild)",
                "notes" to "EpiPen in backpack. Last reaction: Feb 2025."
            ),
            "deviceSequence" to 5,
            "entityId" to "info-001",
            "entityType" to "InfoBankEntry",
            "operation" to "CREATE",
            "protocolVersion" to 2
        )

        val canonical = canonicalSerializer.serialize(infoData)
        val encrypted = cryptoManager.encryptPayload(canonical, dek, aad)
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)

        val parsed = Json.parseToJsonElement(decrypted).jsonObject
        val data = parsed["data"]?.jsonObject!!
        data["label"]?.jsonPrimitive?.content shouldBe "Allergies"
        data["value"]?.jsonPrimitive?.content shouldBe "Peanuts (anaphylactic), Shellfish (mild)"
    }

    // ── Unicode Preservation ──────────────────────────────────────────────────

    test("unicode characters preserved through encrypt-decrypt") {
        val dek = cryptoManager.generateDek()
        val aad = makeAad()

        val unicodeData = mapOf(
            "data" to mapOf(
                "title" to "Arztbesuch fur Kinder",
                "notes" to "Japanese: \u65E5\u672C\u8A9E, Chinese: \u4E2D\u6587, Korean: \uD55C\uAD6D\uC5B4",
                "emoji" to "\uD83D\uDC76\uD83C\uDFE5\uD83D\uDE80"
            ),
            "deviceSequence" to 1,
            "entityId" to "unicode-001",
            "entityType" to "CalendarEvent",
            "operation" to "CREATE",
            "protocolVersion" to 2
        )

        val canonical = canonicalSerializer.serialize(unicodeData)
        val encrypted = cryptoManager.encryptPayload(canonical, dek, aad)
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)

        val parsed = Json.parseToJsonElement(decrypted).jsonObject
        val data = parsed["data"]?.jsonObject!!
        data["notes"]?.jsonPrimitive?.content shouldBe "Japanese: \u65E5\u672C\u8A9E, Chinese: \u4E2D\u6587, Korean: \uD55C\uAD6D\uC5B4"
        data["emoji"]?.jsonPrimitive?.content shouldBe "\uD83D\uDC76\uD83C\uDFE5\uD83D\uDE80"
    }

    // ── Large Payload ─────────────────────────────────────────────────────────

    test("large payload (10KB+) roundtrip preserves data") {
        val dek = cryptoManager.generateDek()
        val aad = makeAad()

        val largeNotes = "A".repeat(10_000) // 10KB of data
        val data = mapOf(
            "data" to mapOf(
                "notes" to largeNotes,
                "childId" to "child-large"
            ),
            "deviceSequence" to 1,
            "entityId" to "large-001",
            "entityType" to "InfoBankEntry",
            "operation" to "CREATE",
            "protocolVersion" to 2
        )

        val canonical = canonicalSerializer.serialize(data)
        val encrypted = cryptoManager.encryptPayload(canonical, dek, aad)
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)

        val parsed = Json.parseToJsonElement(decrypted).jsonObject
        val innerData = parsed["data"]?.jsonObject!!
        innerData["notes"]?.jsonPrimitive?.content?.length shouldBe 10_000
    }

    // ── DecryptedPayload Deserialization ───────────────────────────────────────

    test("DecryptedPayload can be deserialized from decrypted canonical JSON") {
        val dek = cryptoManager.generateDek()
        val aad = makeAad()

        val payload = mapOf(
            "clientTimestamp" to "2026-03-15T10:00:00Z",
            "data" to mapOf(
                "childId" to "child-001",
                "title" to "Swimming lesson"
            ),
            "deviceSequence" to 7,
            "entityId" to "evt-parse",
            "entityType" to "CalendarEvent",
            "operation" to "UPDATE",
            "protocolVersion" to 2
        )

        val canonical = canonicalSerializer.serialize(payload)
        val encrypted = cryptoManager.encryptPayload(canonical, dek, aad)
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)

        val parsed = Json.decodeFromString<DecryptedPayload>(decrypted)
        parsed.deviceSequence shouldBe 7
        parsed.entityType shouldBe "CalendarEvent"
        parsed.entityId shouldBe "evt-parse"
        parsed.operation shouldBe "UPDATE"
        parsed.protocolVersion shouldBe 2
        parsed.clientTimestamp shouldBe "2026-03-15T10:00:00Z"
        parsed.data["childId"]?.jsonPrimitive?.content shouldBe "child-001"
    }

    // ── AAD Mismatch ──────────────────────────────────────────────────────────

    test("decryption with wrong AAD fails even with correct key") {
        val dek = cryptoManager.generateDek()
        val correctAad = makeAad()
        val wrongAad = makeAad(b = "wrong-bucket")

        val canonical = canonicalSerializer.serialize(mapOf(
            "data" to mapOf("test" to "value"),
            "deviceSequence" to 1,
            "entityId" to "aad-test",
            "entityType" to "CalendarEvent",
            "operation" to "CREATE",
            "protocolVersion" to 2
        ))

        val encrypted = cryptoManager.encryptPayload(canonical, dek, correctAad)

        val result = runCatching {
            cryptoManager.decryptPayload(encrypted, dek, wrongAad)
        }
        result.isFailure shouldBe true
    }

    // ── Multiple Entities Same DEK ────────────────────────────────────────────

    test("multiple entity types encrypted with same DEK all decrypt correctly") {
        val dek = cryptoManager.generateDek()
        val aad = makeAad()

        val entities = listOf(
            mapOf("data" to mapOf("type" to "evt"), "deviceSequence" to 1, "entityId" to "e1",
                "entityType" to "CalendarEvent", "operation" to "CREATE", "protocolVersion" to 2),
            mapOf("data" to mapOf("type" to "exp"), "deviceSequence" to 2, "entityId" to "e2",
                "entityType" to "Expense", "operation" to "CREATE", "protocolVersion" to 2),
            mapOf("data" to mapOf("type" to "info"), "deviceSequence" to 3, "entityId" to "e3",
                "entityType" to "InfoBankEntry", "operation" to "CREATE", "protocolVersion" to 2)
        )

        for (entity in entities) {
            val canonical = canonicalSerializer.serialize(entity)
            val encrypted = cryptoManager.encryptPayload(canonical, dek, aad)
            val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)

            val parsed = Json.parseToJsonElement(decrypted).jsonObject
            val expectedType = entity["entityType"] as String
            parsed["entityType"]?.jsonPrimitive?.content shouldBe expectedType

            val innerData = parsed["data"]?.jsonObject!!
            innerData["type"]?.jsonPrimitive?.content shouldBe entity["data"].let {
                @Suppress("UNCHECKED_CAST")
                (it as Map<String, String>)["type"]
            }
        }
    }

    // ── Canonical JSON Determinism Through Encryption ──────────────────────────

    test("same data encrypted twice produces same plaintext after decryption") {
        val dek = cryptoManager.generateDek()
        val aad = makeAad()

        val data = mapOf(
            "data" to mapOf("z" to "last", "a" to "first"),
            "deviceSequence" to 1,
            "entityId" to "deterministic",
            "entityType" to "CalendarEvent",
            "operation" to "CREATE",
            "protocolVersion" to 2
        )

        val canonical1 = canonicalSerializer.serialize(data)
        val enc1 = cryptoManager.encryptPayload(canonical1, dek, aad)

        val canonical2 = canonicalSerializer.serialize(data)
        val enc2 = cryptoManager.encryptPayload(canonical2, dek, aad)

        val dec1 = cryptoManager.decryptPayload(enc1, dek, aad)
        val dec2 = cryptoManager.decryptPayload(enc2, dek, aad)

        // Plaintext should be identical (canonical JSON is deterministic)
        dec1 shouldBe dec2

        // But ciphertext should differ (different nonces)
        enc1 shouldNotBe enc2
    }
})
