package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "info_bank_entries",
    indices = [Index("childId"), Index("category")]
)
data class InfoBankEntryEntity(
    @PrimaryKey
    val entryId: UUID,
    val childId: UUID,
    val category: String,

    // Medical fields
    val allergies: String? = null,
    val medicationName: String? = null,
    val medicationDosage: String? = null,
    val medicationSchedule: String? = null,
    val doctorName: String? = null,
    val doctorPhone: String? = null,
    val insuranceInfo: String? = null,
    val bloodType: String? = null,

    // School fields
    val schoolName: String? = null,
    val teacherNames: String? = null,
    val gradeClass: String? = null,
    val schoolPhone: String? = null,
    val scheduleNotes: String? = null,

    // Emergency contact fields
    val contactName: String? = null,
    val relationship: String? = null,
    val phone: String? = null,
    val email: String? = null,

    // Note fields
    val title: String? = null,
    val content: String? = null,
    val tag: String? = null,

    // Common fields
    val notes: String? = null,
    val clientTimestamp: String? = null,
    val updatedTimestamp: String? = null,
    val deleted: Boolean = false
)
