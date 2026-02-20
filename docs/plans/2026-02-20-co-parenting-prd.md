# Co-Parenting App - Product Requirements Document (PRD)

**Date:** 2026-02-20
**Version:** Final (3 review cycles completed)
**Status:** Approved
**Reviewed by:** Gemini 3 Pro (3 cycles), Codex (3 cycles) -- all critical/high issues resolved

## 1. Problem Statement

Separated and divorced parents need a neutral, functional tool to coordinate custody schedules and shared expenses for their children. Existing solutions are expensive ($140-144/year), closed-source, and monetize sensitive family data. Many parents resort to fragmented communication across WhatsApp, email, and spreadsheets, leading to miscommunication and conflict.

## 2. Product Goals

1. **Reduce conflict**: No messaging. Structured data exchange only (calendar, expenses).
2. **Protect privacy**: E2E encryption, local-first storage, self-hostable server.
3. **Lower barriers**: Free and open source. No subscription, no ads, no data selling.
4. **Enable coordination**: Pattern-based custody calendar with swap requests and expense tracking.

## 3. Target Users

### Primary: Separated/Divorced Parents

- **Demographics**: Adults 25-55, co-parenting 1-4 children
- **Tech comfort**: Average smartphone users (not developers)
- **Emotional state**: Varies from amicable to high-conflict
- **Legal context**: May have court-ordered custody arrangements

### Secondary: Extended Family (v0.3+)

- Grandparents who want to see their grandchildren's schedule
- Caregivers/nannies who need to know pickup/dropoff logistics
- Older children (13+) who want to see their own schedule

## 4. User Stories

### Custody Calendar

| ID | Story | Priority |
|----|-------|----------|
| US-C01 | As a parent, I want to set up a recurring custody pattern (e.g., week-on/week-off) so I always know whose week it is | P0 |
| US-C02 | As a parent, I want to request a schedule swap for specific dates so I can handle exceptions | P0 |
| US-C03 | As a parent, I want to approve or decline swap requests from my co-parent | P0 |
| US-C04 | As a parent, I want to see a monthly calendar view showing which parent has each child on each day | P0 |
| US-C05 | As a parent, I want to add events/appointments for my children (doctor, school events) | P0 |
| US-C06 | As a parent, I want to see pending swap requests clearly distinguished from confirmed schedule | P0 |
| US-C07 | As a parent, I want my custody schedule to sync to my phone's system calendar (one-way export) | P1 |
| US-C08 | As a parent, I want to add scoped notes to swap requests (reason for requesting) | P1 |
| US-C09 | As a child (13+), I want to see my own custody schedule on my phone | v0.3 |
| US-C10 | As a parent, I want to mark holidays/school breaks with special custody rules | v0.2 |
| US-C11 | As a parent, I want to import/export my custody calendar to Google Calendar | v1.0 |

### Expense Tracking

| ID | Story | Priority |
|----|-------|----------|
| US-E01 | As a parent, I want to log a child-related expense with amount, category, and date | P0 |
| US-E02 | As a parent, I want to attach a receipt photo to an expense (stored as encrypted blob, not in OpLog) | P0 |
| US-E03 | As a parent, I want to see a running balance of who owes whom | P0 |
| US-E04 | As a parent, I want to categorize expenses (medical, education, clothing, etc.) | P0 |
| US-E05 | As a parent, I want to set the default expense split ratio (e.g., 50/50 or 60/40) | P0 |
| US-E06 | As a parent, I want to acknowledge or dispute an expense logged by my co-parent | P0 |
| US-E07 | As a parent, I want to see expense history filtered by category, date range, or child | P1 |
| US-E08 | As a parent, I want to export expense reports as PDF/CSV | v0.2 |

### Account & Security

| ID | Story | Priority |
|----|-------|----------|
| US-A01 | As a parent, I want to create an account with email and password | P0 |
| US-A02 | As a parent, I want to invite my co-parent to join the family | P0 |
| US-A03 | As a parent, I want to set up 2FA (TOTP) for my account | P0 |
| US-A04 | As a parent, I want to generate and securely store a recovery key (high-entropy backup key in PDF) at onboarding | P0 |
| US-A05 | As a parent, I want to recover my account using my recovery key if I lose my device | P0 |
| US-A06 | As a parent, I want to add/remove devices from my account and trigger key rotation on removal | P1 |
| US-A07 | As a parent, I want to revoke a compromised device (revokes API access + triggers key rotation; device retains historical data already downloaded) | P1 |
| US-A08 | As a parent, I want to invite a grandparent with read-only access | v0.3 |

### Sync & Offline

| ID | Story | Priority |
|----|-------|----------|
| US-S01 | As a parent, I want my changes to sync to my co-parent's device automatically | P0 |
| US-S02 | As a parent, I want to create calendar entries and log expenses while offline, syncing when reconnected | P0 |
| US-S03 | As a parent, I want to see sync status (synced/pending/failed) on each item | P1 |
| US-S04 | As a parent, I want to self-host the sync server for maximum privacy | P1 |
| US-S05 | As a parent, I want to recover from a sync error (re-download state from server) without losing my account | P1 |

## 5. Functional Requirements

### FR-01: Custody Pattern Engine

- Support common patterns: week-on/week-off, 2-2-3, 2-2-5-5, alternating weekends, custom
- Pattern defined as: anchor date + cycle length + parent assignment per day
- Multiple children can have different patterns
- Pattern changes create new schedule (old pattern preserved for history)
- Calendar view generated from rules as source of truth
- Materialized assignment projection with rolling horizon: always covers at least 12 months ahead of current view. When user navigates to month M, projection auto-extends to M+12 if needed. Deterministically rebuilt on any rule change
- All dates stored with explicit timezone (ZoneId); all OpLog timestamps in UTC

### FR-02: Schedule Override System

Overrides cover all non-base-pattern assignments. Types:

| Override Type | Description | Who Can Create |
|---------------|-------------|----------------|
| **SwapRequest** | Parent proposes date swap, other approves/declines | Either parent |
| **HolidayRule** | Recurring holiday assignment (e.g., Christmas with Mom in even years) | Either parent (requires approval) |
| **CourtOrder** | Court-mandated schedule change, imported from legal document | Either parent (flagged as court-ordered) |
| **ManualOverride** | One-off exception (e.g., sick day, special event) | Either parent (requires approval) |

**Precedence**: CourtOrder > HolidayRule > ApprovedSwap > ManualOverride > BasePattern

**State machine** for approval-requiring overrides:

```
                    ┌─── Approved
Proposed ───┬───────┤
            │       └─── Declined
            │
            └─── Cancelled (by proposer before response)

Approved ───┬─── Superseded (by higher-precedence override, e.g., CourtOrder)
            └─── Expired (past date range, auto-transition)

All terminal states are immutable (preserved for audit history).
```

**Transition authority**: Only the non-proposing parent can approve/decline. Only the proposer can cancel. Superseded/Expired are system-triggered.

### FR-03: Expense Management

- Log expenses with: amount, currency, category, description, date
- Receipt photos stored as encrypted blobs in separate storage, referenced by ID in OpLog
- Default split ratio configurable (e.g., 50/50, 60/40)
- Per-expense split ratio override possible
- Running balance calculation showing net owed
- Status workflow: Logged -> Acknowledged/Disputed
- Categories: Medical, Education, Clothing, Activities, Food, Transport, Childcare, Other

### FR-04: Sync Protocol

- Append-only operation log with server-assigned monotonic sequence numbers
- E2E encrypted payloads (server never sees plaintext)
- **Hash chaining model**: Per-device hash chains (each device maintains its own chain of ops). Server assigns global monotonic sequence numbers and publishes periodic checkpoint hashes. Clients verify: (a) their own device chain is intact, (b) server checkpoint matches deterministic replay of all ops up to that sequence number. This handles concurrent offline writes without requiring a single global chain.
- Tamper detection: each op includes `SHA256(devicePrevHash + currentPayload)` plus server-assigned global sequence
- Snapshot mechanism for new device bootstrapping (~every 500 ops)
- Deterministic conflict resolution per entity type (see design doc for rules)
- Offline-capable: mutations applied locally, queued for sync when online
- Protocol versioning: sync handshake includes `protocol_version` and `app_version`; incompatible versions require app update before sync
- Conformance test vectors: canonical set of ops + expected final state, mandatory for all client implementations

**Encrypted Blob Storage** (for receipts, documents):
- Binary assets stored separately from OpLog
- OpLog entry contains blob reference ID + per-blob encryption key
- Server stores encrypted blobs, indexed by reference ID
- Blobs downloaded on demand (not during initial sync)

**Push Notifications**:
- Server sends generic signals ("new data available at seq #N") since it cannot read content
- Client pulls, decrypts, and generates local rich notifications

### FR-05: Encryption & Key Management

- Per-family symmetric DEK (Data Encryption Key) with envelope encryption
- DEK wrapped per-device with device public keys
- Hardware-backed key storage (Android Keystore / iOS Secure Enclave)
- **Recovery**: High-entropy recovery key generated at onboarding, displayed as PDF with QR code. User must confirm they saved it.
- Key rotation on device revocation: new DEK generated, re-wrapped for remaining trusted devices
- Key epochs: each DEK version has a monotonic epoch number; OpLog entries reference their key epoch
- Note: Threshold recovery (2-of-3 shares) deferred to v0.3+ due to implementation complexity

### FR-06: Authentication & Authorization

- Email/password + TOTP 2FA
- Breached-password check at registration (HaveIBeenPwned k-anonymity API)
- Rate limiting on auth endpoints
- Device management (add/remove/revoke)
- Invite flow with out-of-band fingerprint verification
- **MVP roles**: Parent (full access), no other roles in v0.1
- **MVP RBAC**: UI-enforced only (single family encryption key). Cryptographic role separation deferred to v0.3 when Family Circle introduces per-module encryption keys
- Child/Grandparent/Caregiver roles added in v0.3

## 6. Non-Functional Requirements

| Requirement | Target | Notes |
|-------------|--------|-------|
| Offline capability | See capability matrix below | Not all features work offline |
| Sync latency | < 5 seconds when both devices online | Measured from op creation to delivery |
| Cold start | < 2 seconds to usable calendar view | Using materialized projection |
| Data at rest | Encrypted (SQLCipher / encrypted CoreData) | |
| Data in transit | E2E encrypted + TLS | |
| Accessibility | WCAG 2.1 AA compliance | |
| Localization | English + German initially, i18n-ready | |
| Min Android | SDK 26 (Android 8.0) | |
| Min iOS | iOS 16 | From v0.4 |
| Server resources | See sizing tiers below | |

### Offline Capability Matrix

| Capability | Offline | Online Required |
|------------|---------|-----------------|
| View calendar | Yes | |
| Create/edit events | Yes (queued) | |
| View expenses | Yes | |
| Log new expense | Yes (queued) | |
| View swap requests | Yes | |
| Create swap request | Yes (queued) | |
| Approve/decline swap | Yes (queued) | |
| Account creation | | Yes |
| Device enrollment | | Yes |
| Co-parent invite | | Yes |
| Initial sync | | Yes |
| Receipt photo upload | | Yes (queued locally) |

### Server Sizing Tiers

| Tier | Families | Resources | Database |
|------|----------|-----------|----------|
| Self-host (single family) | 1 | 1 vCPU, 512MB RAM | SQLite/WAL |
| Self-host (small) | 1-10 | 1 vCPU, 512MB RAM | SQLite/WAL |
| Managed (community) | 10-1000 | 2 vCPU, 2GB RAM | PostgreSQL |
| Managed (scale) | 1000+ | 4 vCPU, 4GB RAM | PostgreSQL |

## 7. Out of Scope (MVP)

- General messaging / chat
- Photo galleries
- Task management / chore charts
- GPS tracking / location sharing
- AI features (tone detection, suggestions)
- Web client
- Integration with legal/court systems
- Payment processing (settling balances)
- Threshold recovery (2-of-3 key shares)
- Cryptographic role separation (per-module keys)

## 8. Success Metrics

### Operational Metrics

| Metric | Target (6 months post-launch) |
|--------|-------------------------------|
| GitHub stars | 500+ |
| Active families | 100+ |
| App store rating | 4.0+ |
| Sync reliability | 99.9% (measured via opt-in anonymous telemetry) |
| Crash-free rate | 99.5% |

### Product Outcome Metrics

| Metric | Target | Rationale |
|--------|--------|-----------|
| Swap resolution time | < 24 hours median | Measures co-parent responsiveness |
| Disputed expense rate | < 10% | Low disputes = clear expense logging |
| Retained co-parent pairs (30-day) | > 60% | Both parents must stay for app to work |
| Calendar-change conflict rate | < 5% | Low conflicts = good pattern design |
| Support incidents per family/month | < 0.5 | App should be self-explanatory |

## 9. Release Plan

| Version | Scope | Milestone |
|---------|-------|-----------|
| v0.1 (MVP) | Calendar + Expenses (Android, Parents only) | M1 |
| v0.2 | Info Bank + Reminders + Holiday Rules + Expense Export | M2 |
| v0.3 | Family Circle (child/grandparent/caregiver roles, per-module encryption keys) | M3 |
| v0.4 | iOS app | M4 |
| v1.0 | Audit trail, court-ready export, document vault, calendar integrations | M5 |

## 10. Delivery Spec (Required Before Sprint Planning)

- [ ] Protocol specification with wire format, ordering rules, timezone semantics, tie-breakers
- [ ] Conformance test vectors (canonical ops + expected final state)
- [ ] API contract (OpenAPI spec for server endpoints)
- [ ] Data migration/backfill strategy for version upgrades
- [ ] Observability: anonymous opt-in telemetry spec (error codes only, no payload data)
- [ ] Security test plan (penetration testing scope, threat model)
- [ ] Capacity test plan: benchmark thresholds per server sizing tier
- [ ] Acceptance criteria per user story (before each sprint)

## 11. Open Questions

1. App name: "KidSync" (working title) - needs trademark check
2. Managed server hosting: who operates it? Community foundation?
3. Court-ready export: what format/standard do family courts accept?
4. Internationalization priority: which languages after EN/DE?
5. Anonymous telemetry: which provider? Self-hosted PostHog?
