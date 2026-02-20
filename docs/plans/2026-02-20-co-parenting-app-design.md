# Co-Parenting App - Design Document

**Date:** 2026-02-20
**Status:** Approved
**Review cycles:** 3 (Gemini 3 Pro + Codex)

## Vision

A free, open-source, privacy-first co-parenting coordination tool that helps separated families share custody schedules and child-related expenses without conflict. No messaging, no ads, no data selling -- just functional tools for parallel parenting.

## Target Users

| Role | Access Level |
|------|-------------|
| Parent (2) | Full read/write on calendar, expenses |
| Child (13+) | Read-only on own schedule |
| Grandparent | Read-only on schedule (v0.3+) |
| Caregiver | Limited read on assigned days + medical info (v0.3+) |

## Core Modules

### MVP (v0.1)

| Module | Description |
|--------|-------------|
| **Custody Calendar** | Pattern-based scheduling (week-on/week-off, 2-2-3, alternating weekends) with swap requests and approval flow |
| **Expense Tracker** | Log, categorize, split, and track child-related expenses with receipt photos |

### v0.2

| Module | Description |
|--------|-------------|
| **Info Bank** | Shared child records (medical, school, emergency contacts, sizes, insurance) |
| **Reminders** | Configurable notifications for handovers, expense deadlines, appointments |

### v0.3

| Module | Description |
|--------|-------------|
| **Family Circle** | Grandparent/caregiver roles with granular encryption keys |
| **Structured Notes** | Scoped comments on swap requests and expenses (not general messaging) |

### v1.0

| Module | Description |
|--------|-------------|
| **Audit Trail & Export** | Immutable event log, court-ready PDF/CSV export |
| **Handover Protocol** | Exchange checklist (location, belongings, medication) |
| **Document Vault** | Secure storage for custody agreements, medical records |
| **Calendar Integrations** | Import/export to Google Calendar, Apple Calendar, Outlook |
| **Migration Tools** | Import from OurFamilyWizard, 2Houses, Cozi |

## Architecture

### High-Level

```
┌──────────────┐          ┌──────────────┐
│   Android    │◄────────►│  Sync Server │◄────────►│    iOS       │
│  (Kotlin)    │  E2E     │ (Kotlin/Ktor)│  E2E     │  (Swift)    │
│  Room/SQLite │ encrypted│  SQLite/WAL  │ encrypted│  CoreData   │
│  Compose UI  │  blobs   │  Docker      │  blobs   │  SwiftUI    │
└──────────────┘          └──────────────┘          └──────────────┘
```

### Design Principles

1. **Local-first**: All data stored locally on device, app works fully offline
2. **E2E encrypted**: Server is a "dumb relay" -- stores only encrypted blobs + sequence numbers
3. **Append-only**: All changes are immutable operations in an event log
4. **Deterministic**: Conflict resolution is client-side, deterministic, and cross-platform compatible
5. **Self-hostable**: Server ships as a Docker image, any family can run their own

### Sync Protocol

- **Operation Log**: Every mutation is an immutable `OpLogEntry` with encrypted payload
- **Server role**: Assigns monotonic sequence numbers, stores/relays encrypted blobs
- **Client sync**: Pull ops since last sequence number, decrypt, apply to local DB
- **Hash chaining**: Each op includes `SHA256(prevHash + currentPayload)` for tamper detection
- **Snapshots**: Every ~500 ops, client creates a baseline state blob for new device bootstrapping

### Conflict Resolution (Per-Entity)

| Entity | Strategy | Rationale |
|--------|----------|-----------|
| CustodySchedule | Deterministic merge (latest effectiveFrom wins) | Only one pattern can be active |
| ScheduleOverride / Swap | State machine (pending -> approved/declined) | Both parents must agree |
| Expense | Append-only, no conflicts | Each expense is a distinct record |
| InfoBankEntry | Last-write-wins + version history | Low conflict probability |

### E2E Encryption

- **Envelope encryption**: Per-family symmetric DEK (Data Encryption Key)
- **DEK distribution**: Wrapped per-device with device public keys
- **Key storage**: Android Keystore (hardware-backed), iOS Secure Enclave
- **Recovery**: Threshold recovery (2-of-3 shares) + post-recovery key rotation
- **Key epochs**: On device revocation, generate new DEK, re-wrap for trusted devices
- **Large files**: Stored separately as encrypted blobs, OpLog contains metadata/pointers

### Authentication

- Email/password + TOTP 2FA
- No OAuth (avoid third-party data sharing)
- Device pairing via invite link + fingerprint verification

## Data Model

### Family

```
Family
  - id: UUID
  - name: String
  - createdAt: Instant
  - encryptedDEK: ByteArray (wrapped per device)
```

### FamilyMember

```
FamilyMember
  - id: UUID
  - familyId: UUID
  - role: Enum(PARENT, CHILD, GRANDPARENT, CAREGIVER)
  - displayName: String
  - publicKey: ByteArray
  - deviceKeys: List<DeviceKey>
```

### CustodySchedule

```
CustodySchedule
  - id: UUID
  - childId: UUID
  - startDate: LocalDate (anchor)
  - cycleDays: Int (e.g., 14 for 2-2-3)
  - pattern: List<ParentId> (assignment per day in cycle)
  - effectiveFrom: Instant
  - effectiveUntil: Instant? (null = current)
```

Calendar view is **generated** from rules, not stored as materialized rows.
Final view = BasePattern + Overrides (layered precedence).

### Precedence Order

```
CourtOrder > HolidayRule > ApprovedSwap > ManualOverride > BasePattern
```

### ScheduleOverride

```
ScheduleOverride
  - id: UUID
  - scheduleId: UUID
  - dateRange: DateRange
  - assignedParent: ParentId
  - reason: Enum(SWAP, HOLIDAY, COURT_ORDER, MANUAL)
  - status: Enum(PENDING, APPROVED, DECLINED)
  - requestedBy: MemberId
  - respondedBy: MemberId?
  - createdAt: Instant
```

### Expense

```
Expense
  - id: UUID
  - childId: UUID
  - paidBy: ParentId
  - amount: BigDecimal
  - currency: String
  - category: Enum(MEDICAL, EDUCATION, CLOTHING, ACTIVITIES, FOOD, TRANSPORT, CHILDCARE, OTHER)
  - description: String
  - receiptPhotoRef: String? (pointer to encrypted blob)
  - date: LocalDate
  - splitRatio: Float (e.g., 0.5 for 50/50)
  - status: Enum(LOGGED, ACKNOWLEDGED, DISPUTED)
  - createdAt: Instant
```

### InfoBankEntry

```
InfoBankEntry
  - id: UUID
  - childId: UUID
  - category: Enum(MEDICAL, SCHOOL, EMERGENCY, SIZES, INSURANCE, LEGAL)
  - key: String
  - value: String (encrypted)
  - updatedAt: Instant
  - updatedBy: MemberId
  - version: Int
```

### OpLogEntry

```
OpLogEntry
  - sequenceNo: Long (server-assigned, monotonic)
  - deviceId: UUID
  - timestamp: Instant
  - entityType: String
  - entityId: UUID
  - operation: Enum(CREATE, UPDATE, DELETE)
  - encryptedPayload: ByteArray
  - prevHash: ByteArray (hash chain for integrity)
  - keyEpoch: Int (which DEK version was used)
```

## Platform Details

### Android (MVP)

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Local DB**: Room with SQLCipher (encrypted at rest)
- **Min SDK**: 26 (Android 8.0)
- **Architecture**: MVVM + Clean Architecture

### iOS (v0.4+)

- **Language**: Swift
- **UI**: SwiftUI
- **Local DB**: CoreData with encrypted store
- **Min iOS**: 16
- **Architecture**: MVVM + Swift Concurrency

### Sync Server

- **Language**: Kotlin
- **Framework**: Ktor
- **Database**: SQLite with WAL mode
- **Deployment**: Docker Compose (single container)
- **Hosting**: EU-based for managed instance

## Privacy & Legal

- **GDPR**: Privacy-by-design, data minimization, right to export, DPIA required before launch
- **COPPA**: Child accounts are read-only projections, no data collection from children
- **Soft delete**: Data never hard-deleted, only marked cancelled (legal evidence preservation)
- **Data sovereignty**: Self-hostable, EU hosting for managed instance
- **Open source**: Full source available, auditable by anyone

## Build Order

```
Phase 1: Protocol Spec + Sync Engine (headless, with conformance tests)
Phase 2: Server (relay + auth + device management)
Phase 3: Android UI (Compose) - Calendar + Expenses
Phase 4: iOS UI (SwiftUI) - Calendar + Expenses
```

## Key Risks & Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| Cross-platform crypto divergence | Critical | Strict protocol spec + conformance test vectors |
| OpLog unbounded growth | High | Snapshot mechanism every ~500 ops |
| Recovery kit compromise | High | Threshold recovery (2-of-3), post-recovery rotation |
| One parent refuses to use app | High | Solo mode (personal logger + PDF export) |
| SQLite write contention on server | Medium | WAL mode, single-tenant design, upgrade path to PostgreSQL |
| Notification fatigue | Medium | Batched updates, configurable notification preferences |

## Competitive Positioning

| Feature | KidSync (us) | OurFamilyWizard | 2Houses | Cozi |
|---------|-------------|-----------------|---------|------|
| Price | Free/OSS | $144/yr | $140/yr | Free (ads) |
| E2E Encryption | Yes | No | No | No |
| Self-hostable | Yes | No | No | No |
| Custody patterns | Yes | Yes | Yes | No |
| Expense tracking | Yes | Yes | Yes | No |
| Messaging | No (by design) | Yes (ToneMeter) | Yes | No |
| Court-ready export | v1.0 | Yes | Yes | No |
| Open source | Yes | No | No | No |

## Review History

- **Cycle 1** (Gemini + Codex): Identified missing Info Bank, audit trail, handover protocol, solo mode. Flagged COPPA/GDPR requirements. Recommended scoped comments over messaging.
- **Cycle 2** (Gemini + Codex): Found E2E vs server-authoritative contradiction. Recommended KMP (declined by product owner), rule-based calendar, SQLite for server, envelope encryption.
- **Cycle 3** (Gemini + Codex): Final validation. Added hash chaining, snapshot mechanism, threshold recovery, key rotation. Cut MVP to Calendar + Expenses + Parents only.
