<!-- FOR AI AGENTS - Scoped to android/ -->
<!-- Last updated: 2026-02-20 -->

# Android AGENTS.md

Jetpack Compose Android app. Local-first with E2E encrypted sync to Ktor server.

## Stack

Kotlin, Jetpack Compose, Room + SQLCipher, Tink (crypto), Hilt (DI), WorkManager, Retrofit, min SDK 26, target SDK 35

## Package Map

```
app/src/main/java/com/kidsync/app/
  KidSyncApplication.kt   # @HiltAndroidApp
  crypto/
    CryptoManager.kt       # Interface + buildPayloadAad() helper
    TinkCryptoManager.kt   # AES-256-GCM encrypt/decrypt, X25519
  data/
    local/                  # Room DB, DAOs, entities, converters
    remote/api/             # ApiService (Retrofit), DTOs
    remote/interceptor/     # AuthInterceptor (JWT token refresh)
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
    viewmodel/              # AuthViewModel, CalendarViewModel, ExpenseViewModel, FamilyViewModel, SettingsViewModel
```

## Commands

| Command | Purpose |
|---------|---------|
| Open `android/` in Android Studio | IDE setup |
| `./gradlew test` | Run unit tests |
| `./gradlew assembleRelease` | Build release APK (requires signing config) |
| `./gradlew assembleDebug` | Build debug APK |

## Architecture Layers

```
UI (Compose screens + ViewModels)
  ↓ StateFlow + collectAsStateWithLifecycle
Domain (use cases, models, repository interfaces)
  ↓ suspend functions
Data (Room DAOs, Retrofit API, repository impls, SyncWorker)
  ↓
Crypto (Tink: AES-256-GCM, X25519, HKDF, BIP39)
```

All ViewModels: `@HiltViewModel`, `viewModelScope`, `MutableStateFlow`/`StateFlow`

## Critical Patterns

**AAD construction**: `CryptoManager.buildPayloadAad(familyId, deviceId, deviceSequence, keyEpoch)` -> `"familyId|deviceId|deviceSequence|keyEpoch"`

**Nonce**: Random 12 bytes prepended to ciphertext: `nonce(12) || ciphertext || tag`, then Base64-encoded

**Hash chain**: `HashChainVerifier.computeHash(prevHash, encryptedPayload)` = `SHA256(hexDecode(prevHash) + base64Decode(encryptedPayload))`

**Navigation**: Sealed class `Routes` with `data object` entries. `popUpTo` with `inclusive = true` at auth flow transitions.

## Signing (Release)

Reads from env vars or `local.properties`:
- `KIDSYNC_KEYSTORE_PATH` / `keystore.path`
- `KIDSYNC_KEYSTORE_PASSWORD` / `keystore.password`
- `KIDSYNC_KEY_ALIAS` / `key.alias`
- `KIDSYNC_KEY_PASSWORD` / `key.password`

Only activates when all 4 values present.

## Known Tech Debt

- CalendarViewModel is 743 lines (SRP violation, should split)
- ExpenseViewModel bypasses repository, injects DAO directly
- Monolithic UI state objects (AuthUiState: 16 fields, CalendarUiState: 25 fields)
- In-memory expense filtering instead of SQL WHERE
- No sync status UI indicator (offline/syncing/synced)
