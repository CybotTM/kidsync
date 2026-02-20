# Co-Parenting App - Implementation Plan

**Date:** 2026-02-20
**Version:** Final (3 review cycles completed)
**Status:** Approved
**Reviewed by:** Gemini 3 Pro (3 cycles), Codex (3 cycles) -- all blockers resolved
**Prerequisites:** PRD (approved), Design Doc (approved)

## Build Order (Revised)

Phases 2 and 3 run **in parallel** after Phase 1 (protocol freeze). An **interoperability gate** separates core engine from UI work. Calendar and Expense UI run in parallel.

```
Phase 1: Protocol Spec & Shared Contracts          [~2 weeks]
    │
    ├──► Phase 2: Sync Server (parallel)            [~3 weeks]
    │
    └──► Phase 3: Android Core (DB + Crypto + Auth  [~4 weeks]
         + Sync Engine + Custody Engine)
              │
              ▼
         Gate A: Interoperability Gate               [~1 week]
         (Android <-> Server conformance suite green)
              │
    ┌─────────┴─────────┐
    │                   │
    ▼                   ▼
Phase 4: Auth &     Phase 5: Calendar    Phase 6: Expense
Onboarding UI       Module (UI)          Module (UI)
[~2 weeks]          [~3 weeks]           [~2 weeks]
    │                   │                    │
    └─────────┬─────────┴────────────────────┘
              ▼
         Gate B: Security & Compliance Hardening     [~1 week]
              │
              ▼
         Phase 7: Integration Testing & Beta         [~2 weeks]
              │
              ▼
         Phase 8: Play Store Launch (v0.1)           [~1 week]
```

**Validation Gates:**
- **Gate P0 (Protocol Freeze)**: All Phase 1 artifacts approved and version-tagged (`wire-format.md`, `sync-protocol.md`, `encryption-spec.md`, `openapi.yaml`, conformance vectors). No Phase 2/3 coding begins until P0 passes. Artifacts locked; changes require versioned amendment.
- **Gate A (Interoperability)**: Android-server conformance suite passes (key rotation, recovery, sync, 100 ops/min sustained under 512MB)
- **Gate B (Security)**: Threat model reviewed, security test suite passes, retention/deletion policy implemented
- **Gate C (Beta SLOs)**: Crash-free rate > 99.5%, sync latency < 5s, cold start < 2s, capacity validated at 1000 ops/min for managed tier

---

## Phase 1: Protocol Spec & Shared Contracts

**Goal**: Define the immutable protocol that all clients and the server must implement.

### 1.1 Wire Format Specification

- [ ] Define JSON schema for `OpLogEntry` (encrypted envelope)
- [ ] Define JSON schema for all `OperationPayload` variants (decrypted business data)
- [ ] Define binary blob upload/download protocol (multipart encrypted upload, reference-based retrieval)
- [ ] Document serialization rules: field ordering, null handling, date/time formats (ISO 8601, UTC for timestamps, IANA timezone IDs for local dates)
- [ ] Choose serialization library: kotlinx.serialization (Android/Server), Codable (iOS)
- [ ] Exclude InfoBankEntry op types from v0.1 protocol (deferred to v0.2)

**Output**: `docs/protocol/wire-format.md`

### 1.2 Sync Protocol Specification

- [ ] Define client-server handshake (protocol_version, app_version negotiation)
- [ ] Define REST endpoints for sync: `POST /sync/ops` (upload), `GET /sync/ops?since={seq}` (pull), `POST /sync/snapshot` (upload snapshot), `GET /sync/snapshot/latest` (bootstrap)
- [ ] Define WebSocket protocol for real-time push (generic signals only)
- [ ] Define per-device hash chain rules: `SHA256(devicePrevHash + currentPayloadBytes)`
- [ ] Define server checkpoint hash: periodic `SHA256(concat(all_ops[startSeq..endSeq]))` published every 100 ops
- [ ] Define canonical linearization rules for concurrent offline ops from multiple devices
- [ ] Define conflict resolution rules per entity type:
  - CustodySchedule: latest `effectiveFrom` wins; tie-break by `timestamp`, then `deviceId` lexicographic
  - ScheduleOverride: state machine transitions validated by both client AND server (server rejects invalid transitions based on metadata)
  - Expense: append-only (no conflicts)
- [ ] Define deterministic tie-break rules for all conflict scenarios
- [ ] Define snapshot format: complete state at sequence N, signed by creating device
- [ ] Define error handling: hash chain break recovery, protocol mismatch, unknown op type, decryption failure
- [ ] Define protocol version compatibility matrix (N and N-1 support)

**Output**: `docs/protocol/sync-protocol.md`

### 1.3 Encryption Specification

- [ ] Define key hierarchy: Family DEK -> wrapped per-device with device public key
- [ ] Define unified crypto profile: X25519 for key agreement, AES-256-GCM for payloads (resolve P-256 vs X25519 inconsistency: use X25519 throughout for key exchange, store X25519 keys in Android Keystore via Tink library)
- [ ] Define DEK rotation protocol: new epoch, re-wrap for trusted devices, old epoch retained for historical decryption
- [ ] Define recovery key format: 256-bit entropy, BIP39 mnemonic or base58 encoding
- [ ] Define device enrollment flow: invite link -> fingerprint verification -> DEK delivery
- [ ] Define device revocation flow: revoke API token, trigger DEK rotation, broadcast new epoch
- [ ] Define exact key formats, wrapping envelope, and fallback behavior per platform

**Output**: `docs/protocol/encryption-spec.md`

### 1.4 Conformance Test Vectors

- [ ] Test vector set 1: Single device creates custody schedule, 3 expenses, 1 swap request -> expected final state
- [ ] Test vector set 2: Two devices create concurrent offline ops -> merge -> expected final state (deterministic)
- [ ] Test vector set 3: Hash chain verification (valid chain, tampered entry detection, per-device chain branching)
- [ ] Test vector set 4: Key rotation during active sync -> all ops decryptable with correct epoch key
- [ ] Test vector set 5: Override state machine transitions (all valid/invalid paths, including Superseded/Expired)
- [ ] Test vector set 6: Clock skew scenarios (device clocks differ by hours)
- [ ] Package as JSON files importable by Android unit tests and iOS test suites

**Output**: `tests/conformance/` directory with JSON test vectors

### 1.5 API Contract

- [ ] Write OpenAPI 3.1 spec for sync server REST API
- [ ] Define all endpoints: auth, sync, blob storage, device management, push notification registration
- [ ] Define error response format and error codes
- [ ] Define rate limiting headers and behavior
- [ ] Set up contract test framework (server must pass against OpenAPI spec)

**Output**: `docs/api/openapi.yaml`

### 1.6 Mock Server for Parallel Development

- [ ] Generate mock server from `openapi.yaml` (Prism or simple Ktor mock) for Android team to test against while real server is in development

---

## Gate P0: Protocol Freeze

**All Phase 1 outputs must be complete and approved before Phases 2/3 begin:**

- [ ] `docs/protocol/wire-format.md` - reviewed and version-tagged
- [ ] `docs/protocol/sync-protocol.md` - reviewed and version-tagged
- [ ] `docs/protocol/encryption-spec.md` - reviewed and version-tagged
- [ ] `docs/api/openapi.yaml` - linted and version-tagged
- [ ] `tests/conformance/` - all test vector sets present and schema-validated
- [ ] Protocol version set to `1` and locked

---

## Phase 2: Sync Server (Kotlin/Ktor) [parallel with Phase 3]

**Goal**: Build the "dumb relay" server.

### 2.1 Project Setup

- [ ] Initialize Kotlin/Ktor project with Gradle
- [ ] Set up dependencies: Ktor (Netty engine), Exposed (ORM), SQLite JDBC (WAL mode, busy-timeout configured), kotlinx.serialization
- [ ] Set up Docker build (multi-stage: build with JDK, run with JRE)
- [ ] Set up CI pipeline (GitHub Actions): build, test, contract test vs OpenAPI, Docker publish
- [ ] Configure logging (SLF4J + Logback), structured JSON output
- [ ] Configure SQLite WAL mode, busy-timeout, and backpressure/queueing for concurrent writes

### 2.2 Database Schema

- [ ] `users` table: id, email (normalized + indexed), password_hash (bcrypt, separate from email), totp_secret (encrypted), created_at
- [ ] `devices` table: id, user_id, public_key, device_name, created_at, revoked_at
- [ ] `families` table: id, name, created_at
- [ ] `family_members` table: user_id, family_id, role, joined_at
- [ ] `op_log` table: global_sequence (auto-increment), device_id, encrypted_payload, device_prev_hash, server_timestamp
- [ ] `blobs` table: id, file_path, size_bytes, sha256_hash, uploaded_by, uploaded_at
- [ ] `push_tokens` table: device_id, token, platform (FCM/APNs), updated_at
- [ ] Write Exposed table definitions
- [ ] Implement migration strategy with rollback support
- [ ] Write migration rehearsal tests

### 2.3 Auth Endpoints

- [ ] `POST /auth/register` - email + password (bcrypt), enforce breached-password check (HaveIBeenPwned k-anonymity)
- [ ] `POST /auth/login` - returns short-lived JWT + refresh token
- [ ] `POST /auth/totp/setup` - generate TOTP secret, return QR code URI
- [ ] `POST /auth/totp/verify` - verify TOTP code
- [ ] `POST /auth/refresh` - refresh JWT with refresh token
- [ ] Implement rate limiting middleware (sliding window, per-IP + per-account)
- [ ] Implement brute-force detection and temporary lockout

### 2.4 Family & Device Management

- [ ] `POST /families` - create family
- [ ] `POST /families/{id}/invite` - generate invite token (expires in 48h)
- [ ] `POST /families/{id}/join` - accept invite, upload device public key
- [ ] `GET /families/{id}/members` - list members with public keys
- [ ] `POST /devices` - register new device (with public key)
- [ ] `DELETE /devices/{id}` - revoke device (marks revoked_at, signals key rotation)

### 2.5 Sync Endpoints

- [ ] `POST /sync/ops` - upload encrypted op(s), assign global sequence numbers, validate override state machine transitions on metadata
- [ ] `GET /sync/ops?since={seq}&limit={n}` - pull ops since sequence number (paginated)
- [ ] `POST /sync/snapshot` - upload snapshot blob
- [ ] `GET /sync/snapshot/latest` - get latest snapshot metadata + download URL
- [ ] `GET /sync/checkpoint` - get latest server checkpoint hash + sequence range
- [ ] Implement WebSocket endpoint `/sync/ws` - push "new ops available" signal

### 2.6 Blob Storage Endpoints

- [ ] `POST /blobs` - upload encrypted blob (multipart, max 10MB)
- [ ] `GET /blobs/{id}` - download encrypted blob
- [ ] `DELETE /blobs/{id}` - soft-delete blob (owner only)
- [ ] Implement storage backend: filesystem (default) with configurable S3-compatible option

### 2.7 Push Notifications

- [ ] `POST /push/register` - register FCM token for device
- [ ] Implement notification dispatch: on new op received, send generic "data available" signal to family devices
- [ ] FCM integration (Android first, APNs deferred to v0.4)

### 2.8 Server Testing

- [ ] Unit tests for all business logic (auth, sequence assignment, membership, override validation)
- [ ] Contract tests: verify all endpoints match OpenAPI spec
- [ ] Integration tests with in-memory SQLite
- [ ] Load test (Gate A target): 1 family, 4 devices, 100 ops/minute sustained under 512MB
- [ ] Load test (Gate C target): 10 families, 20 devices, 1000 ops/minute for managed tier (2GB+)
- [ ] Memory profiling: ensure JVM fits within 512MB with Ktor + SQLite + Exposed under Gate A load
- [ ] Docker Compose smoke test: start server, health check, create family, sync ops
- [ ] Abuse/rate-limit tests for auth and invite endpoints
- [ ] Backup/restore verification (SQLite file copy + restore + consistency check)

---

## Phase 3: Android Core (DB + Crypto + Auth + Sync) [parallel with Phase 2]

**Goal**: Build the complete headless backend on Android: local database, encryption, auth logic, sync engine, and custody engine. No UI yet -- all testing via instrumented tests and CLI harness.

### 3.1 Project Setup

- [ ] Initialize Android project (Kotlin, Gradle, min SDK 26)
- [ ] Set up dependencies: Jetpack Compose, Room + SQLCipher, Retrofit + OkHttp, kotlinx.serialization, Tink (for X25519)
- [ ] Set up architecture: Clean Architecture (data/domain/presentation layers)
- [ ] Set up DI: Hilt
- [ ] Set up CI: GitHub Actions (build, lint, test)

### 3.2 Local Database (Room + SQLCipher)

- [ ] Define Room entities: Family, FamilyMember, CustodySchedule, ScheduleOverride, Expense, OpLogEntry (local), SyncState, KeyEpoch
- [ ] Define DAOs: current schedule, overrides by date range, expenses by category/date, pending ops, sync state
- [ ] Integrate SQLCipher for at-rest encryption
- [ ] Write database migration strategy (Room auto-migrations + manual for complex changes)
- [ ] Write migration rehearsal tests (create DB v1, migrate to v2, verify data integrity)

### 3.3 Encryption Module

- [ ] Implement X25519 key pair generation/storage via Tink + Android Keystore
- [ ] Implement AES-256-GCM encrypt/decrypt for OpLog payloads
- [ ] Implement DEK generation, wrapping (envelope encryption), and epoch management
- [ ] Implement recovery key generation (BIP39 mnemonic)
- [ ] Implement device enrollment crypto: unwrap DEK using device private key
- [ ] Implement key rotation: generate new DEK, re-wrap for trusted devices, update epoch
- [ ] Write unit tests with all conformance test vectors from Phase 1

### 3.4 Auth Logic (Headless)

- [ ] Implement `AuthRepository`: register, login, JWT storage, token refresh
- [ ] Implement session management: auto-refresh expired tokens, handle 401s
- [ ] Implement device registration: generate key pair, upload public key to server
- [ ] Implement invite flow logic: accept invite, fingerprint verification, receive wrapped DEK
- [ ] Implement recovery flow logic: enter recovery key, regenerate device key pair, re-enroll
- [ ] Write integration tests against real server (Phase 2) or mock server

### 3.5 Sync Engine

- [ ] Implement operation creation: business logic -> OperationPayload -> encrypt -> OpLogEntry -> queue
- [ ] Implement sync loop: pull ops since last sequence -> decrypt -> validate hash chain -> apply to Room
- [ ] Implement conflict resolution per entity type (deterministic rules from protocol spec)
- [ ] Implement per-device hash chain verification on incoming ops
- [ ] Implement server checkpoint verification: replay ops and compare hash
- [ ] Implement snapshot creation: serialize DB state -> encrypt -> upload
- [ ] Implement snapshot restoration: download -> decrypt -> apply (new device bootstrap)
- [ ] Implement WorkManager scheduled sync (periodic, configurable interval)
- [ ] Implement connectivity-aware sync: sync immediately on reconnect
- [ ] Implement sync error handling: decryption failure recovery, hash chain break recovery, force re-download
- [ ] Write integration tests: 2 simulated devices exchanging ops via server

### 3.6 Blob Manager

- [ ] Implement BlobRepository: queue encrypted blob uploads, retry on failure
- [ ] Implement blob download on demand (for receipt images)
- [ ] Implement blob encryption/decryption (per-blob key in OpLog entry)
- [ ] Implement upload resume for large files
- [ ] Write tests for blob lifecycle: upload, reference, download, orphan detection

### 3.7 Custody Pattern Engine

- [ ] Implement pattern generator: CustodySchedule + date -> assigned parent
- [ ] Implement override resolution: apply layered precedence (CourtOrder > Holiday > Swap > Manual > Base)
- [ ] Implement materialized projection: generate assignments for rolling 12-month horizon
- [ ] Implement rolling horizon extension: auto-extend when user navigates beyond window
- [ ] Implement override state machine: validate transitions, enforce authority rules
- [ ] Write unit tests: all pattern types (week-on/week-off, 2-2-3, 2-2-5-5, alternating weekends)
- [ ] Write property-based tests (Kotest) for pattern generator determinism
- [ ] Write edge case tests: DST boundaries, leap years, year boundaries, timezone transitions

### 3.8 Debug & Dev Tools

- [ ] Implement CLI test harness: auth -> key gen -> sync upload -> sync download (headless verification)
- [ ] Implement in-app debug panel (hidden in settings): view OpLog, force sync, nuke DB, view key epochs, sync state
- [ ] Implement correlation IDs for sync operations (for observability)

---

## Gate A: Interoperability Gate (~1 week)

**Must pass before starting UI phases:**

- [ ] Android client successfully authenticates with server
- [ ] Android client creates family and invites second device
- [ ] Two Android devices exchange encrypted ops via server and arrive at identical state
- [ ] Key rotation works: revoke device, new DEK, remaining device re-syncs
- [ ] Recovery flow works: recovery key restores account on fresh device
- [ ] Conformance test vectors pass on both Android client and server
- [ ] Hash chain tamper detection works: modified op is detected
- [ ] Snapshot bootstrap works: new device catches up from snapshot + recent ops
- [ ] Server stays under 512MB RAM under Gate A load (100 ops/minute sustained)

---

## Phase 4: Auth & Onboarding UI (~2 weeks)

**Goal**: Build the onboarding screens so the app is usable for testing Calendar/Expense UI.

### 4.1 Onboarding Flow

- [ ] Welcome screen with value proposition
- [ ] Account creation: email + password (strength indicator, breached-password warning)
- [ ] TOTP 2FA setup (QR code display for authenticator app)
- [ ] Recovery key generation: display mnemonic, force acknowledgment ("I saved this")
- [ ] Recovery key PDF export

### 4.2 Family Setup

- [ ] Create family: name, add children (name + date of birth)
- [ ] Invite co-parent: generate shareable invite link (deep link)
- [ ] Co-parent join flow: accept invite, verify fingerprints, receive DEK
- [ ] Family dashboard: members, children

### 4.3 Login & Settings

- [ ] Login screen: email + password + TOTP
- [ ] Device list view (settings)
- [ ] Revoke device + key rotation trigger
- [ ] Recovery flow: enter recovery key -> restore on new device
- [ ] Server URL configuration (for self-hosted)
- [ ] Notification preferences
- [ ] Default expense split ratio + currency
- [ ] Anonymous telemetry opt-in/out
- [ ] About/licenses screen
- [ ] Accessibility: screen reader labels, contrast ratios, touch targets (WCAG 2.1 AA)
- [ ] Localization: English + German string resources

---

## Phase 5: Calendar Module UI (~3 weeks) [parallel with Phase 6]

### 5.1 Calendar View

- [ ] Monthly calendar grid: color-coded parent assignments per day
- [ ] Day detail view: assigned parent, events, pending swap requests
- [ ] Today indicator and current parent highlight
- [ ] Child selector (multiple children)
- [ ] Accessibility: content descriptions for color-coded days

### 5.2 Schedule Setup Flow

- [ ] Pattern selection screen (visual picker for common patterns)
- [ ] Custom pattern builder (tap-to-assign days in cycle)
- [ ] Anchor date picker
- [ ] Timezone selection
- [ ] Review & confirm screen

### 5.3 Swap Request Flow

- [ ] "Request swap" from calendar day/range selection
- [ ] Swap request form: date range, reason (optional note)
- [ ] Pending swap indicators on calendar (visual differentiation)
- [ ] Swap approval/decline screen
- [ ] Push notification integration

### 5.4 Events/Appointments

- [ ] Add event to date: title, time, location, notes
- [ ] Event indicators on calendar
- [ ] Event detail, edit, cancel

### 5.5 System Calendar Sync (P1)

- [ ] One-way export to Android system calendar via CalendarProvider API
- [ ] Configurable: auto-sync on/off

---

## Phase 6: Expense Module UI (~2 weeks) [parallel with Phase 5]

### 6.1 Expense Entry

- [ ] Quick add expense: amount, category (icon grid), description, date
- [ ] Receipt photo capture (camera) or gallery pick
- [ ] Receipt encryption and blob upload (with progress indicator)
- [ ] Split ratio display and per-expense override
- [ ] Currency selector

### 6.2 Expense List & Balance

- [ ] Expense list with filters: category, date range, child, status
- [ ] Running balance widget ("You owe $X" / "They owe you $X")
- [ ] Expense detail view with receipt image (downloaded on demand)
- [ ] Acknowledge/dispute actions
- [ ] Accessibility: all interactive elements labeled

### 6.3 Expense Summary

- [ ] Monthly/yearly summary by category (bar chart)
- [ ] Per-child expense breakdown

---

## Gate B: Security & Compliance Hardening (~1 week)

- [ ] Execute threat model review against OWASP Mobile Top 10
- [ ] Run security test suite: token replay, invite abuse, brute-force, key misuse
- [ ] Verify retention/deletion policy: soft-delete only, no hard delete in sync
- [ ] Verify account deletion semantics: user data removal from server, local data retained for court evidence
- [ ] Verify rate limiting works under abuse simulation
- [ ] Review all API endpoints for injection, auth bypass, IDOR
- [ ] Verify E2E: server admin cannot read any family data (decrypt attempt should fail)

---

## Phase 7: Integration Testing & Beta

### 7.1 E2E Test Suite

- [ ] Full lifecycle: create account -> family -> invite -> schedule -> swap -> expense -> acknowledge
- [ ] Offline/online transitions: create ops offline, reconnect, verify sync
- [ ] Multi-device: 2 phones, 1 tablet, verify consistent state
- [ ] Key rotation: revoke device, verify data integrity
- [ ] Snapshot bootstrap: new device catches up correctly
- [ ] Deterministic replay fuzz: random op sequences, verify all clients converge
- [ ] Transport chaos: duplicate sends, out-of-order delivery, partial message loss

### 7.2 Beta Testing

- [ ] Closed beta: 5-10 co-parent pairs (recruited via family mediation contacts)
- [ ] Set up crash reporting (self-hosted Sentry)
- [ ] Set up anonymous telemetry (opt-in, error codes only)
- [ ] Collect feedback: 2 rounds of UX improvements
- [ ] Verify: Play Store asset preparation in parallel (screenshots, description)

### 7.3 Performance & Capacity Validation

- [ ] Measure cold start time (target < 2s)
- [ ] Measure sync latency (target < 5s)
- [ ] Test with 2 years of simulated data (10,000+ ops)
- [ ] Server capacity test per sizing tier (512MB, 2GB, 4GB)
- [ ] Battery profiling: sync frequency impact on battery drain

---

## Phase 8: Play Store Launch (v0.1)

### 8.1 Launch Preparation

- [ ] Play Store listing: screenshots, description, feature graphic
- [ ] Privacy policy (GDPR-compliant, hosted on GitHub Pages)
- [ ] Open source repo: LICENSE (AGPLv3), README, CONTRIBUTING.md, AGENTS.md
- [ ] GitHub Discussions for community support
- [ ] App signing: Play App Signing
- [ ] Disaster recovery runbook: server backup/restore, key recovery procedures

### 8.2 Launch

- [ ] Submit to Play Store review
- [ ] Monitor crash reports and telemetry
- [ ] Community engagement

---

## Technical Debt & Future Work (Post v0.1)

| Item | Priority | Target |
|------|----------|--------|
| Blob garbage collection | Low | v0.2 |
| Protocol deprecation policy (N-1) | Medium | v0.2 |
| Battery optimization (WorkManager) | Medium | v0.1 patch |
| GraalVM Native Image for server | Low | v0.3 |
| PostgreSQL migration path | Medium | v0.3 |
| iOS app | High | v0.4 |
| Solo mode (single-parent logger) | High | v0.2 |
| Info Bank module | Medium | v0.2 |
| Web admin dashboard | Low | v1.0 |

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Cross-platform crypto divergence | High | Critical | Conformance vectors + unified crypto profile (X25519/AES-256-GCM) |
| One parent refuses to use app | High | High | Solo mode in v0.2 |
| Auth/sync integration failure | Medium | Critical | Gate A: interoperability gate before UI work |
| Play Store rejection | Medium | High | Parent-only MVP, GDPR privacy policy, no COPPA issues |
| OpLog unbounded growth | Medium | Medium | Snapshot mechanism + projection caching |
| Server compromise | Low | Critical | E2E encryption (server sees nothing useful) |
| JVM memory on self-hosted | Medium | Medium | 512MB minimum, memory profiling in Phase 2 |
| Custody engine edge cases | Medium | Medium | Property-based tests + DST/leap year vectors |
| SQLite write contention | Medium | Medium | WAL mode, busy-timeout, backpressure queue |
