# Phase 7 Review: Server Deep Dive (3-Cycle PAL Review #3)

**Date:** 2026-02-20
**Reviewer:** gemini-3-pro-preview via PAL (secaudit + codereview + thinkdeep)
**Scope:** Full server codebase (34 Kotlin files) — routes, services, plugins, database, utilities
**Test status:** 44/44 passing (8 AuthTest + 7 SyncTest + 8 HashChainTest + 8 IntegrationTest + 8 E2ETest + 5 OverrideStateMachineTest)

---

## Summary

The server codebase is well-structured with Ktor + Exposed ORM patterns, comprehensive hash chain validation, and solid test coverage. However, the security audit identified critical CORS misconfiguration and missing blob validation. The code review found a transaction rollback bug in `SyncService.uploadOps` and inconsistent service layer usage. The architecture analysis highlighted missing production essentials (health check, migration versioning, graceful shutdown).

---

## Critical Issues (Must Fix Before Beta)

### SEC-C1: CORS anyHost() Configuration
**File:** `plugins/CORS.kt`
**Risk:** Allows any origin to make authenticated requests. In production, this enables CSRF-style attacks from malicious sites.
**Fix:** Replace `anyHost()` with explicit allowed origins list matching the deployed frontend domain(s).

### QA-C1: SyncService.uploadOps Partial Commit Bug
**File:** `services/SyncService.kt` (uploadOps method)
**Risk:** `return@dbQuery Result.failure(ApiException(...))` inside a transaction does NOT trigger rollback. If hash chain validation fails mid-batch, earlier ops in the batch are already committed. This violates the atomicity guarantee.
**Fix:** Replace `Result.failure(...)` pattern with `throw ApiException(...)` to trigger Exposed's automatic transaction rollback on exception.

### QA-C2: FamilyRoutes / KeyRoutes Bypass Service Layer
**Files:** `routes/FamilyRoutes.kt`, `routes/KeyRoutes.kt`
**Risk:** Business logic (family creation, invite flow, key operations) lives directly in route handlers. Inconsistent with AuthRoutes→AuthService and SyncRoutes→SyncService pattern. Makes testing business logic difficult without HTTP layer.
**Fix:** Extract `FamilyService` and `KeyService` classes. Route handlers should only handle request parsing, service delegation, and response formatting.

### QA-C3: No Health Check Endpoint
**Files:** `Application.kt`, routes
**Risk:** No way for load balancers, orchestrators, or monitoring to verify server health. Required for any production deployment.
**Fix:** Add `GET /health` endpoint returning 200 with `{ "status": "ok", "db": true }` after verifying database connectivity.

---

## High Issues (Should Fix Before Beta)

### SEC-H1: WebSocket Error Leaks Exception Details
**File:** `routes/SyncRoutes.kt` (WebSocket handler)
**Risk:** Exception `.message` sent directly to client over WebSocket. May leak internal paths, query details, or stack traces.
**Fix:** Send generic error message to client, log full exception server-side.

### SEC-H2: No Account Lockout / Brute Force Protection
**File:** `services/AuthService.kt`
**Risk:** Rate limiting exists (10/min per IP) but no account-level lockout after repeated failed logins. Distributed attacks from multiple IPs bypass IP-based rate limiting.
**Fix:** Track failed login attempts per account. Lock after 10 failures for 15 minutes.

### SEC-H3: Snapshot URL Expiration Not Enforced
**File:** `routes/SyncRoutes.kt` (snapshot download)
**Risk:** Snapshot download URLs don't have time-limited access tokens. Once a URL is known, it's accessible indefinitely.
**Fix:** Add signed URL tokens with expiration for snapshot downloads.

### QA-H1: Batch Upload Rejects All on Single Failure
**File:** `services/SyncService.kt`
**Risk:** If one op in a batch of 50 has a bad hash, all 50 are rejected. Client must retry the entire batch.
**Fix:** Consider partial acceptance: validate all ops, reject only invalid ones, return accepted/rejected lists.

### QA-H2: Duplicated getLatestSequence Logic
**Files:** `services/SyncService.kt`, `routes/SyncRoutes.kt`
**Risk:** Latest sequence lookup exists in both service and route layers. Divergence risk.
**Fix:** Consolidate into SyncService only.

### QA-H3: Test Helpers Duplicated Across Test Files
**Files:** `AuthTest.kt`, `SyncTest.kt`, `IntegrationTest.kt`, `E2ETest.kt`
**Risk:** Registration/login/family-creation helpers copy-pasted across 4 test files. Changes require updating all copies.
**Fix:** Extract `TestHelper` object with shared `registerUser()`, `loginUser()`, `createFamily()` functions.

### SEC-H4: No Blob Content-Type Validation
**File:** `routes/BlobRoutes.kt`
**Risk:** Any file type can be uploaded as a blob. No size limit enforcement on individual blobs.
**Fix:** Validate content-type against allowlist, enforce max blob size.

---

## Medium Issues

| ID | File | Issue |
|----|------|-------|
| SEC-M1 | `services/AuthService.kt` | Password policy is length-only (>=8). No complexity or breach-list check |
| QA-M1 | `db/Tables.kt` | `RefreshTokens.tokenHash` not indexed. Token lookup is full table scan |
| QA-M2 | `routes/SyncRoutes.kt` | Snapshot `version` column is Int, should be Long for future-proofing |
| QA-M3 | Multiple routes | WebSocket send failures and push notification failures silently swallowed |
| QA-M4 | `routes/SyncRoutes.kt` | No early input validation before service calls (e.g., empty payload) |
| SEC-M2 | `services/AuthService.kt` | TOTP has no time window tolerance (no skew allowance for clock drift) |
| ARCH-M1 | `db/Tables.kt` | SQLite `autoIncrement()` not portable to PostgreSQL migration path |

---

## Architecture Observations

### No Database Migration Versioning
**Files:** `db/DatabaseFactory.kt`, `db/Tables.kt`
**Risk:** `SchemaUtils.create()` only creates missing tables. No support for column additions, renames, or type changes. First schema change in production will require manual migration.
**Recommendation:** Adopt Flyway or Liquibase before first production deployment.

### Rate Limit: Snapshot Too Aggressive
**File:** `plugins/RateLimit.kt`
**Risk:** Snapshot rate limit of 1 per 60 minutes is too aggressive. If a snapshot upload fails, user must wait an hour to retry.
**Recommendation:** Increase to 5-10 per hour, or use a shorter window.

### WebSocket In-Memory Only
**File:** `routes/SyncRoutes.kt`
**Risk:** WebSocket connections stored in-memory map. Not compatible with horizontal scaling.
**Recommendation:** Document single-instance limitation. For multi-instance, use Redis pub/sub or similar.

### No Correlation IDs
**Files:** All route handlers
**Risk:** No request correlation IDs for tracing requests across logs. Debugging production issues will be difficult.
**Recommendation:** Add correlation ID middleware (Ktor CallId plugin).

### No Graceful Shutdown
**File:** `Application.kt`
**Risk:** No shutdown hook to drain active connections, flush pending WebSocket messages, or complete in-flight database transactions.
**Recommendation:** Add `ShutDownUrl` or custom shutdown hook with connection draining.

---

## Positive Findings

- **Hash Chain Validation:** Thorough server-side hash chain verification with correct formula
- **JWT Implementation:** Proper access/refresh token separation with configurable expiration
- **Rate Limiting:** Per-endpoint rate limits with sensible defaults
- **Test Coverage:** 44 tests covering auth, sync, hash chains, integration, E2E, and override state machine
- **StatusPages:** Clean error handling with typed ApiException → ErrorResponse mapping
- **Exposed ORM:** Good use of DSL queries with explicit database reference (fixed in this session)
- **SQLite WAL:** Correct WAL mode configuration for concurrent read/write
- **Bcrypt:** Proper password hashing with BCrypt
- **TOTP:** Standard RFC 6238 TOTP implementation

---

## Recommended Fix Priority

1. **Immediate:** QA-C1 (uploadOps rollback bug) — data integrity risk
2. **Immediate:** SEC-C1 (CORS) + QA-C3 (health endpoint) — low effort, high impact
3. **Short-term:** QA-C2 (service layer extraction) — improves testability
4. **Pre-beta:** SEC-H1 (WebSocket error leak) + SEC-H2 (account lockout) + SEC-H4 (blob validation)
5. **Pre-production:** Database migration tooling, graceful shutdown, correlation IDs
