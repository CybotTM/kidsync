<!-- FOR AI AGENTS - Scoped to server/ -->
<!-- Last updated: 2026-02-25 -->

# Server AGENTS.md

Kotlin/Ktor sync server. Relays E2E encrypted ops between devices. Cannot decrypt user data.

## Stack

Kotlin 2.1.0, Ktor 3.0.3, Exposed ORM, SQLite WAL, JDK 21, kotlinx.serialization

## Package Map

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

## Commands

| Command | Purpose |
|---------|---------|
| `docker run --rm -v "$(pwd):/app" -w /app gradle:8.12-jdk21 gradle test --no-daemon` | Run all 464 tests |
| `docker run --rm -v "$(pwd):/app" -w /app gradle:8.12-jdk21 gradle buildFatJar --no-daemon` | Build fat JAR |
| `docker build -t kidsync-server .` | Build Docker image |

## Tests (464 across 41 test classes)

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

## Critical Patterns

**DatabaseFactory**: Kotlin `object` singleton. `dbQuery` passes explicit `database` reference to `newSuspendedTransaction`. Never rely on Exposed's global default.

**Transaction rollback**: Inside `dbQuery {}`, always `throw ApiException(...)`. Using `return@dbQuery Result.failure(...)` does NOT trigger rollback -- data corruption risk.

**Route → Service**: Auth and Sync routes delegate to service classes. FamilyRoutes/KeyRoutes currently bypass service layer (known tech debt).

**Error handling**: `ApiException` -> `StatusPages` -> `ErrorResponse` JSON. Never leak exception messages to clients.

## Config (env vars)

| Variable | Default | Purpose |
|----------|---------|---------|
| `KIDSYNC_DB_PATH` | `data/kidsync.db` | SQLite database path |
| `KIDSYNC_BLOB_PATH` | `data/blobs` | Blob storage directory |
| `KIDSYNC_SNAPSHOT_PATH` | `data/snapshots` | Snapshot storage directory |
| `KIDSYNC_CORS_ORIGINS` | (unset = anyHost) | Comma-separated allowed origins |
| `KIDSYNC_PORT` | `8080` | Server port |
| `KIDSYNC_SESSION_TTL_SECONDS` | `3600` | Session token lifetime |
| `KIDSYNC_CHALLENGE_TTL_SECONDS` | `60` | Challenge nonce lifetime |
| `KIDSYNC_SERVER_ORIGIN` | `https://api.kidsync.app` | Server origin for challenge-response auth (MUST set in prod) |

See `.env.example` at project root for full list.

## Known Tech Debt

- FamilyRoutes/KeyRoutes bypass service layer
- Test helpers duplicated across 4 test files
- No database migration tooling (uses `SchemaUtils.create()`)
- No correlation IDs or structured logging
