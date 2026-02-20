<!-- FOR AI AGENTS - Scoped to server/ -->
<!-- Last updated: 2026-02-20 -->

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
  util/                  # HashUtil, JwtUtil, ValidationUtil
```

## Commands

| Command | Purpose |
|---------|---------|
| `docker run --rm -v "$(pwd):/app" -w /app gradle:8.12-jdk21 gradle test --no-daemon` | Run all 44 tests |
| `docker run --rm -v "$(pwd):/app" -w /app gradle:8.12-jdk21 gradle buildFatJar --no-daemon` | Build fat JAR |
| `docker build -t kidsync-server .` | Build Docker image |

## Tests (44 total)

| Suite | Tests | Coverage |
|-------|-------|----------|
| AuthTest | 8 | Register, login, TOTP, refresh tokens, validation |
| SyncTest | 7 | Upload, pull, hash chain, handshake, pagination |
| HashChainTest | 8 | SHA-256, hex, chain verification |
| IntegrationTest | 8 | Family flow, invites, devices, keys, blobs |
| E2ETest | 8 | Full lifecycle, multi-device, revocation, checkpoints |
| OverrideStateMachineTest | 5 | State transitions, proposer rules |

## Critical Patterns

**DatabaseFactory**: Kotlin `object` singleton. `dbQuery` passes explicit `database` reference to `newSuspendedTransaction`. Never rely on Exposed's global default.

**Transaction rollback**: Inside `dbQuery {}`, always `throw ApiException(...)`. Using `return@dbQuery Result.failure(...)` does NOT trigger rollback -- data corruption risk.

**Route → Service**: Auth and Sync routes delegate to service classes. FamilyRoutes/KeyRoutes currently bypass service layer (known tech debt).

**Error handling**: `ApiException` -> `StatusPages` -> `ErrorResponse` JSON. Never leak exception messages to clients.

## Config (env vars)

| Variable | Default | Purpose |
|----------|---------|---------|
| `KIDSYNC_DB_PATH` | `data/kidsync.db` | SQLite database path |
| `KIDSYNC_JWT_SECRET` | dev placeholder | JWT signing secret (MUST change in prod) |
| `KIDSYNC_CORS_ORIGINS` | (unset = anyHost) | Comma-separated allowed origins |
| `KIDSYNC_PORT` | `8080` | Server port |

See `.env.example` at project root for full list.

## Known Tech Debt

- FamilyRoutes/KeyRoutes bypass service layer
- Test helpers duplicated across 4 test files
- No database migration tooling (uses `SchemaUtils.create()`)
- No correlation IDs or structured logging
