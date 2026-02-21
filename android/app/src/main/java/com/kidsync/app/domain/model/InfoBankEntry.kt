package com.kidsync.app.domain.model

import java.time.Instant
import java.util.UUID

// ─── Info Bank Category ─────────────────────────────────────────────────────

enum class InfoBankCategory {
    MEDICAL,
    SCHOOL,
    EMERGENCY_CONTACT,
    NOTE
}

// ─── Info Bank Entry ────────────────────────────────────────────────────────

/**
 * Generic Info Bank entry. All category-specific data is stored as a JSON
 * string in the [content] field. The UI parses this JSON to display
 * category-specific fields (medical, school, emergency contact, note).
 */
data class InfoBankEntry(
    val entryId: UUID,
    val childId: UUID,
    val category: InfoBankCategory,
    val title: String? = null,
    val content: String? = null,
    val notes: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)
