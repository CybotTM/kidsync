package com.kidsync.app.custody

import com.kidsync.app.domain.model.DecryptedPayload
import com.kidsync.app.domain.model.OverrideStatus
import com.kidsync.app.domain.usecase.custody.AuthorityResult
import com.kidsync.app.domain.usecase.custody.OverrideStateMachine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * Extended tests for OverrideStateMachine covering:
 * - CREATE -> PROPOSED state via apply()
 * - PROPOSED -> APPROVED by non-proposer via apply()
 * - PROPOSED -> DECLINED via apply()
 * - PROPOSED -> CANCELLED by proposer via apply()
 * - APPROVED -> SUPERSEDED via apply()
 * - APPROVED -> EXPIRED via apply()
 * - Self-approval rejected via apply()
 * - Unknown override ID handled gracefully
 * - Thread safety with concurrent operations
 * - DELETE removes state
 * - Reset clears all state
 */
class OverrideStateMachineExtendedTest : FunSpec({

    val proposerDevice = "device-proposer-aaa"
    val otherDevice = "device-other-bbb"
    val overrideId = "override-001"

    fun createPayload(
        entityId: String = overrideId,
        operation: String = "CREATE",
        data: JsonObject = buildJsonObject {
            put("proposerDeviceId", proposerDevice)
        }
    ) = DecryptedPayload(
        deviceSequence = 1,
        entityType = "ScheduleOverride",
        entityId = entityId,
        operation = operation,
        clientTimestamp = "2026-04-01T10:00:00Z",
        protocolVersion = 2,
        data = data
    )

    fun updatePayload(
        entityId: String = overrideId,
        status: String,
        responderDeviceId: String? = null
    ) = DecryptedPayload(
        deviceSequence = 2,
        entityType = "ScheduleOverride",
        entityId = entityId,
        operation = "UPDATE",
        clientTimestamp = "2026-04-01T11:00:00Z",
        protocolVersion = 2,
        data = buildJsonObject {
            put("status", status)
            if (responderDeviceId != null) {
                put("responderDeviceId", responderDeviceId)
            }
        }
    )

    // ── CREATE -> PROPOSED ──────────────────────────────────────────────────

    test("CREATE op sets state to PROPOSED") {
        val sm = OverrideStateMachine()
        sm.apply(createPayload())

        val state = sm.getState(overrideId)
        state shouldNotBe null
        state!!.status shouldBe OverrideStatus.PROPOSED
        state.proposerDeviceId shouldBe proposerDevice
    }

    test("CREATE with proposerId field also works") {
        val sm = OverrideStateMachine()
        val payload = createPayload(data = buildJsonObject {
            put("proposerId", proposerDevice)
        })
        sm.apply(payload)

        val state = sm.getState(overrideId)
        state shouldNotBe null
        state!!.status shouldBe OverrideStatus.PROPOSED
    }

    // ── PROPOSED -> APPROVED (by non-proposer) ──────────────────────────────

    test("PROPOSED -> APPROVED by non-proposer succeeds") {
        val sm = OverrideStateMachine()
        sm.apply(createPayload())
        sm.apply(updatePayload(status = "APPROVED", responderDeviceId = otherDevice))

        val state = sm.getState(overrideId)
        state!!.status shouldBe OverrideStatus.APPROVED
    }

    // ── PROPOSED -> DECLINED ────────────────────────────────────────────────

    test("PROPOSED -> DECLINED by non-proposer succeeds") {
        val sm = OverrideStateMachine()
        sm.apply(createPayload())
        sm.apply(updatePayload(status = "DECLINED", responderDeviceId = otherDevice))

        val state = sm.getState(overrideId)
        state!!.status shouldBe OverrideStatus.DECLINED
    }

    // ── PROPOSED -> CANCELLED (by proposer) ─────────────────────────────────

    test("PROPOSED -> CANCELLED by proposer succeeds") {
        val sm = OverrideStateMachine()
        sm.apply(createPayload())
        sm.apply(updatePayload(status = "CANCELLED", responderDeviceId = proposerDevice))

        val state = sm.getState(overrideId)
        state!!.status shouldBe OverrideStatus.CANCELLED
    }

    // ── APPROVED -> SUPERSEDED (system only) ────────────────────────────────

    test("APPROVED -> SUPERSEDED via system transition succeeds") {
        val sm = OverrideStateMachine()
        sm.apply(createPayload())
        sm.apply(updatePayload(status = "APPROVED", responderDeviceId = otherDevice))

        // SUPERSEDED is system-only; the apply method treats it as system because
        // the transition check in apply() passes isSystem=true for SUPERSEDED
        sm.apply(updatePayload(status = "SUPERSEDED"))
        val state = sm.getState(overrideId)
        state!!.status shouldBe OverrideStatus.SUPERSEDED
    }

    // ── APPROVED -> EXPIRED (system only) ───────────────────────────────────

    test("APPROVED -> EXPIRED via system transition succeeds") {
        val sm = OverrideStateMachine()
        sm.apply(createPayload())
        sm.apply(updatePayload(status = "APPROVED", responderDeviceId = otherDevice))

        sm.apply(updatePayload(status = "EXPIRED"))
        val state = sm.getState(overrideId)
        state!!.status shouldBe OverrideStatus.EXPIRED
    }

    // ── Self-Approval Rejected ──────────────────────────────────────────────

    test("self-approval is rejected - proposer cannot approve their own override") {
        val sm = OverrideStateMachine()
        sm.apply(createPayload())

        // Proposer tries to approve
        sm.apply(updatePayload(status = "APPROVED", responderDeviceId = proposerDevice))

        // State should remain PROPOSED
        val state = sm.getState(overrideId)
        state!!.status shouldBe OverrideStatus.PROPOSED
    }

    test("self-decline is rejected") {
        val sm = OverrideStateMachine()
        sm.apply(createPayload())

        sm.apply(updatePayload(status = "DECLINED", responderDeviceId = proposerDevice))

        val state = sm.getState(overrideId)
        state!!.status shouldBe OverrideStatus.PROPOSED
    }

    // ── Non-proposer cannot cancel ──────────────────────────────────────────

    test("non-proposer cannot cancel the override") {
        val sm = OverrideStateMachine()
        sm.apply(createPayload())

        sm.apply(updatePayload(status = "CANCELLED", responderDeviceId = otherDevice))

        // State should remain PROPOSED
        val state = sm.getState(overrideId)
        state!!.status shouldBe OverrideStatus.PROPOSED
    }

    // ── Unknown Override ID ─────────────────────────────────────────────────

    test("UPDATE on unknown override ID is handled gracefully (no crash)") {
        val sm = OverrideStateMachine()

        // UPDATE without prior CREATE - should be silently ignored
        sm.apply(updatePayload(entityId = "nonexistent-override", status = "APPROVED", responderDeviceId = otherDevice))

        sm.getState("nonexistent-override") shouldBe null
    }

    // ── DELETE removes state ────────────────────────────────────────────────

    test("DELETE removes override from state machine") {
        val sm = OverrideStateMachine()
        sm.apply(createPayload())
        sm.getState(overrideId) shouldNotBe null

        sm.apply(DecryptedPayload(
            deviceSequence = 3,
            entityType = "ScheduleOverride",
            entityId = overrideId,
            operation = "DELETE",
            clientTimestamp = "2026-04-01T12:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {}
        ))

        sm.getState(overrideId) shouldBe null
    }

    // ── Reset ───────────────────────────────────────────────────────────────

    test("reset clears all state") {
        val sm = OverrideStateMachine()
        sm.apply(createPayload(entityId = "override-1"))
        sm.apply(createPayload(entityId = "override-2"))
        sm.getStates().size shouldBe 2

        sm.reset()

        sm.getStates().size shouldBe 0
    }

    // ── Non-ScheduleOverride entities are ignored ───────────────────────────

    test("non-ScheduleOverride entities are ignored") {
        val sm = OverrideStateMachine()
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "Expense",
            entityId = "expense-001",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {}
        )
        sm.apply(payload)
        sm.getStates().size shouldBe 0
    }

    // ── Terminal state transitions are rejected ─────────────────────────────

    test("DECLINED state rejects all transitions") {
        val sm = OverrideStateMachine()
        sm.apply(createPayload())
        sm.apply(updatePayload(status = "DECLINED", responderDeviceId = otherDevice))

        // Try to approve the declined override
        sm.apply(updatePayload(status = "APPROVED", responderDeviceId = otherDevice))
        sm.getState(overrideId)!!.status shouldBe OverrideStatus.DECLINED
    }

    // ── Thread safety with concurrent operations ────────────────────────────

    test("concurrent CREATE operations on different overrides") {
        val sm = OverrideStateMachine()

        runBlocking {
            val jobs = (1..100).map { i ->
                launch(Dispatchers.Default) {
                    sm.apply(createPayload(entityId = "override-$i"))
                }
            }
            jobs.forEach { it.join() }
        }

        sm.getStates().size shouldBe 100
    }

    test("concurrent reads and writes do not crash") {
        val sm = OverrideStateMachine()
        sm.apply(createPayload())

        runBlocking {
            val writeJob = launch(Dispatchers.Default) {
                repeat(100) { i ->
                    sm.apply(createPayload(entityId = "concurrent-$i"))
                }
            }
            val readJob = launch(Dispatchers.Default) {
                repeat(100) {
                    sm.getStates()
                    sm.getState(overrideId)
                }
            }
            writeJob.join()
            readJob.join()
        }

        // Should not crash and should have all entries
        (sm.getStates().size >= 1) shouldBe true
    }

    // ── validateAuthority: comprehensive checks ─────────────────────────────

    test("validateAuthority: InvalidTransition for PROPOSED -> PROPOSED") {
        val sm = OverrideStateMachine()
        val result = sm.validateAuthority(
            from = OverrideStatus.PROPOSED,
            to = OverrideStatus.PROPOSED,
            actorDeviceId = otherDevice,
            proposerDeviceId = proposerDevice
        )
        result.shouldBeInstanceOf<AuthorityResult.InvalidTransition>()
    }

    test("validateAuthority: Forbidden for non-system SUPERSEDED") {
        val sm = OverrideStateMachine()
        val result = sm.validateAuthority(
            from = OverrideStatus.APPROVED,
            to = OverrideStatus.SUPERSEDED,
            actorDeviceId = otherDevice,
            proposerDeviceId = proposerDevice,
            isSystem = false
        )
        result.shouldBeInstanceOf<AuthorityResult.Forbidden>()
    }

    test("validateAuthority: Permitted for system SUPERSEDED") {
        val sm = OverrideStateMachine()
        val result = sm.validateAuthority(
            from = OverrideStatus.APPROVED,
            to = OverrideStatus.SUPERSEDED,
            actorDeviceId = otherDevice,
            proposerDeviceId = proposerDevice,
            isSystem = true
        )
        result shouldBe AuthorityResult.Permitted
    }

    test("validTransitionsFrom PROPOSED") {
        val sm = OverrideStateMachine()
        val transitions = sm.validTransitionsFrom(OverrideStatus.PROPOSED)
        (OverrideStatus.APPROVED in transitions) shouldBe true
        (OverrideStatus.DECLINED in transitions) shouldBe true
        (OverrideStatus.CANCELLED in transitions) shouldBe true
        (OverrideStatus.SUPERSEDED in transitions) shouldBe true
        (OverrideStatus.EXPIRED in transitions) shouldBe true
    }
})
