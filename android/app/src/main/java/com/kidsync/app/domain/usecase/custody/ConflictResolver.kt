package com.kidsync.app.domain.usecase.custody

import com.kidsync.app.data.local.entity.CustodyScheduleEntity
import com.kidsync.app.domain.model.OverrideStatus
import java.time.Instant
import javax.inject.Inject

/**
 * Resolves conflicts between competing operations as defined in sync-protocol.md Section 9.
 *
 * CustodySchedule conflicts (Section 9.1):
 *   1. Latest effectiveFrom wins
 *   2. Tie-break: later clientTimestamp wins
 *   3. Tie-break: lexicographically greater deviceId wins
 *
 * ExpenseStatus conflicts:
 *   Last-write-wins by clientTimestamp
 *
 * Override transitions:
 *   State machine validation via OverrideStateMachine
 */
class ConflictResolver @Inject constructor(
    private val overrideStateMachine: OverrideStateMachine
) {
    /**
     * Resolve conflict between two CustodySchedule entities with the same effectiveFrom.
     * Returns the winning schedule entity.
     */
    fun resolveCustodyScheduleConflict(
        existing: CustodyScheduleEntity,
        incoming: CustodyScheduleEntity,
        incomingClientTimestamp: Instant
    ): CustodyScheduleEntity {
        // 1. Compare effectiveFrom
        val existingEffective = Instant.parse(existing.effectiveFrom)
        val incomingEffective = Instant.parse(incoming.effectiveFrom)

        if (incomingEffective > existingEffective) return incoming
        if (existingEffective > incomingEffective) return existing

        // 2. Tie-break by clientTimestamp (later wins)
        val existingTimestamp = Instant.parse(existing.clientTimestamp)
        if (incomingClientTimestamp > existingTimestamp) return incoming
        if (existingTimestamp > incomingClientTimestamp) return existing

        // 3. Tie-break by scheduleId (lexicographically greater wins)
        return if (incoming.scheduleId.toString() > existing.scheduleId.toString()) {
            incoming
        } else {
            existing
        }
    }

    /**
     * Check if an override state transition is valid.
     */
    fun isValidOverrideTransition(from: OverrideStatus, to: OverrideStatus): Boolean {
        return overrideStateMachine.isValidTransition(from, to)
    }
}
