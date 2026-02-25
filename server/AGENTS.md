<!-- FOR AI AGENTS - Scoped to server/ -->
<!-- Managed by agent: keep sections and order; edit content, not structure -->
<!-- Last updated: 2026-02-25 | Last verified: 2026-02-25 -->

# Server AGENTS.md

## Overview

Kotlin/Ktor sync server. Relays E2E encrypted ops between devices. Cannot decrypt user data.
Ed25519 challenge-response auth, opaque session tokens (1h TTL), per-endpoint rate limiting.

**Stack:** Kotlin 2.1.0, Ktor 3.0.3, Exposed ORM, SQLite WAL, JDK 21, kotlinx.serialization

## Setup

No local JDK required -- all commands run via Docker:

```bash
docker run --rm -v "$(pwd):/app" -w /app gradle:8.12-jdk21 gradle test --no-daemon
```

Config via env vars (see `.env.example` at project root):

| Variable | Default | Purpose |
|----------|---------|---------|
| `KIDSYNC_DB_PATH` | `data/kidsync.db` | SQLite database path |
| `KIDSYNC_BLOB_PATH` | `data/blobs` | Blob storage directory |
| `KIDSYNC_SNAPSHOT_PATH` | `data/snapshots` | Snapshot storage directory |
| `KIDSYNC_CORS_ORIGINS` | (unset = anyHost) | Comma-separated allowed origins |
| `KIDSYNC_PORT` | `8080` | Server port |
| `KIDSYNC_SESSION_TTL_SECONDS` | `3600` | Session token lifetime |
| `KIDSYNC_CHALLENGE_TTL_SECONDS` | `60` | Challenge nonce lifetime |
| `KIDSYNC_SERVER_ORIGIN` | `https://api.kidsync.app` | Server origin (MUST set in prod) |

## Commands

| Task | Command | ~Time |
|------|---------|-------|
| Test (all) | `docker run --rm -v "$(pwd):/app" -w /app gradle:8.12-jdk21 gradle test --no-daemon` | ~90s |
| Build JAR | `docker run --rm -v "$(pwd):/app" -w /app gradle:8.12-jdk21 gradle buildFatJar --no-daemon` | ~60s |
| Docker image | `docker build -t kidsync-server .` | ~120s |

## File Map

```
src/main/kotlin/dev/kidsync/server/
  Application.kt        # Main module, wires routes/services/plugins, /health endpoint
  Config.kt             # AppConfig (all env vars with defaults)
  db/
    DatabaseFactory.kt   # Singleton init + dbQuery wrapper (explicit DB reference!)
    Tables.kt            # 13 Exposed tables (Users, Devices, Families, OpLog, Blobs, etc.)
  models/                # Requests.kt, Responses.kt, Enums.kt (DTOs)
  plugins/               # Auth, CORS, RateLimit, Serialization, StatusPages, WebSockets
  routes/                # Auth, Blob, Device, Family, Key, Push, Sync (7 route files)
  services/              # AuthService, SyncService, BlobService, PushService, WebSocketManager
  util/                  # HashUtil, SessionUtil, SlidingWindowRateLimiter, ValidationUtil
```

## Testing (464 across 41 test classes)

| Area | Key Suites | Focus |
|------|-----------|-------|
| Auth | AuthTest, AuthIntegrationTest, SessionEdgeCaseTest, SessionTokenPrefixTest | Challenge-response, sessions, token prefixes |
| Sync | SyncTest, SyncIntegrationTest, SyncServiceExtendedTest, OpPruningTest | Upload, pull, hash chain, pagination, pruning |
| Hash | HashChainTest, HashUtilUnitTest | SHA-256, hex, chain verification |
| Buckets | BucketTest, BucketIntegrationTest, BucketCreatorTransferTest, BucketServiceCascadeTest | CRUD, invites, creator transfer, cascade delete |
| Devices | DeviceDeregistrationTest, DeviceRevocationTest, DeviceRegistrationRateLimitTest | Registration, revocation, rate limits |
| Keys | KeyTest, KeyServiceExtendedTest | Wrapped DEKs, attestations |
| Blobs/Snapshots | BlobIntegrationTest, BlobServiceTest, SnapshotQuotaTest, SnapshotDownloadTest | Upload, download, quota |
| Security | SecurityHeaderTest, InputValidationEdgeCaseTest, MalformedInputTest, ValidationUtilTest | Headers, input validation, UUID checks |
| E2E | E2ETest, TwoDevicePairingE2ETest | Full lifecycle, multi-device pairing |
| WebSocket | WebSocketManagerTest, WebSocketQueryParamAuthTest | Connection limits, query param auth |
| Infrastructure | ConfigTest, HealthEndpointTest, ConcurrencyTest, RateLimiterTest, SlidingWindowRateLimiterTest | Config, health, concurrency, rate limits |

Test pattern: `testApplication { application { module(testConfig()) } }` with `TestHelper` for device registration, auth, and JSON client creation.

## Code Style

- `kotlinx.serialization` for all DTOs (never Jackson/Gson)
- Error codes as integer literals in `ApiException(statusCode, message, errorCode)` -- e.g. `ApiException(404, "Not found", "NOT_FOUND")`
- Route files import and use `SlidingWindowRateLimiter` for all rate limiting
- Named constants for magic numbers (`CLEANUP_INTERVAL_MS`, `MAX_REQUEST_BODY_BYTES`, `HSTS_MAX_AGE_SECONDS`)

## Security

- **Zero knowledge:** Server never sees plaintext user data
- **Auth:** Ed25519 challenge-response. Server issues challenges, device signs with private key
- **Sessions:** Opaque tokens prefixed `sess_` (1h TTL). Challenges prefixed `chal_`
- **Rate limiting:** `SlidingWindowRateLimiter` shared utility -- per-key sliding window, thread-safe
- **CORS:** Restricted via `KIDSYNC_CORS_ORIGINS` env var; unset = anyHost (dev only)
- **Headers:** HSTS, X-Content-Type-Options, X-Frame-Options

## Critical Patterns

**DatabaseFactory**: Kotlin `object` singleton. `dbQuery` passes explicit `database` reference to `newSuspendedTransaction`. Never rely on Exposed's global default.

**Transaction rollback**: Inside `dbQuery {}`, always `throw ApiException(...)`. Using `return@dbQuery Result.failure(...)` does NOT trigger rollback -- data corruption risk.

**Route -> Service**: Auth and Sync routes delegate to service classes. FamilyRoutes/KeyRoutes currently bypass service layer (known tech debt).

**Error handling**: `ApiException` -> `StatusPages` -> `ErrorResponse` JSON. Never leak exception messages to clients.

**Exposed deleteWhere**: In Exposed 0.57.0, `SqlExpressionBuilder` member extensions (`lessEq`, `greaterEq`) need explicit imports. Wildcard `import org.jetbrains.exposed.sql.*` doesn't cover them.

## Examples

Good -- service delegation with proper error handling:
```kotlin
post("/auth/challenge") {
    val request = call.receive<ChallengeRequest>()
    if (!challengeKeyRateLimiter.checkAndIncrement(request.signingKey)) {
        throw ApiException(429, "Too many requests", "RATE_LIMITED")
    }
    val challenge = authService.createChallenge(request.signingKey)
    call.respond(challenge)
}
```

Bad -- bypassing service, swallowing errors:
```kotlin
post("/auth/challenge") {
    val request = call.receive<ChallengeRequest>()
    val result = dbQuery { /* inline DB logic */ }
    if (result == null) call.respond(HttpStatusCode.NotFound) // leaks nothing but no structured error
}
```

## Checklist

Before committing server changes:
- [ ] `docker run --rm -v "$(pwd):/app" -w /app gradle:8.12-jdk21 gradle test --no-daemon` passes
- [ ] No `return@dbQuery Result.failure(...)` inside `dbQuery {}` blocks
- [ ] Explicit `database` reference in all Exposed transactions
- [ ] `ApiException` uses integer status codes (not `HttpStatusCode.*.value`)
- [ ] No exception messages leaked to clients
- [ ] Rate limiting uses `SlidingWindowRateLimiter` (no ad-hoc implementations)

## Known Tech Debt

- FamilyRoutes/KeyRoutes bypass service layer
- Test helpers duplicated across 4 test files
- No database migration tooling (uses `SchemaUtils.create()`)
- No correlation IDs or structured logging

## When Stuck

1. Check `TestHelper.kt` for test setup patterns (device registration, auth flow)
2. Check `StatusPages` plugin for error response format
3. Check `DatabaseFactory.dbQuery` for transaction wrapper
4. Run tests: failures give clear assertion messages with context
5. Check `.env.example` for all available config vars
