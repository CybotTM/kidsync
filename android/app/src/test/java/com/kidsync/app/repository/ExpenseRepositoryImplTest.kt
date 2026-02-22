package com.kidsync.app.repository

import com.kidsync.app.data.local.dao.ExpenseDao
import com.kidsync.app.data.local.entity.ExpenseEntity
import com.kidsync.app.data.local.entity.ExpenseStatusEntity
import com.kidsync.app.data.repository.ExpenseRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*

class ExpenseRepositoryImplTest : FunSpec({

    val expenseDao = mockk<ExpenseDao>(relaxed = true)

    fun createRepo() = ExpenseRepositoryImpl(expenseDao)

    beforeEach {
        clearAllMocks()
    }

    fun makeExpense(id: String, childId: String = "child-1", amountCents: Int = 5000) = ExpenseEntity(
        expenseId = id,
        childId = childId,
        paidByDeviceId = "device-1",
        amountCents = amountCents,
        currencyCode = "USD",
        category = "MEDICAL",
        description = "Test expense",
        incurredAt = "2026-01-15",
        payerResponsibilityRatio = 0.5
    )

    fun makeStatus(id: String, expenseId: String, status: String = "LOGGED") = ExpenseStatusEntity(
        id = id,
        expenseId = expenseId,
        status = status,
        responderId = "device-2",
        clientTimestamp = "2026-01-15T10:00:00Z"
    )

    // ── getAllExpenses ────────────────────────────────────────────────────────

    test("getAllExpenses returns all expenses from DAO") {
        val expenses = listOf(makeExpense("exp-1"), makeExpense("exp-2"))
        coEvery { expenseDao.getAllExpenses() } returns expenses

        val repo = createRepo()
        val result = repo.getAllExpenses()

        result.size shouldBe 2
        result[0].expenseId shouldBe "exp-1"
        result[1].expenseId shouldBe "exp-2"
    }

    test("getAllExpenses returns empty list when no expenses") {
        coEvery { expenseDao.getAllExpenses() } returns emptyList()

        val repo = createRepo()
        val result = repo.getAllExpenses()

        result.size shouldBe 0
    }

    // ── getExpenseById ──────────────────────────────────────────────────────

    test("getExpenseById returns matching expense") {
        val expense = makeExpense("exp-42")
        coEvery { expenseDao.getExpenseById("exp-42") } returns expense

        val repo = createRepo()
        val result = repo.getExpenseById("exp-42")

        result shouldNotBe null
        result!!.expenseId shouldBe "exp-42"
        result.amountCents shouldBe 5000
    }

    test("getExpenseById returns null for nonexistent expense") {
        coEvery { expenseDao.getExpenseById("nonexistent") } returns null

        val repo = createRepo()
        val result = repo.getExpenseById("nonexistent")

        result shouldBe null
    }

    // ── getLatestStatusForExpense ────────────────────────────────────────────

    test("getLatestStatusForExpense returns latest status") {
        val status = makeStatus("status-1", "exp-1", "ACKNOWLEDGED")
        coEvery { expenseDao.getLatestStatusForExpense("exp-1") } returns status

        val repo = createRepo()
        val result = repo.getLatestStatusForExpense("exp-1")

        result shouldNotBe null
        result!!.status shouldBe "ACKNOWLEDGED"
    }

    test("getLatestStatusForExpense returns null when no statuses") {
        coEvery { expenseDao.getLatestStatusForExpense("exp-new") } returns null

        val repo = createRepo()
        val result = repo.getLatestStatusForExpense("exp-new")

        result shouldBe null
    }

    // ── getStatusHistoryForExpense ───────────────────────────────────────────

    test("getStatusHistoryForExpense returns ordered status history") {
        val history = listOf(
            makeStatus("s-2", "exp-1", "ACKNOWLEDGED"),
            makeStatus("s-1", "exp-1", "LOGGED")
        )
        coEvery { expenseDao.getStatusHistoryForExpense("exp-1") } returns history

        val repo = createRepo()
        val result = repo.getStatusHistoryForExpense("exp-1")

        result.size shouldBe 2
        result[0].status shouldBe "ACKNOWLEDGED"
        result[1].status shouldBe "LOGGED"
    }

    test("getStatusHistoryForExpense returns empty list for expense with no statuses") {
        coEvery { expenseDao.getStatusHistoryForExpense("exp-none") } returns emptyList()

        val repo = createRepo()
        val result = repo.getStatusHistoryForExpense("exp-none")

        result.size shouldBe 0
    }
})
