package com.kidsync.app.viewmodel

import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.ui.viewmodel.FamilyViewModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class FamilyViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()
    val authRepository = mockk<AuthRepository>(relaxed = true)
    val bucketRepository = mockk<BucketRepository>(relaxed = true)

    beforeEach {
        Dispatchers.setMain(testDispatcher)
        clearAllMocks()
    }

    afterEach {
        Dispatchers.resetMain()
    }

    fun createViewModel(): FamilyViewModel {
        return FamilyViewModel(authRepository, bucketRepository)
    }

    // ── Child Management ─────────────────────────────────────────────────────

    test("initial state has one empty child entry") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.uiState.value.children.size shouldBe 1
            vm.uiState.value.children[0].name shouldBe ""
        }
    }

    test("onChildNameChanged updates child name at index") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onChildNameChanged(0, "Alice")
            vm.uiState.value.children[0].name shouldBe "Alice"
        }
    }

    test("addChild adds a new empty entry") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.addChild()
            vm.uiState.value.children.size shouldBe 2
            vm.uiState.value.children[1].name shouldBe ""
        }
    }

    test("removeChild removes the child at index") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.addChild()
            vm.onChildNameChanged(0, "Alice")
            vm.onChildNameChanged(1, "Bob")
            vm.uiState.value.children.size shouldBe 2

            vm.removeChild(0)
            vm.uiState.value.children.size shouldBe 1
            vm.uiState.value.children[0].name shouldBe "Bob"
        }
    }

    test("removeChild does not remove the last child") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.removeChild(0)
            vm.uiState.value.children.size shouldBe 1 // Still 1
        }
    }

    // ── Validation ───────────────────────────────────────────────────────────

    test("saveChildren with all blank names shows error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.saveChildren()
            advanceUntilIdle()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "at least one child"
        }
    }

    test("saveChildren with valid name succeeds") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onChildNameChanged(0, "Alice")
            vm.saveChildren()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe null
            vm.uiState.value.isLoading shouldBe false
        }
    }

    // ── Error Handling ───────────────────────────────────────────────────────

    test("clearError resets error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.saveChildren() // triggers error
            advanceUntilIdle()
            vm.uiState.value.error shouldNotBe null

            vm.clearError()
            vm.uiState.value.error shouldBe null
        }
    }
})
