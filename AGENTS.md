<!-- FOR AI AGENTS - Human readability is a side effect, not a goal -->
<!-- Managed by agent: keep sections and order; edit content, not structure -->
<!-- Last updated: 2026-02-20 | Last verified: 2026-02-20 -->

# AGENTS.md

**Precedence:** The **closest AGENTS.md** to changed files wins. Root holds global defaults only.

## Project Overview

KidSync -- privacy-first co-parenting coordination app with E2E encryption.
Local-first, append-only OpLog. Server is a dumb encrypted relay (cannot decrypt user data).

| Component | Stack | Entry Point |
|-----------|-------|-------------|
| Server | Kotlin 2.1.0, Ktor 3.0.3, Exposed ORM, SQLite WAL, JDK 21 | `server/.../Application.kt` |
| Android | Kotlin, Jetpack Compose, Room + SQLCipher, Tink, Hilt | `android/.../ui/MainActivity.kt` |
| Specs | Markdown + YAML + JSON test vectors | `docs/`, `tests/conformance/` |

## Global Rules

- Conventional Commits: `type(scope): subject`
- All data models use `kotlinx.serialization`
- Hash chain: `SHA256(hexDecode(prevHash) + base64Decode(encryptedPayload))`
- AAD format: `familyId|deviceId|deviceSequence|keyEpoch`
- No Java locally -- server tests run via Docker

## Boundaries

### Always Do
- Run server tests before committing: `docker run --rm -v "$(pwd)/server:/app" -w /app gradle:8.12-jdk21 gradle test --no-daemon`
- Use `throw ApiException(...)` inside `dbQuery {}` blocks (NOT `return Result.failure`)
- Pass explicit database reference in Exposed transactions

### Ask First
- Changing protocol specs, database schema, encryption/key management code, or dependencies

### Never Do
- Commit `.env`, `*.keystore`, `*.jks`, or `local.properties`
- Access plaintext user data on the server
- Use `return@dbQuery Result.failure()` inside transactions (breaks rollback)
- Leak exception details to clients

## Security

- E2E encrypted: X25519 key agreement + AES-256-GCM
- Passwords: bcrypt. Tokens: JWT (15 min access, 30 day refresh)
- CORS restricted via `KIDSYNC_CORS_ORIGINS` env var
- Rate limiting per endpoint. `FLAG_SECURE` on sensitive screens.

## Testing (44 server tests)

```bash
docker run --rm -v "$(pwd)/server:/app" -w /app gradle:8.12-jdk21 gradle test --no-daemon
python3 tests/conformance/verify_conformance.py  # Conformance vectors
```

## Documentation

| Document | Path |
|----------|------|
| Specs | `docs/protocol/wire-format.md`, `sync-protocol.md`, `encryption-spec.md` |
| OpenAPI | `docs/api/openapi.yaml` |
| Reviews | `docs/reviews/phase1-review.md`, `phase4-6-review.md`, `phase7-server-review.md` |
| Ops | `docs/disaster-recovery.md`, `docker-compose.yml`, `.env.example` |

## Index of Scoped AGENTS.md

| Path | Scope |
|------|-------|
| `server/AGENTS.md` | Ktor sync server (routes, services, DB, plugins) |
| `android/AGENTS.md` | Android app (crypto, data, domain, UI) |

## When Instructions Conflict

Nearest AGENTS.md wins. User prompts override files. Protocol specs override implementation code.
