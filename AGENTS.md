<!-- FOR AI AGENTS - Human readability is a side effect, not a goal -->
<!-- Managed by agent: keep sections and order; edit content, not structure -->
<!-- Last updated: 2026-02-25 | Last verified: 2026-02-25 -->

# AGENTS.md

**Precedence:** The **closest `AGENTS.md`** to the files you're changing wins. Root holds global defaults only.

## Overview

KidSync -- privacy-first co-parenting coordination app with E2E encryption.
Local-first, append-only OpLog. Server is a dumb encrypted relay (cannot decrypt user data).

## Commands

| Task | Command | ~Time |
|------|---------|-------|
| Server tests | `docker run --rm -v "$(pwd)/server:/app" -w /app gradle:8.12-jdk21 gradle test --no-daemon` | ~90s |
| Conformance | `python3 tests/conformance/verify_conformance.py` | ~2s |
| Server build | `docker run --rm -v "$(pwd)/server:/app" -w /app gradle:8.12-jdk21 gradle buildFatJar --no-daemon` | ~60s |
| Docker image | `docker build -t kidsync-server server/` | ~120s |
| Android tests | `cd android && ./gradlew test` | ~120s |
| Android build | `cd android && ./gradlew assembleDebug` | ~90s |

## File Map

```
server/          # Kotlin/Ktor relay server (routes, services, DB, plugins)
android/         # Jetpack Compose Android app (crypto, data, domain, UI)
docs/            # Protocol specs (wire-format, sync-protocol, encryption-spec), OpenAPI, reviews
tests/           # Conformance test vectors (JSON + Python verifier)
```

## Golden Samples

| For | Reference | Key patterns |
|-----|-----------|--------------|
| Route handler | `server/.../routes/AuthRoutes.kt` | Service delegation, ApiException, rate limiter |
| DB transaction | `server/.../db/DatabaseFactory.kt` | Explicit DB ref, `throw ApiException` for rollback |
| Use case | `android/.../usecase/sync/SyncOpsUseCase.kt` | Repository injection, suspend, error handling |
| ViewModel | `android/.../viewmodel/AuthViewModel.kt` | `@HiltViewModel`, StateFlow, viewModelScope |
| Composable | `android/.../screens/auth/LoginScreen.kt` | Material 3, collectAsStateWithLifecycle |

## Heuristics

| When | Do |
|------|-----|
| Adding server endpoint | Follow Route -> Service -> dbQuery pattern |
| Touching crypto code | Ask first -- protocol spec changes need approval |
| Adding dependency | Ask first -- we minimize deps |
| Unsure about pattern | Check Golden Samples above |
| Exposed `deleteWhere` with `<`, `<=` | Explicit import: `import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq` |

## Boundaries

### Always Do
- Run server tests before committing (see Commands above)
- Use `throw ApiException(...)` inside `dbQuery {}` blocks (NOT `return Result.failure`)
- Pass explicit database reference in Exposed transactions
- Use conventional commit format: `type(scope): subject`
- Show test output as evidence before claiming work is complete

### Ask First
- Changing protocol specs, database schema, encryption/key management code, or dependencies

### Never Do
- Commit `.env`, `*.keystore`, `*.jks`, or `local.properties`
- Access plaintext user data on the server
- Use `return@dbQuery Result.failure()` inside transactions (breaks rollback)
- Leak exception details to clients

## Terminology

| Term | Means |
|------|-------|
| OpLog | Append-only operation log (encrypted ops synced between devices) |
| Bucket | A family's data container (replaces "family" in sync context) |
| DEK | Data Encryption Key -- AES-256-GCM key, wrapped per device |
| AAD | Additional Authenticated Data: `familyId\|deviceId\|deviceSequence\|keyEpoch` |
| Hash chain | `SHA256(hexDecode(prevHash) + base64Decode(encryptedPayload))` |

## Index of scoped AGENTS.md

| Path | Scope |
|------|-------|
| [server/AGENTS.md](./server/AGENTS.md) | Ktor sync server -- routes, services, DB, plugins, rate limiting |
| [android/AGENTS.md](./android/AGENTS.md) | Android app -- crypto, data layer, domain logic, Compose UI |

## When instructions conflict

Nearest `AGENTS.md` wins. User prompts override files. Protocol specs override implementation code.
