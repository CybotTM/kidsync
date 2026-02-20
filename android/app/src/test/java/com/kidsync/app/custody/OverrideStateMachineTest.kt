package com.kidsync.app.custody

import com.kidsync.app.domain.model.OverrideStatus
import com.kidsync.app.domain.usecase.custody.AuthorityResult
import com.kidsync.app.domain.usecase.custody.OverrideStateMachine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

/**
 * Tests for OverrideStateMachine using tv05 conformance vectors.
 *
 * Covers:
 * - All valid transitions (VT-01 through VT-05)
 * - All invalid transitions (IT-01 through IT-10)
 * - Authority constraints (AC-01 through AC-05)
 * - Terminal state verification
 */
class OverrideStateMachineTest : FunSpec({

    val stateMachine = OverrideStateMachine()
    val parentA = UUID.fromString("d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa")
    val parentB = UUID.fromString("e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb")

    // ---- Valid Transitions (from tv05) ----

    test("VT-01: PROPOSED -> APPROVED is valid") {
        stateMachine.isValidTransition(OverrideStatus.PROPOSED, OverrideStatus.APPROVED) shouldBe true
    }

    test("VT-02: PROPOSED -> DECLINED is valid") {
        stateMachine.isValidTransition(OverrideStatus.PROPOSED, OverrideStatus.DECLINED) shouldBe true
    }

    test("VT-03: PROPOSED -> CANCELLED is valid") {
        stateMachine.isValidTransition(OverrideStatus.PROPOSED, OverrideStatus.CANCELLED) shouldBe true
    }

    test("VT-04: APPROVED -> SUPERSEDED is valid") {
        stateMachine.isValidTransition(OverrideStatus.APPROVED, OverrideStatus.SUPERSEDED) shouldBe true
    }

    test("VT-05: APPROVED -> EXPIRED is valid") {
        stateMachine.isValidTransition(OverrideStatus.APPROVED, OverrideStatus.EXPIRED) shouldBe true
    }

    // ---- Invalid Transitions (from tv05) ----

    test("IT-01: DECLINED -> APPROVED is invalid (terminal state)") {
        stateMachine.isValidTransition(OverrideStatus.DECLINED, OverrideStatus.APPROVED) shouldBe false
    }

    test("IT-02: CANCELLED -> APPROVED is invalid (terminal state)") {
        stateMachine.isValidTransition(OverrideStatus.CANCELLED, OverrideStatus.APPROVED) shouldBe false
    }

    test("IT-03: CANCELLED -> PROPOSED is invalid (terminal + no PROPOSED transition)") {
        stateMachine.isValidTransition(OverrideStatus.CANCELLED, OverrideStatus.PROPOSED) shouldBe false
    }

    test("IT-04: EXPIRED -> APPROVED is invalid (terminal state)") {
        stateMachine.isValidTransition(OverrideStatus.EXPIRED, OverrideStatus.APPROVED) shouldBe false
    }

    test("IT-05: EXPIRED -> SUPERSEDED is invalid (terminal state)") {
        stateMachine.isValidTransition(OverrideStatus.EXPIRED, OverrideStatus.SUPERSEDED) shouldBe false
    }

    test("IT-06: APPROVED -> DECLINED is invalid") {
        stateMachine.isValidTransition(OverrideStatus.APPROVED, OverrideStatus.DECLINED) shouldBe false
    }

    test("IT-07: PROPOSED -> EXPIRED is invalid") {
        stateMachine.isValidTransition(OverrideStatus.PROPOSED, OverrideStatus.EXPIRED) shouldBe false
    }

    test("IT-08: PROPOSED -> SUPERSEDED is invalid") {
        stateMachine.isValidTransition(OverrideStatus.PROPOSED, OverrideStatus.SUPERSEDED) shouldBe false
    }

    test("IT-09: SUPERSEDED -> APPROVED is invalid (terminal state)") {
        stateMachine.isValidTransition(OverrideStatus.SUPERSEDED, OverrideStatus.APPROVED) shouldBe false
    }

    test("IT-10: DECLINED -> CANCELLED is invalid (terminal state)") {
        stateMachine.isValidTransition(OverrideStatus.DECLINED, OverrideStatus.CANCELLED) shouldBe false
    }

    // ---- Terminal States ----

    test("DECLINED is terminal") {
        OverrideStatus.DECLINED.isTerminal shouldBe true
        stateMachine.validTransitionsFrom(OverrideStatus.DECLINED) shouldBe emptySet()
    }

    test("CANCELLED is terminal") {
        OverrideStatus.CANCELLED.isTerminal shouldBe true
        stateMachine.validTransitionsFrom(OverrideStatus.CANCELLED) shouldBe emptySet()
    }

    test("SUPERSEDED is terminal") {
        OverrideStatus.SUPERSEDED.isTerminal shouldBe true
        stateMachine.validTransitionsFrom(OverrideStatus.SUPERSEDED) shouldBe emptySet()
    }

    test("EXPIRED is terminal") {
        OverrideStatus.EXPIRED.isTerminal shouldBe true
        stateMachine.validTransitionsFrom(OverrideStatus.EXPIRED) shouldBe emptySet()
    }

    test("PROPOSED is non-terminal") {
        OverrideStatus.PROPOSED.isTerminal shouldBe false
    }

    test("APPROVED is non-terminal") {
        OverrideStatus.APPROVED.isTerminal shouldBe false
    }

    // ---- All possible transitions from terminal states are invalid ----

    test("no transitions out of any terminal state") {
        val terminalStates = listOf(
            OverrideStatus.DECLINED,
            OverrideStatus.CANCELLED,
            OverrideStatus.SUPERSEDED,
            OverrideStatus.EXPIRED
        )
        val allStatuses = OverrideStatus.entries

        for (terminal in terminalStates) {
            for (target in allStatuses) {
                stateMachine.isValidTransition(terminal, target) shouldBe false
            }
        }
    }

    // ---- validTransitionsFrom ----

    test("PROPOSED can transition to APPROVED, DECLINED, CANCELLED") {
        stateMachine.validTransitionsFrom(OverrideStatus.PROPOSED) shouldBe setOf(
            OverrideStatus.APPROVED,
            OverrideStatus.DECLINED,
            OverrideStatus.CANCELLED
        )
    }

    test("APPROVED can transition to SUPERSEDED, EXPIRED") {
        stateMachine.validTransitionsFrom(OverrideStatus.APPROVED) shouldBe setOf(
            OverrideStatus.SUPERSEDED,
            OverrideStatus.EXPIRED
        )
    }

    // ---- Authority Constraints (from tv05) ----

    test("AC-01: proposer cannot approve their own override") {
        val result = stateMachine.validateAuthority(
            from = OverrideStatus.PROPOSED,
            to = OverrideStatus.APPROVED,
            actorUserId = parentA,
            proposerId = parentA
        )
        result.shouldBeInstanceOf<AuthorityResult.Forbidden>()
    }

    test("AC-02: proposer cannot decline their own override") {
        val result = stateMachine.validateAuthority(
            from = OverrideStatus.PROPOSED,
            to = OverrideStatus.DECLINED,
            actorUserId = parentA,
            proposerId = parentA
        )
        result.shouldBeInstanceOf<AuthorityResult.Forbidden>()
    }

    test("AC-03: non-proposer cannot cancel the override") {
        val result = stateMachine.validateAuthority(
            from = OverrideStatus.PROPOSED,
            to = OverrideStatus.CANCELLED,
            actorUserId = parentB,
            proposerId = parentA
        )
        result.shouldBeInstanceOf<AuthorityResult.Forbidden>()
    }

    test("AC-04: non-proposer can approve the override") {
        val result = stateMachine.validateAuthority(
            from = OverrideStatus.PROPOSED,
            to = OverrideStatus.APPROVED,
            actorUserId = parentB,
            proposerId = parentA
        )
        result shouldBe AuthorityResult.Permitted
    }

    test("AC-05: proposer can cancel their own override") {
        val result = stateMachine.validateAuthority(
            from = OverrideStatus.PROPOSED,
            to = OverrideStatus.CANCELLED,
            actorUserId = parentA,
            proposerId = parentA
        )
        result shouldBe AuthorityResult.Permitted
    }

    test("SUPERSEDED transition requires system authority") {
        // Non-system user cannot trigger SUPERSEDED
        val result = stateMachine.validateAuthority(
            from = OverrideStatus.APPROVED,
            to = OverrideStatus.SUPERSEDED,
            actorUserId = parentA,
            proposerId = parentA,
            isSystem = false
        )
        result.shouldBeInstanceOf<AuthorityResult.Forbidden>()

        // System can trigger SUPERSEDED
        val systemResult = stateMachine.validateAuthority(
            from = OverrideStatus.APPROVED,
            to = OverrideStatus.SUPERSEDED,
            actorUserId = parentA,
            proposerId = parentA,
            isSystem = true
        )
        systemResult shouldBe AuthorityResult.Permitted
    }

    test("EXPIRED transition requires system authority") {
        val result = stateMachine.validateAuthority(
            from = OverrideStatus.APPROVED,
            to = OverrideStatus.EXPIRED,
            actorUserId = parentB,
            proposerId = parentA,
            isSystem = false
        )
        result.shouldBeInstanceOf<AuthorityResult.Forbidden>()

        val systemResult = stateMachine.validateAuthority(
            from = OverrideStatus.APPROVED,
            to = OverrideStatus.EXPIRED,
            actorUserId = parentB,
            proposerId = parentA,
            isSystem = true
        )
        systemResult shouldBe AuthorityResult.Permitted
    }

    test("invalid transition returns InvalidTransition result") {
        val result = stateMachine.validateAuthority(
            from = OverrideStatus.DECLINED,
            to = OverrideStatus.APPROVED,
            actorUserId = parentB,
            proposerId = parentA
        )
        result.shouldBeInstanceOf<AuthorityResult.InvalidTransition>()
    }

    test("cannot transition to PROPOSED") {
        val result = stateMachine.validateAuthority(
            from = OverrideStatus.PROPOSED,
            to = OverrideStatus.PROPOSED,
            actorUserId = parentA,
            proposerId = parentA
        )
        result.shouldBeInstanceOf<AuthorityResult.InvalidTransition>()
    }
})
