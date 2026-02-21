package com.kidsync.app.crypto

import kotlinx.serialization.json.*
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Canonical JSON serialization as defined in wire-format.md Section 3.7.
 *
 * Rules:
 * 1. Object keys MUST be sorted lexicographically (Unicode code point order) at every nesting level
 * 2. No whitespace between tokens (compact form: separators are ',' and ':')
 * 3. Null/absent optional fields MUST be omitted (not serialized as null)
 * 4. The resulting UTF-8 byte sequence is the canonical form
 * 5. UUIDs are lowercase hex with hyphens
 * 6. Timestamps use ISO 8601 with exactly 3 fractional digits and Z suffix
 * 7. Integer values have no fractional parts
 * 8. payerResponsibilityRatio is a JSON number with at most 4 decimal places
 */
class CanonicalJsonSerializer @Inject constructor() {

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * Serialize a map to canonical JSON string.
     * Keys are sorted lexicographically at every nesting level.
     * Null values are omitted.
     * Output is compact (no whitespace).
     */
    fun serialize(data: Map<String, Any?>): String {
        val jsonElement = mapToJsonElement(data)
        return serializeElement(jsonElement)
    }

    /**
     * Serialize a JsonElement to canonical JSON string.
     */
    fun serializeElement(element: JsonElement): String {
        return when (element) {
            is JsonObject -> serializeObject(element)
            is JsonArray -> serializeArray(element)
            is JsonPrimitive -> serializePrimitive(element)
            JsonNull -> "null" // Should not appear in canonical form
        }
    }

    /**
     * Serialize a JSON string and compute its SHA-256 hash.
     * Returns the hex-encoded hash.
     */
    fun serializeAndHash(data: Map<String, Any?>): Pair<String, String> {
        val canonical = serialize(data)
        val hash = sha256Hex(canonical)
        return Pair(canonical, hash)
    }

    /**
     * Compute SHA-256 of a canonical JSON string, returning hex.
     */
    fun sha256Hex(canonicalJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(canonicalJson.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Parse a JSON string to a JsonElement.
     */
    fun parse(jsonString: String): JsonElement {
        return json.parseToJsonElement(jsonString)
    }

    /**
     * Canonicalize an existing JSON string: parse, sort keys, compact, omit nulls.
     */
    fun canonicalize(jsonString: String): String {
        val element = json.parseToJsonElement(jsonString)
        return serializeElement(sortKeys(element))
    }

    private fun serializeObject(obj: JsonObject): String {
        // Sort keys lexicographically (Unicode code point order)
        val sortedEntries = obj.entries.sortedBy { it.key }

        val sb = StringBuilder()
        sb.append('{')

        var first = true
        for ((key, value) in sortedEntries) {
            // Skip null values
            if (value is JsonNull) continue

            if (!first) sb.append(',')
            first = false

            sb.append('"')
            sb.append(escapeJsonString(key))
            sb.append('"')
            sb.append(':')
            sb.append(serializeElement(value))
        }

        sb.append('}')
        return sb.toString()
    }

    private fun serializeArray(array: JsonArray): String {
        val sb = StringBuilder()
        sb.append('[')

        var first = true
        for (element in array) {
            if (!first) sb.append(',')
            first = false
            sb.append(serializeElement(element))
        }

        sb.append(']')
        return sb.toString()
    }

    private fun serializePrimitive(primitive: JsonPrimitive): String {
        if (primitive.isString) {
            return "\"${escapeJsonString(primitive.content)}\""
        }

        // For numbers, use the content directly (preserves integer vs decimal)
        return primitive.content
    }

    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun sortKeys(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> {
                val sorted = buildJsonObject {
                    for ((key, value) in element.entries.sortedBy { it.key }) {
                        if (value !is JsonNull) {
                            put(key, sortKeys(value))
                        }
                    }
                }
                sorted
            }
            is JsonArray -> {
                JsonArray(element.map { sortKeys(it) })
            }
            else -> element
        }
    }

    /**
     * Convert a Map<String, Any?> to a JsonElement, handling nested structures.
     */
    internal fun mapToJsonElement(data: Map<String, Any?>): JsonElement {
        return buildJsonObject {
            for ((key, value) in data) {
                if (value == null) continue // Omit null values
                put(key, anyToJsonElement(value))
            }
        }
    }

    private fun anyToJsonElement(value: Any): JsonElement {
        return when (value) {
            is String -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Double -> {
                // Handle integer-valued doubles (e.g., 4500.0 -> 4500)
                if (value == value.toLong().toDouble() && value % 1.0 == 0.0) {
                    JsonPrimitive(value.toLong())
                } else {
                    JsonPrimitive(value)
                }
            }
            is Float -> {
                val d = value.toDouble()
                if (d == d.toLong().toDouble() && d % 1.0 == 0.0) {
                    JsonPrimitive(d.toLong())
                } else {
                    JsonPrimitive(d)
                }
            }
            is Boolean -> JsonPrimitive(value)
            is List<*> -> JsonArray(value.filterNotNull().map { anyToJsonElement(it) })
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                mapToJsonElement(value as Map<String, Any?>)
            }
            // SEC2-A-18: Fail explicitly for unknown types instead of falling through to toString().
            // toString() could produce non-deterministic output (e.g., object identity hashes),
            // which breaks the canonical serialization contract.
            else -> throw IllegalArgumentException(
                "Cannot serialize value of type ${value::class.qualifiedName} to canonical JSON. " +
                    "Supported types: String, Int, Long, Double, Float, Boolean, List, Map."
            )
        }
    }
}
