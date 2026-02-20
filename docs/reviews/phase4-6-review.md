# Phase 4-6 Review: UI Modules (3-Cycle PAL Review #2)

**Date:** 2026-02-20
**Reviewer:** gemini-3-pro-preview via PAL (secaudit + codereview + analyze)
**Scope:** Auth/Onboarding (Phase 4), Calendar/Custody (Phase 5), Expense (Phase 6)
**Files reviewed:** 48 Kotlin UI files across ViewModels, screens, components, navigation

---

## Summary

The UI codebase demonstrates solid modern Android patterns (Jetpack Compose + Hilt + MVVM + StateFlow). However, the security audit identified critical gaps in credential handling and navigation access control that must be addressed before beta. The code review found architectural violations (DAO bypass, monolithic ViewModels) and the architecture analysis highlighted missing offline-first UI indicators.

---

## Critical Issues (Must Fix Before Beta)

### SEC-C1: Missing FLAG_SECURE on Sensitive Screens
**Files:** `RecoveryKeyScreen.kt`, `TotpSetupScreen.kt`
**Risk:** Screen capture/recording can steal 24-word recovery mnemonic or TOTP secret
**Fix:** Add `DisposableEffect` to set `FLAG_SECURE` on window entry, clear on exit

### SEC-C2: No Navigation Auth Guards
**File:** `NavGraph.kt:205-377`
**Risk:** Deep links can navigate directly to Dashboard/Calendar/Expense without authentication
**Fix:** Implement `AuthenticatedRoute` wrapper composable that checks session state

### SEC-C3: Credentials Persist in StateFlow Memory
**File:** `AuthViewModel.kt:28-43`
**Risk:** registerPassword, loginPassword, totpSetup.secret, recoveryWords all persist in immutable StateFlow copies on the heap until GC
**Fix:** Use CharArray for password processing, clear secrets from state after use, don't expose raw secrets in public UI state

### ARCH-C1: Untyped Payload Map Construction
**Files:** `CalendarViewModel.kt:330-342,445-458`, `ExpenseViewModel.kt`
**Risk:** String keys ("payloadType", "entityId") are brittle and error-prone. Protocol changes require updating every ViewModel.
**Fix:** Create sealed `OperationPayload` interface with typed data classes, serialize via kotlinx.serialization

### ARCH-C2: No Offline-First UI Indicators
**Files:** `DashboardScreen.kt`, all screens
**Risk:** Users assume data is shared when it may still be local-only. Critical for co-parenting custody handoffs.
**Fix:** Add global SyncStatus component (syncing/synced/offline badge in TopAppBar)

---

## High Issues (Should Fix Before Beta)

### QA-H1: ExpenseViewModel Bypasses Repository Pattern
**File:** `ExpenseViewModel.kt:97`
Directly injects `ExpenseDao` instead of repository. Only ViewModel that does this.
**Fix:** Create `ExpenseRepository` interface and implementation.

### QA-H2: CalendarViewModel SRP Violation (743 lines)
**File:** `CalendarViewModel.kt`
Handles 6+ features: month nav, child selection, schedule setup, swap requests, events, utility.
**Fix:** Split into CalendarDisplayViewModel, ScheduleSetupViewModel, SwapRequestViewModel, EventFormViewModel.

### QA-H3: Monolithic UI State Objects
**Files:** `AuthUiState` (16 fields), `CalendarUiState` (25 fields)
Mixing unrelated flows causes unnecessary recompositions.
**Fix:** Use sealed sub-states per screen flow.

### QA-H4: In-Memory Data Filtering
**File:** `ExpenseViewModel.kt:120-164`
`loadExpenses()` fetches ALL expenses, filters in Kotlin. `loadSummary()` (line 420) duplicates the query.
**Fix:** Push filters to SQL WHERE clause in DAO. Use aggregation queries for summary.

### SEC-H1: Weak Password Policy
**File:** `AuthViewModel.kt:100`
Only checks `length >= 8`. No complexity requirements.
**Fix:** Enforce NIST guidelines (min 12 chars or complexity requirements).

### QA-H5: Missing Phase 7 Prerequisites
No sync status composable, no conflict resolution UI, no key rotation notification, no WebSocket connection management in UI layer.

---

## Medium Issues

| ID | File | Issue |
|----|------|-------|
| QA-M1 | `CalendarViewModel.kt:161-168` | No debouncing on `navigateMonth()` - rapid taps trigger multiple coroutines |
| QA-M2 | `NavGraph.kt`, `Routes.kt` | Dates passed as String through navigation, loses type safety |
| SEC-M1 | `ExpenseViewModel.kt:526-529` | Amount parsing via `toDoubleOrNull()` allows precision issues |
| SEC-M2 | `AuthViewModel.kt:353-358` | Logout doesn't clear nav back stack screen caches |
| SEC-M3 | Multiple ViewModels | Error messages from exceptions displayed directly to user |
| QA-M3 | `CalendarViewModel.kt:221-225` | In-memory event filtering breaks across month boundaries |
| ARCH-M1 | `CalendarViewModel.kt:347` | `EntityType.Event` referenced but may not exist in domain enum |

---

## Positive Findings

- **MVVM Architecture:** Consistent unidirectional data flow with StateFlow + asStateFlow
- **Hilt DI:** Properly configured @HiltViewModel and @Inject across all ViewModels
- **Error Handling:** Consistent Result.fold pattern in all async operations
- **Process Death:** SavedStateHandle used for receipt URI persistence (ExpenseViewModel)
- **Compose Patterns:** Good use of Scaffold, LazyColumn, state hoisting, semantics/contentDescription
- **Coroutines:** Correct viewModelScope usage for all async work
- **Navigation:** Clean sealed class Routes with typed popUpTo back stack management
- **i18n:** Complete German translations for all 3 modules
- **Crypto Primitives:** Strong choices (X25519, AES-256-GCM, Ed25519, BIP39)

---

## Spec Issues (Previously Fixed)

Note: Several spec-level issues identified by PAL (hash chain formula, AAD construction, nonce management, upload response format, HTTP status codes) were already addressed in commit `db5cc64` during the Phase 1 review fix cycle. The PAL analysis re-flagged these because it read the same spec files. The Android and server code already implement the corrected versions.

---

## Recommended Fix Priority

1. **Immediate:** SEC-C1 (FLAG_SECURE) + SEC-C2 (auth guards) - low effort, high impact
2. **Short-term:** SEC-C3 (credential memory) + SEC-H1 (password policy)
3. **Pre-beta:** ARCH-C1 (typed payloads) + QA-H1 (ExpenseRepository) + QA-H4 (SQL filtering)
4. **Phase 7:** ARCH-C2 (sync status UI) + QA-H5 (missing prerequisites)
5. **Refactor:** QA-H2 (split CalendarViewModel) + QA-H3 (sealed sub-states)
