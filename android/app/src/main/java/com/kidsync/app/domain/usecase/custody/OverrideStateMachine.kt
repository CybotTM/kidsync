package com.kidsync.app.domain.usecase.custody

import com.kidsync.app.domain.model.OverrideStatus
import com.kidsync.app.domain.model.OverrideType
import java.util.UUID
import javax.inject.Inject

/**
 * Enforces the override state machine as defined in sync-protocol.md Section 9.2
 * and tv05-override-state-machine.json.
 *
 * Valid transitions:
 *   PROPOSED -> APPROVED (by other parent)
 *   PROPOSED -> DECLINED (by other parent)
 *   PROPOSED -> CANCELLED (by proposer)
 *   APPROVED -> SUPERSEDED (by system when new override takes precedence)
 *   APPROVED -> EXPIRED (by system when end date passes)
 *
 * Terminal states (no transitions out):
 *   DECLINED, CANCELLED, SUPERSEDED, EXPIRED
 *
 * Authority constraints:
 *   - Only the proposer can CANCEL
 *   - Only the OTHER parent (not proposer) can APPROVE or DECLINE
 *   - SUPERSEDED and EXPIRED are system-only transitions
 */
class OverrideStateMachine @Inject constructor() {

    companion object {
        private val VALID_TRANSITIONS: Map<OverrideStatus, Set<OverrideStatus>> = mapOf(
            OverrideStatus.PROPOSED to setOf(
                OverrideStatus.APPROVED,
                OverrideStatus.DECLINED,
                OverrideStatus.CANCELLED
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
     * @param actorUserId The user attempting the transition
     * @param proposerId The original proposer of the override
     * @param isSystem Whether this is a system-initiated transition
     * @return AuthorityResult indicating whether the transition is permitted
     */
    fun validateAuthority(
        from: OverrideStatus,
        to: OverrideStatus,
        actorUserId: UUID,
        proposerId: UUID,
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
                // Only proposer can cancel
                if (actorUserId == proposerId) {
                    AuthorityResult.Permitted
                } else {
                    AuthorityResult.Forbidden(
                        "Only the proposer can cancel an override"
                    )
                }
            }

            OverrideStatus.APPROVED, OverrideStatus.DECLINED -> {
                // Only the OTHER parent (not proposer) can approve/decline
                if (actorUserId != proposerId) {
                    AuthorityResult.Permitted
                } else {
                    AuthorityResult.Forbidden(
                        "Proposer cannot ${to.name.lowercase()} their own override"
                    )
                }
            }

            OverrideStatus.SUPERSEDED, OverrideStatus.EXPIRED -> {
                // System-only transitions
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

sealed class AuthorityResult {
    data object Permitted : AuthorityResult()
    data class Forbidden(val reason: String) : AuthorityResult()
    data class InvalidTransition(val reason: String) : AuthorityResult()
}
