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
 * Sealed hierarchy representing the different types of Info Bank entries.
 * Each variant carries the fields specific to its category.
 */
sealed class InfoBankEntry {
    abstract val entryId: UUID
    abstract val childId: UUID
    abstract val category: InfoBankCategory
    abstract val createdAt: Instant?
    abstract val updatedAt: Instant?

    data class Medical(
        override val entryId: UUID,
        override val childId: UUID,
        val allergies: String? = null,
        val medicationName: String? = null,
        val medicationDosage: String? = null,
        val medicationSchedule: String? = null,
        val doctorName: String? = null,
        val doctorPhone: String? = null,
        val insuranceInfo: String? = null,
        val bloodType: String? = null,
        val notes: String? = null,
        override val createdAt: Instant? = null,
        override val updatedAt: Instant? = null
    ) : InfoBankEntry() {
        override val category: InfoBankCategory = InfoBankCategory.MEDICAL
    }

    data class School(
        override val entryId: UUID,
        override val childId: UUID,
        val schoolName: String? = null,
        val teacherNames: String? = null,
        val gradeClass: String? = null,
        val schoolPhone: String? = null,
        val scheduleNotes: String? = null,
        val notes: String? = null,
        override val createdAt: Instant? = null,
        override val updatedAt: Instant? = null
    ) : InfoBankEntry() {
        override val category: InfoBankCategory = InfoBankCategory.SCHOOL
    }

    data class EmergencyContact(
        override val entryId: UUID,
        override val childId: UUID,
        val contactName: String,
        val relationship: String? = null,
        val phone: String? = null,
        val email: String? = null,
        val notes: String? = null,
        override val createdAt: Instant? = null,
        override val updatedAt: Instant? = null
    ) : InfoBankEntry() {
        override val category: InfoBankCategory = InfoBankCategory.EMERGENCY_CONTACT
    }

    data class Note(
        override val entryId: UUID,
        override val childId: UUID,
        val title: String,
        val content: String,
        val tag: String? = null,
        override val createdAt: Instant? = null,
        override val updatedAt: Instant? = null
    ) : InfoBankEntry() {
        override val category: InfoBankCategory = InfoBankCategory.NOTE
    }
}
