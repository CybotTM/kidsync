package com.kidsync.app.domain.usecase.custody

import com.kidsync.app.domain.model.DecryptedPayload
import com.kidsync.app.domain.model.OverrideStatus
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * Client-side override state machine.
 *
 * In the zero-knowledge architecture, the server has NO override state table.
 * Each client deterministically replays all ops in global sequence order and
 * derives the current state. This is the ONLY source of override state.
 *
 * Valid transitions:
 *   PROPOSED -> APPROVED (by other parent / any device except proposer)
 *   PROPOSED -> DECLINED (by other parent / any device except proposer)
 *   PROPOSED -> CANCELLED (by proposer device only)
 *   PROPOSED -> EXPIRED (by any device, when clientTimestamp + TTL < now)
 *   PROPOSED -> SUPERSEDED (automatic: new PROPOSED for same date range supersedes previous)
 *
 * Terminal states (no transitions out):
 *   DECLINED, CANCELLED, SUPERSEDED, EXPIRED
 *
 * Convergence guarantee:
 *   Given the same ordered sequence of ops, all clients compute the same override state.
 */
class OverrideStateMachine @Inject constructor() {

    private val states = mutableMapOf<String, OverrideState>()

    companion object {
        private val VALID_TRANSITIONS: Map<OverrideStatus, Set<OverrideStatus>> = mapOf(
            OverrideStatus.PROPOSED to setOf(
                OverrideStatus.APPROVED,
                OverrideStatus.DECLINED,
                OverrideStatus.CANCELLED,
                OverrideStatus.SUPERSEDED,
                OverrideStatus.EXPIRED
            ),
            OverrideStatus.APPROVED to setOf(
                OverrideStatus.SUPERSEDED,
                OverrideStatus.EXPIRED
            ),
            OverrideStatus.DECLINED to emptySet(),
            OverrideStatus.CANCELLED to emptySet(),
            OverrideStatus.SUPERSEDED to emptySet(),
            OverrideStatus.EXPIRED to emptySet()
        )
    }

    /**
     * Apply a decrypted op to the state machine.
     * Only processes CustodyOverride entity types.
     */
    fun apply(op: DecryptedPayload) {
        if (op.entityType != "ScheduleOverride") return

        when (op.operation) {
            "CREATE" -> {
                val proposerId = op.data["proposerDeviceId"]?.jsonPrimitive?.content ?: return
                states[op.entityId] = OverrideState(
                    entityId = op.entityId,
                    status = OverrideStatus.PROPOSED,
                    proposerDeviceId = proposerId,
                    createdAt = op.clientTimestamp
                )
            }
            "UPDATE" -> {
                val current = states[op.entityId] ?: return
                val transitionStr = op.data["status"]?.jsonPrimitive?.content ?: return
                val transitionTo = runCatching { OverrideStatus.valueOf(transitionStr) }.getOrNull() ?: return
                val actorDeviceId = op.data["responderDeviceId"]?.jsonPrimitive?.content

                if (isValidTransition(current.status, transitionTo)) {
                    val authorityResult = validateAuthority(
                        from = current.status,
                        to = transitionTo,
                        actorDeviceId = actorDeviceId,
                        proposerDeviceId = current.proposerDeviceId,
                        isSystem = (transitionTo == OverrideStatus.SUPERSEDED || transitionTo == OverrideStatus.EXPIRED)
                    )
                    if (authorityResult is AuthorityResult.Permitted) {
                        states[op.entityId] = current.copy(status = transitionTo)
                    }
                }
            }
            "DELETE" -> {
                states.remove(op.entityId)
            }
        }
    }

    /**
     * Get the current state of all overrides.
     */
    fun getStates(): Map<String, OverrideState> = states.toMap()

    /**
     * Get the state of a specific override.
     */
    fun getState(entityId: String): OverrideState? = states[entityId]

    /**
     * Reset the state machine (e.g., before replaying from scratch).
     */
    fun reset() {
        states.clear()
    }

    /**
     * Check if a transition from [from] to [to] is valid.
     */
    fun isValidTransition(from: OverrideStatus, to: OverrideStatus): Boolean {
        return VALID_TRANSITIONS[from]?.contains(to) ?: false
    }

    /**
     * Get all valid transitions from a given state.
     */
    fun validTransitionsFrom(state: OverrideStatus): Set<OverrideStatus> {
        return VALID_TRANSITIONS[state] ?: emptySet()
    }

    /**
     * Validate authority for a transition.
     *
     * @param from Current status
     * @param to Target status
     * @param actorDeviceId The device attempting the transition
     * @param proposerDeviceId The device that proposed the override
     * @param isSystem Whether this is a system-initiated transition
     * @return AuthorityResult indicating whether the transition is permitted
     */
    fun validateAuthority(
        from: OverrideStatus,
        to: OverrideStatus,
        actorDeviceId: String?,
        proposerDeviceId: String,
        isSystem: Boolean = false
    ): AuthorityResult {
        // First check if the transition itself is valid
        if (!isValidTransition(from, to)) {
            return AuthorityResult.InvalidTransition(
                "Cannot transition from $from to $to"
            )
        }

        return when (to) {
            OverrideStatus.CANCELLED -> {
                // Only proposer device can cancel
                if (actorDeviceId == proposerDeviceId) {
                    AuthorityResult.Permitted
                } else {
                    AuthorityResult.Forbidden(
                        "Only the proposer can cancel an override"
                    )
                }
            }

            OverrideStatus.APPROVED, OverrideStatus.DECLINED -> {
                // Only a device OTHER than the proposer can approve/decline
                if (actorDeviceId != null && actorDeviceId != proposerDeviceId) {
                    AuthorityResult.Permitted
                } else {
                    AuthorityResult.Forbidden(
                        "Proposer cannot ${to.name.lowercase()} their own override"
                    )
                }
            }

            OverrideStatus.SUPERSEDED, OverrideStatus.EXPIRED -> {
                // System-only transitions (any device can trigger deterministically)
                if (isSystem) {
                    AuthorityResult.Permitted
                } else {
                    AuthorityResult.Forbidden(
                        "${to.name} is a system-only transition"
                    )
                }
            }

            OverrideStatus.PROPOSED -> {
                AuthorityResult.InvalidTransition("Cannot transition to PROPOSED")
            }
        }
    }
}

data class OverrideState(
    val entityId: String,
    val status: OverrideStatus,
    val proposerDeviceId: String,
    val createdAt: String
)

sealed class AuthorityResult {
    data object Permitted : AuthorityResult()
    data class Forbidden(val reason: String) : AuthorityResult()
    data class InvalidTransition(val reason: String) : AuthorityResult()
}
