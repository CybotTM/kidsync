<!-- FOR AI AGENTS - Scoped to android/ -->
<!-- Managed by agent: keep sections and order; edit content, not structure -->
<!-- Last updated: 2026-02-25 | Last verified: 2026-02-25 -->

# Android AGENTS.md

## Overview

Jetpack Compose Android app. Local-first with E2E encrypted sync to Ktor server.
Clean architecture (UI -> Domain -> Data -> Crypto), Hilt DI, Room + SQLCipher, WorkManager sync.

**Stack:** Kotlin, Jetpack Compose, Room + SQLCipher, BouncyCastle + JCA, Hilt, WorkManager, Retrofit, min SDK 26, target SDK 35

## Setup

- Android Studio with Kotlin plugin
- JDK 17 for compilation
- No server dependency for unit tests (mocked)

Release signing reads from env vars or `local.properties`:
- `KIDSYNC_KEYSTORE_PATH` / `keystore.path`
- `KIDSYNC_KEYSTORE_PASSWORD` / `keystore.password`
- `KIDSYNC_KEY_ALIAS` / `key.alias`
- `KIDSYNC_KEY_PASSWORD` / `key.password`

Only activates when all 4 values present.

## Commands

| Task | Command | ~Time |
|------|---------|-------|
| Test (all) | `./gradlew test` | ~120s |
| Build debug | `./gradlew assembleDebug` | ~90s |
| Build release | `./gradlew assembleRelease` | ~90s |

## File Map

```
app/src/main/java/com/kidsync/app/
  KidSyncApplication.kt   # @HiltAndroidApp
  crypto/
    CryptoManager.kt       # Interface + buildPayloadAad() helper
    TinkCryptoManager.kt   # AES-256-GCM encrypt/decrypt, X25519 (BouncyCastle + JCA)
  data/
    local/                  # Room DB, DAOs, entities, converters
    remote/api/             # ApiService (Retrofit), DTOs
    remote/interceptor/     # AuthInterceptor (session token auth)
    repository/             # Repository implementations
    sync/                   # SyncWorker (WorkManager periodic sync)
  di/                       # 4 Hilt modules: App, Database, Network, Crypto
  domain/
    model/                  # Domain models (CustodySchedule, Expense, etc.)
    repository/             # Repository interfaces
    usecase/auth/           # LoginUseCase, RegisterUseCase, RecoveryUseCase
    usecase/custody/        # GetCustodyCalendarUseCase, PatternGenerator, OverrideStateMachine
    usecase/expense/        # CreateExpenseUseCase, UpdateExpenseStatusUseCase, GetExpenseSummaryUseCase
    usecase/sync/           # CreateOperationUseCase, SyncOpsUseCase, HashChainVerifier
  ui/
    components/             # Reusable composables (CalendarGrid, MnemonicWordGrid, etc.)
    navigation/             # NavGraph.kt, Routes.kt (sealed class)
    screens/                # auth/, calendar/, dashboard/, expense/, family/, settings/
    theme/                  # Color, Type, Theme (Material 3 + dynamic colors)
    viewmodel/              # AuthVM, CalendarVM, ExpenseVM, FamilyVM, SettingsVM
```

## Testing (881+ tests)

All tests are unit tests using JUnit, Mockito/MockK, and Kotlin coroutines test.

Test pattern: mock repositories/DAOs, inject into use case or ViewModel, assert StateFlow emissions.

## Code Style

- `@HiltViewModel` + `viewModelScope` + `MutableStateFlow`/`StateFlow` for all ViewModels
- `collectAsStateWithLifecycle` in Composables (never `collectAsState`)
- Clean architecture: UI -> Domain -> Data (repositories bridge the layers)
- `SharedPreferences.commit()` for security-critical state, `.apply()` only for non-critical
- Sealed class `Routes` with `data object` entries for navigation
- `popUpTo` with `inclusive = true` at auth flow transitions

## Security

- **E2E encryption:** AES-256-GCM with X25519 key agreement, HKDF key derivation
- **Key storage:** Android Keystore for session keys, SQLCipher for Room DB encryption
- **Recovery:** BIP39 mnemonic seed phrase (12 words)
- **Nonce:** Random 12 bytes prepended to ciphertext: `nonce(12) || ciphertext || tag`, then Base64
- **AAD:** `CryptoManager.buildPayloadAad(familyId, deviceId, deviceSequence, keyEpoch)` -> `"a|b|c|d"`
- **Hash chain:** `HashChainVerifier.computeHash(prevHash, encryptedPayload)` = `SHA256(hexDecode(prevHash) + base64Decode(encryptedPayload))`
- **FLAG_SECURE** on sensitive screens
- **Key zeroing:** Caller owns the key -- don't zero input parameters in decrypt methods

## Critical Patterns

**Architecture layers:**
```
UI (Compose screens + ViewModels)
  -> StateFlow + collectAsStateWithLifecycle
Domain (use cases, models, repository interfaces)
  -> suspend functions
Data (Room DAOs, Retrofit API, repository impls, SyncWorker)
  -> Crypto (BouncyCastle + JCA)
```

**Sync backends** (all transfer already-encrypted ops -- zero-knowledge maintained):
- Server relay (default): REST API push/pull
- File export/import: ZIP `.kidsync` bundles via SAF
- WebDAV/NextCloud: OkHttp PROPFIND/MKCOL/PUT/GET, WorkManager periodic
- P2P local: Google Nearby Connections, HMAC-SHA256 handshake

## Examples

Good -- ViewModel with proper state management:
```kotlin
@HiltViewModel
class ExampleViewModel @Inject constructor(
    private val useCase: ExampleUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(ExampleUiState())
    val state: StateFlow<ExampleUiState> = _state.asStateFlow()

    fun onAction() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            useCase.execute().fold(
                onSuccess = { _state.update { s -> s.copy(data = it, loading = false) } },
                onFailure = { _state.update { s -> s.copy(error = it.message, loading = false) } },
            )
        }
    }
}
```

Bad -- bypassing repository, direct DAO in ViewModel:
```kotlin
@HiltViewModel
class BadViewModel @Inject constructor(
    private val dao: ExampleDao, // Should use repository/use case
) : ViewModel() { /* ... */ }
```

## Checklist

Before committing Android changes:
- [ ] `./gradlew test` passes
- [ ] ViewModels use `@HiltViewModel` + `StateFlow` (not LiveData)
- [ ] Composables use `collectAsStateWithLifecycle` (not `collectAsState`)
- [ ] Security-critical SharedPreferences use `.commit()` (not `.apply()`)
- [ ] Crypto key zeroing: caller owns the key, don't zero input params
- [ ] New screens with sensitive data use `FLAG_SECURE`

## Known Tech Debt

- CalendarViewModel is 743 lines (SRP violation, should split)
- Monolithic UI state objects (AuthUiState: 16 fields, CalendarUiState: 25 fields)
- In-memory expense filtering instead of SQL WHERE
- No sync status UI indicator (offline/syncing/synced)

## When Stuck

1. Check `di/` modules for what's provided by Hilt (DatabaseModule, NetworkModule, CryptoModule, AppModule)
2. Check `domain/repository/` interfaces for available data operations
3. Check `data/local/dao/` for Room query patterns
4. Check `ui/navigation/Routes.kt` for all navigation destinations
5. Check `crypto/CryptoManager.kt` interface for encryption API
6. Run `./gradlew test` -- failures give clear assertion messages
