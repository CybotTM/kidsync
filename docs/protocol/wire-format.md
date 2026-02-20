# Wire Format Specification

**Protocol Version:** 1
**Date:** 2026-02-20
**Status:** Draft (pending Gate P0 review)
**Applies to:** v0.1 (MVP) -- Calendar + Expenses modules only

---

## Table of Contents

1. [Overview](#1-overview)
2. [Conventions](#2-conventions)
3. [Serialization Rules](#3-serialization-rules)
4. [OpLogEntry (Encrypted Envelope)](#4-oplogentry-encrypted-envelope)
5. [OperationPayload (Decrypted Business Data)](#5-operationpayload-decrypted-business-data)
6. [Operation Payload Variants](#6-operation-payload-variants)
7. [Enumeration Types](#7-enumeration-types)
8. [Binary Blob Protocol](#8-binary-blob-protocol)
9. [Serialization Library Choices](#9-serialization-library-choices)
10. [Versioning and Extensibility](#10-versioning-and-extensibility)
11. [Deferred Types (v0.2+)](#11-deferred-types-v02)
12. [Complete Examples](#12-complete-examples)

---

## 1. Overview

This document defines the wire format for all data exchanged between clients and the sync
server in the co-parenting application. The protocol follows a local-first, append-only
architecture where every mutation is recorded as an immutable `OpLogEntry` containing an
encrypted payload.

**Key architectural constraints:**

- The server is a "dumb relay" -- it stores and forwards encrypted blobs and assigns
  monotonic sequence numbers. It never decrypts payloads.
- All business data travels inside `encryptedPayload` as AES-256-GCM ciphertext.
- Binary assets (receipt photos, documents) are stored separately from the OpLog and
  referenced by blob ID.
- InfoBankEntry operations are excluded from protocol v1 (deferred to v0.2).

---

## 2. Conventions

### 2.1 Terminology

| Term | Meaning |
|------|---------|
| MUST / MUST NOT | Absolute requirement per RFC 2119 |
| SHOULD / SHOULD NOT | Recommended but not absolute |
| OpLogEntry | The encrypted envelope transmitted between client and server |
| OperationPayload | The decrypted JSON object inside `encryptedPayload` |
| DEK | Data Encryption Key (per-family symmetric key) |
| Key epoch | Monotonic version number for a DEK generation |

### 2.2 Notation

- JSON Schema uses the 2020-12 draft vocabulary.
- All examples use pretty-printed JSON for readability. Implementations MUST NOT rely on
  whitespace.
- Byte lengths refer to the decoded (binary) form unless stated otherwise.

---

## 3. Serialization Rules

### 3.1 JSON Encoding

- All JSON MUST be encoded as UTF-8.
- Implementations MUST produce valid JSON per RFC 8259.
- No BOM (Byte Order Mark) prefix.
- No trailing commas.
- No comments.

### 3.2 Field Ordering

- Implementations MUST NOT depend on field ordering for correctness.
- For **hash computation** (device hash chain), fields MUST be serialized in the
  lexicographic order of their JSON key names. This ensures deterministic byte output
  across platforms. See [Section 3.7](#37-canonical-serialization-for-hashing).

### 3.3 Null Handling

- Fields with `null` values MUST be **omitted** from the serialized JSON, not included
  as explicit `null`. This reduces payload size and simplifies cross-platform handling.
- Exception: `globalSequence` and `serverTimestamp` in `OpLogEntry` are omitted when
  the entry has not yet been assigned a server sequence (i.e., pending upload). Once
  server-assigned, they MUST be present.
- Receivers MUST treat a missing optional field as `null`.

### 3.4 Date and Time Formats

All temporal values follow ISO 8601 with the following specific rules:

| Type | Format | Example | Usage |
|------|--------|---------|-------|
| `Instant` (UTC timestamp) | `YYYY-MM-DDThh:mm:ss.sssZ` | `2026-03-15T14:30:00.000Z` | OpLog timestamps, effectiveFrom, serverTimestamp |
| `LocalDate` | `YYYY-MM-DD` | `2026-03-15` | Calendar dates (anchorDate, expense incurredAt, event date) |
| `LocalTime` | `hh:mm:ss` | `09:30:00` | Event times (24-hour format) |
| `ZoneId` | IANA timezone identifier | `Europe/Berlin` | Timezone for custody schedule interpretation |

**Rules:**

- `Instant` values MUST always include the `Z` suffix (UTC). Offsets like `+02:00`
  MUST NOT be used.
- `Instant` values MUST include exactly 3 fractional digits (millisecond precision).
  Implementations MUST zero-pad if the source has sub-millisecond or no fractional
  precision (e.g., `14:30:00` becomes `14:30:00.000Z`).
- `LocalDate` values carry no timezone or offset. The timezone context is determined
  by the entity's `timeZone` field (for custody schedules) or is implicit (for expenses,
  it is the incurring parent's local date).
- `LocalTime` values MUST use 24-hour format with seconds. No fractional seconds.
- `ZoneId` MUST be a valid IANA Time Zone Database identifier (e.g., `America/New_York`,
  `Europe/Berlin`). Abbreviations like `EST`, `CET` MUST NOT be used.

### 3.5 String Formats

| Field type | Format | Validation |
|------------|--------|------------|
| UUID | Lowercase hex with hyphens: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` | RFC 4122 v4 |
| SHA-256 hash | Lowercase hex, 64 characters | `^[0-9a-f]{64}$` |
| Base64 | Standard Base64 per RFC 4648 Section 4, with padding (`=`) | No line breaks |
| Currency code | ISO 4217 uppercase, 3 characters | `^[A-Z]{3}$` |

### 3.6 Numeric Types

- Integer values (sequence numbers, amounts in cents, cycle lengths) MUST be
  represented as JSON numbers without fractional parts.
- `amountCents` is a signed 64-bit integer representing the monetary amount in the
  smallest currency unit (e.g., cents for EUR/USD). This avoids floating-point
  precision issues.
- `payerResponsibilityRatio` is a JSON number in the range `[0.0, 1.0]` with at most
  4 decimal places of precision. Example: `0.5` (50/50 split), `0.6` (60/40 split).
- `cycleLengthDays` MUST be a positive integer >= 1.

### 3.7 Canonical Serialization for Hashing

The **canonical serialization** of the `OperationPayload` is used as the plaintext
for compression and subsequent AES-256-GCM encryption:

1. Serialize the `OperationPayload` as JSON.
2. Object keys MUST be sorted lexicographically (Unicode code point order) at every
   nesting level.
3. No whitespace between tokens (compact form).
4. Null/absent fields MUST be omitted (not serialized as `null`).
5. The resulting UTF-8 byte sequence is the canonical form.

**Note on the hash chain:** The per-device hash chain is computed over the
**encrypted payload bytes** (ciphertext), NOT the plaintext. See Section 4.2
(`devicePrevHash`) for the formula. This allows the server to verify hash chain
integrity without decrypting.

### 3.8 Compression

Implementations MUST gzip-compress (RFC 1952) the canonical JSON payload **before**
AES-256-GCM encryption, and gunzip-decompress **after** decryption. The compression
step reduces ciphertext size, especially for payloads with repetitive fields (e.g.,
custody patterns).

```
Encryption:  canonical JSON bytes -> gzip compress -> AES-256-GCM encrypt -> Base64
Decryption:  Base64 -> AES-256-GCM decrypt -> gunzip decompress -> JSON bytes
```

See `encryption-spec.md` Section 12 for the full encryption/decryption pipeline
including compression.

---

## 4. OpLogEntry (Encrypted Envelope)

The `OpLogEntry` is the unit of data exchanged between clients and the server. The server
can read the envelope fields but cannot decrypt the payload.

### 4.1 JSON Schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kidsync.app/protocol/v1/op-log-entry.schema.json",
  "title": "OpLogEntry",
  "description": "Encrypted envelope for a single operation in the append-only log.",
  "type": "object",
  "properties": {
    "globalSequence": {
      "type": "integer",
      "minimum": 1,
      "description": "Server-assigned monotonic sequence number. Absent when the entry is pending upload (client-side only)."
    },
    "deviceId": {
      "type": "string",
      "format": "uuid",
      "description": "UUID of the device that created this operation."
    },
    "deviceSequence": {
      "type": "integer",
      "minimum": 1,
      "description": "Per-device monotonically increasing counter, starting at 1."
    },
    "entityType": {
      "type": "string",
      "enum": ["CustodySchedule", "ScheduleOverride", "Expense", "ExpenseStatus"],
      "description": "The type of entity this operation targets. Visible to the server for override state-machine validation."
    },
    "entityId": {
      "type": "string",
      "format": "uuid",
      "description": "UUID of the entity this operation targets."
    },
    "operation": {
      "type": "string",
      "enum": ["CREATE", "UPDATE", "DELETE"],
      "description": "The type of mutation being performed."
    },
    "devicePrevHash": {
      "type": "string",
      "pattern": "^[0-9a-f]{64}$",
      "description": "SHA-256 hash of the previous operation from this same device. For the first operation from a device, this is 64 hex zeros (representing 32 zero bytes)."
    },
    "currentHash": {
      "type": "string",
      "pattern": "^[0-9a-f]{64}$",
      "description": "SHA256(hexDecode(devicePrevHash) + base64Decode(encryptedPayload)). Computed over ciphertext bytes, allowing the server and other clients to verify hash chain integrity without decrypting."
    },
    "serverTimestamp": {
      "type": "string",
      "format": "date-time",
      "description": "UTC timestamp assigned by the server on receipt. Absent when the entry is pending upload."
    },
    "clientTimestamp": {
      "type": "string",
      "format": "date-time",
      "description": "UTC timestamp from the originating device when the operation was created."
    },
    "protocolVersion": {
      "type": "integer",
      "const": 1,
      "description": "Protocol version. This specification defines version 1."
    },
    "keyEpoch": {
      "type": "integer",
      "minimum": 1,
      "description": "The DEK epoch (version) used to encrypt this payload. Receivers use this to select the correct decryption key."
    },
    "encryptedPayload": {
      "type": "string",
      "contentEncoding": "base64",
      "description": "Base64-encoded AES-256-GCM ciphertext of the canonical OperationPayload JSON. The first 12 bytes of the decoded value are the GCM nonce/IV, followed by the ciphertext, followed by the 16-byte GCM authentication tag."
    },
    "transitionTo": {
      "type": "string",
      "enum": ["APPROVED", "DECLINED", "CANCELLED", "SUPERSEDED", "EXPIRED"],
      "description": "Required for ScheduleOverride UPDATE ops only. The target state for server-side state-machine validation."
    }
  },
  "required": [
    "deviceId",
    "deviceSequence",
    "entityType",
    "entityId",
    "operation",
    "devicePrevHash",
    "currentHash",
    "protocolVersion",
    "keyEpoch",
    "encryptedPayload",
    "clientTimestamp"
  ],
  "if": {
    "properties": {
      "entityType": { "const": "ScheduleOverride" },
      "operation": { "const": "UPDATE" }
    },
    "required": ["entityType", "operation"]
  },
  "then": {
    "required": ["transitionTo"]
  },
  "additionalProperties": false
}
```

### 4.2 Field Details

#### `globalSequence`

- Assigned by the server atomically upon receipt.
- Strictly monotonically increasing across all devices in a family.
- Clients MUST NOT set this field when uploading. The server rejects uploads that include it.
- Once assigned, the value is immutable.

#### `devicePrevHash`

- Forms a per-device hash chain for tamper detection.
- The `currentHash` is computed as:
  `SHA256(bytes(devicePrevHash) + base64Decode(encryptedPayload))` where:
  - `bytes(devicePrevHash)` is the 32-byte binary representation of the hex-encoded
    previous hash (i.e., hex decode the 64-character string to 32 raw bytes).
  - `base64Decode(encryptedPayload)` is the raw encrypted payload bytes after Base64
    decoding.
  - `+` denotes byte concatenation.
- The hash chain uses the **encrypted payload** (ciphertext), not the plaintext. This
  allows the server and other clients to verify chain integrity without decrypting.
- For the very first operation from a device, `devicePrevHash` is 64 hex zeros
  (`"0000000000000000000000000000000000000000000000000000000000000000"`),
  representing 32 zero bytes.
- The server validates `currentHash` by recomputing
  `SHA256(hexDecode(devicePrevHash) + base64Decode(encryptedPayload))` and comparing.
  Clients MUST also validate the chain on sync pull.

#### `encryptedPayload` Binary Layout

```
| Nonce (12 bytes) | Ciphertext (variable) | GCM Auth Tag (16 bytes) |
```

The entire concatenation is Base64-encoded into the `encryptedPayload` string field.

### 4.3 Example OpLogEntry (as sent from server to client)

```json
{
  "globalSequence": 42,
  "deviceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "devicePrevHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "serverTimestamp": "2026-03-15T14:30:00.000Z",
  "protocolVersion": 1,
  "keyEpoch": 1,
  "encryptedPayload": "dGhpcyBpcyBhIHBsYWNlaG9sZGVyIGZvciBhY3R1YWwgQUVTLTI1Ni1HQ00gY2lwaGVydGV4dA=="
}
```

### 4.4 Example OpLogEntry (pending upload, client-side)

```json
{
  "deviceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "devicePrevHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "protocolVersion": 1,
  "keyEpoch": 1,
  "encryptedPayload": "dGhpcyBpcyBhIHBsYWNlaG9sZGVyIGZvciBhY3R1YWwgQUVTLTI1Ni1HQ00gY2lwaGVydGV4dA=="
}
```

Note: `globalSequence` and `serverTimestamp` are absent (not `null`) because they have not
been assigned yet.

---

## 5. OperationPayload (Decrypted Business Data)

After decrypting `encryptedPayload`, the resulting JSON conforms to the `OperationPayload`
schema. This is a discriminated union keyed on the `payloadType` field.

### 5.1 Base OperationPayload Schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kidsync.app/protocol/v1/operation-payload.schema.json",
  "title": "OperationPayload",
  "description": "Decrypted business data inside an OpLogEntry. Discriminated by payloadType.",
  "type": "object",
  "properties": {
    "payloadType": {
      "type": "string",
      "enum": [
        "SetCustodySchedule",
        "UpsertOverride",
        "CreateExpense",
        "UpdateExpenseStatus",
        "CreateEvent",
        "UpdateEvent",
        "CancelEvent",
        "DeviceSnapshot"
      ],
      "description": "Discriminator for the payload variant."
    },
    "entityId": {
      "type": "string",
      "format": "uuid",
      "description": "UUID of the entity being created or mutated."
    },
    "timestamp": {
      "type": "string",
      "format": "date-time",
      "description": "UTC instant when the operation was created on the originating device."
    },
    "operationType": {
      "type": "string",
      "enum": ["CREATE", "UPDATE", "DELETE"],
      "description": "The type of mutation being performed on the entity."
    }
  },
  "required": ["payloadType", "entityId", "timestamp", "operationType"],
  "allOf": [
    {
      "if": {
        "properties": { "payloadType": { "const": "SetCustodySchedule" } }
      },
      "then": { "$ref": "#/$defs/SetCustodySchedule" }
    },
    {
      "if": {
        "properties": { "payloadType": { "const": "UpsertOverride" } }
      },
      "then": { "$ref": "#/$defs/UpsertOverride" }
    },
    {
      "if": {
        "properties": { "payloadType": { "const": "CreateExpense" } }
      },
      "then": { "$ref": "#/$defs/CreateExpense" }
    },
    {
      "if": {
        "properties": { "payloadType": { "const": "UpdateExpenseStatus" } }
      },
      "then": { "$ref": "#/$defs/UpdateExpenseStatus" }
    },
    {
      "if": {
        "properties": { "payloadType": { "const": "CreateEvent" } }
      },
      "then": { "$ref": "#/$defs/CreateEvent" }
    },
    {
      "if": {
        "properties": { "payloadType": { "const": "UpdateEvent" } }
      },
      "then": { "$ref": "#/$defs/UpdateEvent" }
    },
    {
      "if": {
        "properties": { "payloadType": { "const": "CancelEvent" } }
      },
      "then": { "$ref": "#/$defs/CancelEvent" }
    },
    {
      "if": {
        "properties": { "payloadType": { "const": "DeviceSnapshot" } }
      },
      "then": { "$ref": "#/$defs/DeviceSnapshot" }
    }
  ],
  "$defs": {}
}
```

The `$defs` are specified individually in Section 6 below and are logically part of this
schema.

### 5.2 Common Field Semantics

#### `entityId`

- For `CREATE` operations, the client generates a new v4 UUID.
- For `UPDATE` and `DELETE` operations, this references the existing entity.
- Different payload types use domain-specific aliases (e.g., `scheduleId`, `expenseId`)
  but the value is always duplicated in the top-level `entityId` field.

#### `timestamp`

- The wall-clock time on the originating device at the moment the operation was created.
- Always UTC, always millisecond precision.
- Used for conflict resolution tie-breaking (see sync protocol specification).
- Clients SHOULD use NTP-synchronized clocks. The protocol tolerates clock skew but
  prefers accuracy.

#### `operationType`

- `CREATE`: The entity did not previously exist. Applying a `CREATE` for an entity that
  already exists is an error (clients MUST deduplicate by `entityId`).
- `UPDATE`: The entity must already exist. Applying an `UPDATE` for a nonexistent entity
  is a recoverable error (the client SHOULD request a full re-sync).
- `DELETE`: Soft-delete. The entity is marked as cancelled/deleted but preserved in the
  log for audit. Only `CancelEvent` uses `DELETE` in v0.1.

---

## 6. Operation Payload Variants

### 6.1 SetCustodySchedule

Creates or replaces a custody schedule pattern for a child.

```json
{
  "$defs": {
    "SetCustodySchedule": {
      "type": "object",
      "properties": {
        "payloadType": { "const": "SetCustodySchedule" },
        "entityId": { "type": "string", "format": "uuid" },
        "timestamp": { "type": "string", "format": "date-time" },
        "operationType": {
          "type": "string",
          "enum": ["CREATE", "UPDATE"]
        },
        "scheduleId": {
          "type": "string",
          "format": "uuid",
          "description": "Same value as entityId. Identifies this schedule."
        },
        "childId": {
          "type": "string",
          "format": "uuid",
          "description": "The child this schedule applies to."
        },
        "anchorDate": {
          "type": "string",
          "format": "date",
          "description": "The start date of the first cycle (LocalDate, no timezone)."
        },
        "cycleLengthDays": {
          "type": "integer",
          "minimum": 1,
          "maximum": 365,
          "description": "Number of days in one complete cycle of the pattern."
        },
        "pattern": {
          "type": "array",
          "items": {
            "type": "string",
            "format": "uuid"
          },
          "minItems": 1,
          "description": "Ordered list of parent UUIDs, one per day in the cycle. Length MUST equal cycleLengthDays."
        },
        "effectiveFrom": {
          "type": "string",
          "format": "date-time",
          "description": "UTC instant from which this schedule takes effect. Used for conflict resolution (latest effectiveFrom wins)."
        },
        "timeZone": {
          "type": "string",
          "description": "IANA timezone identifier for interpreting anchorDate and determining day boundaries."
        }
      },
      "required": [
        "payloadType", "entityId", "timestamp", "operationType",
        "scheduleId", "childId", "anchorDate", "cycleLengthDays",
        "pattern", "effectiveFrom", "timeZone"
      ],
      "additionalProperties": false
    }
  }
}
```

**Validation rules:**

- `pattern.length` MUST equal `cycleLengthDays`.
- Each entry in `pattern` MUST be the UUID of a parent in the family.
- `operationType` MUST be `CREATE` or `UPDATE`. Schedules are never deleted; they are
  superseded by newer schedules with a later `effectiveFrom`.
- `scheduleId` MUST equal `entityId`.

**Example:**

```json
{
  "payloadType": "SetCustodySchedule",
  "entityId": "b7e1f2a3-4c5d-6e7f-8901-234567890abc",
  "timestamp": "2026-03-15T10:00:00.000Z",
  "operationType": "CREATE",
  "scheduleId": "b7e1f2a3-4c5d-6e7f-8901-234567890abc",
  "childId": "c1d2e3f4-5678-9abc-def0-123456789012",
  "anchorDate": "2026-03-17",
  "cycleLengthDays": 14,
  "pattern": [
    "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
    "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
    "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
    "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
    "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
    "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
    "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
    "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
    "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
    "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
    "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
    "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
    "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
    "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb"
  ],
  "effectiveFrom": "2026-03-17T00:00:00.000Z",
  "timeZone": "Europe/Berlin"
}
```

This example defines a week-on/week-off pattern starting March 17, 2026.

### 6.2 UpsertOverride

Creates or updates a schedule override (swap request, holiday rule, court order, or manual
override).

```json
{
  "$defs": {
    "UpsertOverride": {
      "type": "object",
      "properties": {
        "payloadType": { "const": "UpsertOverride" },
        "entityId": { "type": "string", "format": "uuid" },
        "timestamp": { "type": "string", "format": "date-time" },
        "operationType": {
          "type": "string",
          "enum": ["CREATE", "UPDATE"]
        },
        "overrideId": {
          "type": "string",
          "format": "uuid",
          "description": "Same value as entityId. Identifies this override."
        },
        "type": {
          "type": "string",
          "enum": ["SWAP_REQUEST", "HOLIDAY_RULE", "COURT_ORDER", "MANUAL_OVERRIDE"],
          "description": "The kind of override."
        },
        "childId": {
          "type": "string",
          "format": "uuid",
          "description": "The child this override applies to."
        },
        "startDate": {
          "type": "string",
          "format": "date",
          "description": "First day of the override period (LocalDate, inclusive)."
        },
        "endDate": {
          "type": "string",
          "format": "date",
          "description": "Last day of the override period (LocalDate, inclusive)."
        },
        "assignedParentId": {
          "type": "string",
          "format": "uuid",
          "description": "The parent assigned custody for this date range."
        },
        "status": {
          "type": "string",
          "enum": [
            "PROPOSED",
            "APPROVED",
            "DECLINED",
            "CANCELLED",
            "SUPERSEDED",
            "EXPIRED"
          ],
          "description": "Current state in the override lifecycle."
        },
        "proposerId": {
          "type": "string",
          "format": "uuid",
          "description": "The parent who proposed this override."
        },
        "responderId": {
          "type": "string",
          "format": "uuid",
          "description": "The parent who approved, declined, or is expected to respond. Absent for PROPOSED status before a response."
        },
        "note": {
          "type": "string",
          "maxLength": 2000,
          "description": "Optional note explaining the reason for the override."
        }
      },
      "required": [
        "payloadType", "entityId", "timestamp", "operationType",
        "overrideId", "type", "childId", "startDate", "endDate",
        "assignedParentId", "status", "proposerId"
      ],
      "additionalProperties": false
    }
  }
}
```

**State machine transitions:**

```
PROPOSED --> APPROVED     (by non-proposing parent)
PROPOSED --> DECLINED     (by non-proposing parent)
PROPOSED --> CANCELLED    (by proposer only)
APPROVED --> SUPERSEDED   (system: higher-precedence override created for overlapping dates)
APPROVED --> EXPIRED      (system: endDate has passed)
```

All terminal states (`DECLINED`, `CANCELLED`, `SUPERSEDED`, `EXPIRED`) are immutable.
Attempts to transition from a terminal state MUST be rejected by the client and the server
(server validates transitions on metadata without decrypting payload).

**Validation rules:**

- `overrideId` MUST equal `entityId`.
- `startDate` MUST be <= `endDate`.
- `operationType` for initial creation MUST be `CREATE` with `status` = `PROPOSED`
  (or `APPROVED` for `COURT_ORDER` type, which skips the proposal flow).
- `operationType` for status transitions MUST be `UPDATE`.
- `responderId` MUST be present when `status` is `APPROVED` or `DECLINED`.
- `responderId` MUST NOT equal `proposerId` for `APPROVED` and `DECLINED` transitions.

**Example (swap request creation):**

```json
{
  "payloadType": "UpsertOverride",
  "entityId": "f1a2b3c4-d5e6-7890-abcd-ef0123456789",
  "timestamp": "2026-03-20T08:15:30.000Z",
  "operationType": "CREATE",
  "overrideId": "f1a2b3c4-d5e6-7890-abcd-ef0123456789",
  "type": "SWAP_REQUEST",
  "childId": "c1d2e3f4-5678-9abc-def0-123456789012",
  "startDate": "2026-04-05",
  "endDate": "2026-04-06",
  "assignedParentId": "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
  "status": "PROPOSED",
  "proposerId": "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
  "note": "Would like to take the kids to grandma's birthday."
}
```

**Example (swap request approval):**

```json
{
  "payloadType": "UpsertOverride",
  "entityId": "f1a2b3c4-d5e6-7890-abcd-ef0123456789",
  "timestamp": "2026-03-20T18:45:00.000Z",
  "operationType": "UPDATE",
  "overrideId": "f1a2b3c4-d5e6-7890-abcd-ef0123456789",
  "type": "SWAP_REQUEST",
  "childId": "c1d2e3f4-5678-9abc-def0-123456789012",
  "startDate": "2026-04-05",
  "endDate": "2026-04-06",
  "assignedParentId": "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
  "status": "APPROVED",
  "proposerId": "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
  "responderId": "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb"
}
```

### 6.3 CreateExpense

Logs a new child-related expense.

```json
{
  "$defs": {
    "CreateExpense": {
      "type": "object",
      "properties": {
        "payloadType": { "const": "CreateExpense" },
        "entityId": { "type": "string", "format": "uuid" },
        "timestamp": { "type": "string", "format": "date-time" },
        "operationType": { "const": "CREATE" },
        "expenseId": {
          "type": "string",
          "format": "uuid",
          "description": "Same value as entityId. Identifies this expense."
        },
        "childId": {
          "type": "string",
          "format": "uuid",
          "description": "The child this expense relates to."
        },
        "paidByUserId": {
          "type": "string",
          "format": "uuid",
          "description": "The parent who paid this expense."
        },
        "amountCents": {
          "type": "integer",
          "minimum": 1,
          "description": "Amount in the smallest currency unit (e.g., cents). Must be positive."
        },
        "currencyCode": {
          "type": "string",
          "pattern": "^[A-Z]{3}$",
          "description": "ISO 4217 currency code (e.g., EUR, USD)."
        },
        "category": {
          "type": "string",
          "enum": [
            "MEDICAL",
            "EDUCATION",
            "CLOTHING",
            "ACTIVITIES",
            "FOOD",
            "TRANSPORT",
            "CHILDCARE",
            "OTHER"
          ],
          "description": "Expense category."
        },
        "description": {
          "type": "string",
          "minLength": 1,
          "maxLength": 500,
          "description": "Human-readable description of the expense."
        },
        "incurredAt": {
          "type": "string",
          "format": "date",
          "description": "The date the expense was incurred (LocalDate)."
        },
        "payerResponsibilityRatio": {
          "type": "number",
          "minimum": 0.0,
          "maximum": 1.0,
          "description": "Fraction of the expense that is the payer's responsibility (0.0 = other parent pays all, 1.0 = payer pays all). A value of 0.5 means 50/50 split."
        },
        "receiptBlobId": {
          "type": "string",
          "format": "uuid",
          "description": "Reference to the encrypted receipt blob in blob storage. Absent if no receipt attached."
        },
        "receiptDecryptionKey": {
          "type": "string",
          "contentEncoding": "base64",
          "description": "Base64-encoded AES-256-GCM key for decrypting the receipt blob. Absent if no receipt attached. This is a per-blob key, not the family DEK."
        }
      },
      "required": [
        "payloadType", "entityId", "timestamp", "operationType",
        "expenseId", "childId", "paidByUserId", "amountCents",
        "currencyCode", "category", "description", "incurredAt",
        "payerResponsibilityRatio"
      ],
      "additionalProperties": false,
      "dependentRequired": {
        "receiptBlobId": ["receiptDecryptionKey"],
        "receiptDecryptionKey": ["receiptBlobId"]
      }
    }
  }
}
```

**Validation rules:**

- `expenseId` MUST equal `entityId`.
- `operationType` MUST be `CREATE`. Expenses are append-only; status changes use
  `UpdateExpenseStatus`.
- `receiptBlobId` and `receiptDecryptionKey` MUST either both be present or both be absent.
- `amountCents` MUST be positive (> 0).

**Example:**

```json
{
  "payloadType": "CreateExpense",
  "entityId": "a1234567-b890-cdef-1234-567890abcdef",
  "timestamp": "2026-03-18T16:22:00.000Z",
  "operationType": "CREATE",
  "expenseId": "a1234567-b890-cdef-1234-567890abcdef",
  "childId": "c1d2e3f4-5678-9abc-def0-123456789012",
  "paidByUserId": "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
  "amountCents": 4500,
  "currencyCode": "EUR",
  "category": "MEDICAL",
  "description": "Pediatrician visit - flu symptoms",
  "incurredAt": "2026-03-18",
  "payerResponsibilityRatio": 0.5,
  "receiptBlobId": "deadbeef-1234-5678-9abc-def012345678",
  "receiptDecryptionKey": "c2VjcmV0LWJsb2Ita2V5LTMyLWJ5dGVzLWhlcmUtcGFkZGVk"
}
```

### 6.4 UpdateExpenseStatus

Changes the status of an existing expense (acknowledge or dispute).

```json
{
  "$defs": {
    "UpdateExpenseStatus": {
      "type": "object",
      "properties": {
        "payloadType": { "const": "UpdateExpenseStatus" },
        "entityId": { "type": "string", "format": "uuid" },
        "timestamp": { "type": "string", "format": "date-time" },
        "operationType": { "const": "UPDATE" },
        "expenseId": {
          "type": "string",
          "format": "uuid",
          "description": "Same value as entityId. The expense being updated."
        },
        "status": {
          "type": "string",
          "enum": ["LOGGED", "ACKNOWLEDGED", "DISPUTED"],
          "description": "New status for the expense."
        },
        "responderId": {
          "type": "string",
          "format": "uuid",
          "description": "The parent responding to the expense (must be the non-paying parent)."
        },
        "note": {
          "type": "string",
          "maxLength": 2000,
          "description": "Optional note, typically used when disputing an expense."
        }
      },
      "required": [
        "payloadType", "entityId", "timestamp", "operationType",
        "expenseId", "status", "responderId"
      ],
      "additionalProperties": false
    }
  }
}
```

**Validation rules:**

- `expenseId` MUST equal `entityId`.
- `operationType` MUST be `UPDATE`.
- `responderId` MUST NOT equal the `paidByUserId` of the referenced expense (the payer
  cannot acknowledge/dispute their own expense).
- Valid transitions: `LOGGED` -> `ACKNOWLEDGED`, `LOGGED` -> `DISPUTED`.

**Example:**

```json
{
  "payloadType": "UpdateExpenseStatus",
  "entityId": "a1234567-b890-cdef-1234-567890abcdef",
  "timestamp": "2026-03-19T09:00:00.000Z",
  "operationType": "UPDATE",
  "expenseId": "a1234567-b890-cdef-1234-567890abcdef",
  "status": "DISPUTED",
  "responderId": "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb",
  "note": "Amount seems too high for a standard visit. Can you share the invoice?"
}
```

### 6.5 CreateEvent

Creates a calendar event for a child (doctor appointment, school event, etc.).

```json
{
  "$defs": {
    "CreateEvent": {
      "type": "object",
      "properties": {
        "payloadType": { "const": "CreateEvent" },
        "entityId": { "type": "string", "format": "uuid" },
        "timestamp": { "type": "string", "format": "date-time" },
        "operationType": { "const": "CREATE" },
        "eventId": {
          "type": "string",
          "format": "uuid",
          "description": "Same value as entityId. Identifies this event."
        },
        "childId": {
          "type": "string",
          "format": "uuid",
          "description": "The child this event relates to."
        },
        "title": {
          "type": "string",
          "minLength": 1,
          "maxLength": 200,
          "description": "Short title for the event."
        },
        "date": {
          "type": "string",
          "format": "date",
          "description": "The date of the event (LocalDate)."
        },
        "time": {
          "type": "string",
          "pattern": "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$",
          "description": "The time of the event (LocalTime, 24-hour format HH:mm:ss). Absent for all-day events."
        },
        "location": {
          "type": "string",
          "maxLength": 500,
          "description": "Optional location description."
        },
        "notes": {
          "type": "string",
          "maxLength": 2000,
          "description": "Optional additional notes."
        }
      },
      "required": [
        "payloadType", "entityId", "timestamp", "operationType",
        "eventId", "childId", "title", "date"
      ],
      "additionalProperties": false
    }
  }
}
```

**Validation rules:**

- `eventId` MUST equal `entityId`.
- `operationType` MUST be `CREATE`.

**Example:**

```json
{
  "payloadType": "CreateEvent",
  "entityId": "11223344-5566-7788-99aa-bbccddeeff00",
  "timestamp": "2026-03-16T12:00:00.000Z",
  "operationType": "CREATE",
  "eventId": "11223344-5566-7788-99aa-bbccddeeff00",
  "childId": "c1d2e3f4-5678-9abc-def0-123456789012",
  "title": "Parent-teacher conference",
  "date": "2026-04-10",
  "time": "15:30:00",
  "location": "Grundschule am Park, Room 201",
  "notes": "Bring last semester's report card."
}
```

### 6.6 UpdateEvent

Updates fields on an existing calendar event.

```json
{
  "$defs": {
    "UpdateEvent": {
      "type": "object",
      "properties": {
        "payloadType": { "const": "UpdateEvent" },
        "entityId": { "type": "string", "format": "uuid" },
        "timestamp": { "type": "string", "format": "date-time" },
        "operationType": { "const": "UPDATE" },
        "eventId": {
          "type": "string",
          "format": "uuid",
          "description": "Same value as entityId. The event being updated."
        },
        "childId": {
          "type": "string",
          "format": "uuid",
          "description": "The child this event relates to."
        },
        "title": {
          "type": "string",
          "minLength": 1,
          "maxLength": 200,
          "description": "Updated title."
        },
        "date": {
          "type": "string",
          "format": "date",
          "description": "Updated date (LocalDate)."
        },
        "time": {
          "type": "string",
          "pattern": "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$",
          "description": "Updated time (LocalTime). Absent to keep the event as all-day or to remove a previously set time."
        },
        "location": {
          "type": "string",
          "maxLength": 500,
          "description": "Updated location."
        },
        "notes": {
          "type": "string",
          "maxLength": 2000,
          "description": "Updated notes."
        }
      },
      "required": [
        "payloadType", "entityId", "timestamp", "operationType",
        "eventId", "childId", "title", "date"
      ],
      "additionalProperties": false
    }
  }
}
```

**Semantics:**

- `UpdateEvent` is a **full replacement** of the event's mutable fields, not a partial
  patch. All required fields MUST be provided. Optional fields that are absent after an
  update are cleared (set to null in the local model).
- Conflict resolution: last-write-wins based on `timestamp` (then `deviceId`
  lexicographic tie-break).

**Validation rules:**

- `eventId` MUST equal `entityId`.
- `operationType` MUST be `UPDATE`.

**Example:**

```json
{
  "payloadType": "UpdateEvent",
  "entityId": "11223344-5566-7788-99aa-bbccddeeff00",
  "timestamp": "2026-03-17T09:00:00.000Z",
  "operationType": "UPDATE",
  "eventId": "11223344-5566-7788-99aa-bbccddeeff00",
  "childId": "c1d2e3f4-5678-9abc-def0-123456789012",
  "title": "Parent-teacher conference (rescheduled)",
  "date": "2026-04-12",
  "time": "16:00:00",
  "location": "Grundschule am Park, Room 201"
}
```

Note: `notes` is absent, meaning the previously stored notes are cleared.

### 6.7 CancelEvent

Cancels (soft-deletes) an existing calendar event.

```json
{
  "$defs": {
    "CancelEvent": {
      "type": "object",
      "properties": {
        "payloadType": { "const": "CancelEvent" },
        "entityId": { "type": "string", "format": "uuid" },
        "timestamp": { "type": "string", "format": "date-time" },
        "operationType": { "const": "DELETE" },
        "eventId": {
          "type": "string",
          "format": "uuid",
          "description": "Same value as entityId. The event being cancelled."
        }
      },
      "required": [
        "payloadType", "entityId", "timestamp", "operationType",
        "eventId"
      ],
      "additionalProperties": false
    }
  }
}
```

**Semantics:**

- Cancellation is a soft-delete. The event remains in the OpLog for audit history.
- Cancelled events SHOULD be hidden from normal calendar views but MAY be shown in an
  audit/history view.
- A cancelled event MUST NOT be updated further. Clients MUST reject any operation on
  a cancelled event.

**Example:**

```json
{
  "payloadType": "CancelEvent",
  "entityId": "11223344-5566-7788-99aa-bbccddeeff00",
  "timestamp": "2026-03-17T10:00:00.000Z",
  "operationType": "DELETE",
  "eventId": "11223344-5566-7788-99aa-bbccddeeff00"
}
```

### 6.8 DeviceSnapshot

Records metadata about a state snapshot for new-device bootstrapping.

```json
{
  "$defs": {
    "DeviceSnapshot": {
      "type": "object",
      "properties": {
        "payloadType": { "const": "DeviceSnapshot" },
        "entityId": { "type": "string", "format": "uuid" },
        "timestamp": { "type": "string", "format": "date-time" },
        "operationType": { "const": "CREATE" },
        "startGlobalSequence": {
          "type": "integer",
          "minimum": 1,
          "description": "First global sequence number included in this snapshot."
        },
        "endGlobalSequence": {
          "type": "integer",
          "minimum": 1,
          "description": "Last global sequence number included in this snapshot."
        },
        "stateHash": {
          "type": "string",
          "pattern": "^[0-9a-f]{64}$",
          "description": "SHA-256 hash of the canonical snapshot state (for integrity verification)."
        },
        "snapshotBlobId": {
          "type": "string",
          "format": "uuid",
          "description": "Reference to the encrypted snapshot blob in blob storage."
        }
      },
      "required": [
        "payloadType", "entityId", "timestamp", "operationType",
        "startGlobalSequence", "endGlobalSequence", "stateHash",
        "snapshotBlobId"
      ],
      "additionalProperties": false
    }
  }
}
```

**Semantics:**

- A snapshot represents the complete materialized state as of `endGlobalSequence`.
- New devices bootstrap by downloading the latest snapshot, then replaying ops from
  `endGlobalSequence + 1` onward.
- The `stateHash` allows verification that the snapshot was correctly generated from
  the ops in range `[startGlobalSequence, endGlobalSequence]`.
- Snapshots are created approximately every 500 ops, or when a new device joins the
  family.

**Validation rules:**

- `startGlobalSequence` MUST be <= `endGlobalSequence`.
- `operationType` MUST be `CREATE`. Snapshots are immutable.

**Example:**

```json
{
  "payloadType": "DeviceSnapshot",
  "entityId": "99887766-5544-3322-1100-aabbccddeeff",
  "timestamp": "2026-04-01T00:00:00.000Z",
  "operationType": "CREATE",
  "startGlobalSequence": 1,
  "endGlobalSequence": 500,
  "stateHash": "a9f3e12b7c8d456e1234567890abcdef1234567890abcdef1234567890abcdef",
  "snapshotBlobId": "ffeeddcc-bbaa-9988-7766-554433221100"
}
```

---

## 7. Enumeration Types

All enumeration values are serialized as uppercase strings. Clients MUST reject unknown
enum values and treat them as a sync error (request re-sync or prompt for app update).

### 7.1 OverrideType

| Value | Description |
|-------|-------------|
| `SWAP_REQUEST` | Parent-initiated date swap |
| `HOLIDAY_RULE` | Recurring holiday assignment |
| `COURT_ORDER` | Court-mandated schedule change |
| `MANUAL_OVERRIDE` | One-off exception |

### 7.2 OverrideStatus

| Value | Terminal? | Description |
|-------|-----------|-------------|
| `PROPOSED` | No | Initial state, awaiting response from non-proposing parent |
| `APPROVED` | No | Accepted by non-proposing parent; active for the date range |
| `DECLINED` | Yes | Rejected by non-proposing parent |
| `CANCELLED` | Yes | Withdrawn by proposer before any response |
| `SUPERSEDED` | Yes | Replaced by a higher-precedence override |
| `EXPIRED` | Yes | Date range has passed; system-triggered |

### 7.3 ExpenseCategory

| Value | Description |
|-------|-------------|
| `MEDICAL` | Doctor visits, prescriptions, therapy |
| `EDUCATION` | School fees, tutoring, supplies |
| `CLOTHING` | Clothes and shoes |
| `ACTIVITIES` | Sports, music lessons, camps |
| `FOOD` | Groceries, school lunches |
| `TRANSPORT` | Travel, gas, public transit for child |
| `CHILDCARE` | Daycare, babysitting, after-school care |
| `OTHER` | Anything not in the above categories |

### 7.4 ExpenseStatus

| Value | Description |
|-------|-------------|
| `LOGGED` | Initial state when expense is created |
| `ACKNOWLEDGED` | Non-paying parent confirms the expense |
| `DISPUTED` | Non-paying parent disputes the expense |

### 7.5 OperationType

| Value | Description |
|-------|-------------|
| `CREATE` | New entity |
| `UPDATE` | Mutation of an existing entity |
| `DELETE` | Soft-delete of an entity |

---

## 8. Binary Blob Protocol

Binary assets (receipt photos, snapshot data) are stored separately from the OpLog. The
OpLog references blobs by ID and includes per-blob decryption keys inside the encrypted
payload.

### 8.1 Blob Constraints

| Constraint | Value |
|------------|-------|
| Maximum blob size | 10 MB (10,485,760 bytes) |
| Allowed content | Any binary data (images, documents, snapshots) |
| Encryption | AES-256-GCM with a per-blob key (not the family DEK) |
| Storage | Server stores encrypted bytes; cannot determine content type |

### 8.2 Blob Encryption Format

Each blob is encrypted client-side before upload:

1. Generate a random 256-bit (32-byte) AES key for this blob.
2. Generate a random 96-bit (12-byte) GCM nonce.
3. Encrypt the plaintext blob with AES-256-GCM.
4. Concatenate: `nonce (12 bytes) || ciphertext || auth_tag (16 bytes)`.
5. The concatenated bytes are the upload payload.
6. The per-blob AES key is included in the referencing OpLog entry's encrypted payload
   (e.g., `receiptDecryptionKey` in `CreateExpense`), Base64-encoded.

This ensures the server never has access to the per-blob key (it is inside the
E2E-encrypted OpLog payload).

### 8.3 Upload: `POST /blobs`

**Request:**

- Method: `POST`
- Path: `/blobs`
- Content-Type: `multipart/form-data`
- Authentication: Bearer JWT token
- Parts:

| Part name | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | binary | Yes | The encrypted blob bytes (nonce + ciphertext + tag) |
| `sha256` | text | Yes | SHA-256 hex digest of the encrypted bytes (for server-side integrity check) |

**Request size limit:** 10 MB for the `file` part. The server MUST reject uploads exceeding
this limit with HTTP 413.

**Response (success):**

- Status: `201 Created`
- Content-Type: `application/json`

```json
{
  "blobId": "deadbeef-1234-5678-9abc-def012345678",
  "sizeBytes": 245760,
  "sha256": "abc123def456...",
  "uploadedAt": "2026-03-18T16:20:00.000Z"
}
```

**Response schema:**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kidsync.app/protocol/v1/blob-upload-response.schema.json",
  "title": "BlobUploadResponse",
  "type": "object",
  "properties": {
    "blobId": {
      "type": "string",
      "format": "uuid",
      "description": "Server-assigned blob identifier."
    },
    "sizeBytes": {
      "type": "integer",
      "minimum": 1,
      "description": "Size of the stored encrypted blob in bytes."
    },
    "sha256": {
      "type": "string",
      "pattern": "^[0-9a-f]{64}$",
      "description": "SHA-256 hex digest of the stored encrypted bytes."
    },
    "uploadedAt": {
      "type": "string",
      "format": "date-time",
      "description": "UTC timestamp of when the server stored the blob."
    }
  },
  "required": ["blobId", "sizeBytes", "sha256", "uploadedAt"],
  "additionalProperties": false
}
```

**Error responses:**

| Status | Condition |
|--------|-----------|
| 400 | Missing `file` or `sha256` part; SHA-256 mismatch between `sha256` field and actual file hash |
| 401 | Missing or invalid authentication token |
| 403 | User is not a member of any family (no blob storage entitlement) |
| 413 | File exceeds 10 MB limit |
| 429 | Rate limit exceeded |
| 500 | Internal server error |

### 8.4 Download: `GET /blobs/{id}`

**Request:**

- Method: `GET`
- Path: `/blobs/{blobId}`
- Authentication: Bearer JWT token

**Response (success):**

- Status: `200 OK`
- Content-Type: `application/octet-stream`
- Headers:
  - `Content-Length`: size in bytes
  - `X-Blob-SHA256`: SHA-256 hex digest of the encrypted blob bytes
- Body: raw encrypted blob bytes

**Error responses:**

| Status | Condition |
|--------|-----------|
| 401 | Missing or invalid authentication token |
| 403 | User is not a member of the family that owns this blob |
| 404 | Blob not found or soft-deleted |
| 429 | Rate limit exceeded |

### 8.5 Soft-Delete: `DELETE /blobs/{id}`

**Request:**

- Method: `DELETE`
- Path: `/blobs/{blobId}`
- Authentication: Bearer JWT token

**Response (success):**

- Status: `204 No Content`

**Error responses:**

| Status | Condition |
|--------|-----------|
| 401 | Missing or invalid authentication token |
| 403 | User did not upload this blob |
| 404 | Blob not found |

**Semantics:**

- Soft-delete only. The blob is no longer downloadable but remains on disk for potential
  legal/audit recovery.
- The OpLog entry referencing this blob is unaffected (it still records the blob ID and
  decryption key for historical completeness).

### 8.6 Blob Upload Workflow

The recommended client workflow for attaching a receipt to an expense:

```
1. Client captures/selects image
2. Client generates random 256-bit per-blob AES key
3. Client encrypts image with per-blob key (AES-256-GCM)
4. Client computes SHA-256 of encrypted bytes
5. Client uploads encrypted bytes via POST /blobs
6. Server returns blobId
7. Client creates CreateExpense OpLogEntry with:
   - receiptBlobId = blobId from step 6
   - receiptDecryptionKey = Base64(per-blob AES key from step 2)
8. Client uploads OpLogEntry via POST /sync/ops
```

If step 5 fails (network error), the client queues the upload for retry. The OpLogEntry
in step 7 MUST NOT be created until the blob upload succeeds, because the `blobId` is
server-assigned.

If the client is offline, it SHOULD:
1. Store the encrypted blob locally.
2. Queue the blob upload.
3. Create the OpLogEntry locally with a placeholder `receiptBlobId` set to a locally
   generated UUID prefixed with `local:` (e.g., `local:deadbeef-...`).
4. Upon successful blob upload, create a corrective OpLogEntry that updates the blob
   reference. Alternatively, implementations MAY defer OpLogEntry creation until the
   blob upload succeeds.

---

## 9. Serialization Library Choices

### 9.1 Platform Libraries

| Platform | Library | Notes |
|----------|---------|-------|
| Android (client) | `kotlinx.serialization` | JSON format, `@Serializable` data classes |
| Server (Ktor) | `kotlinx.serialization` | Same library as Android for type sharing |
| iOS (v0.4+) | Swift `Codable` | `JSONEncoder` / `JSONDecoder` with custom date strategies |

### 9.2 kotlinx.serialization Configuration

Android and server implementations MUST use the following `Json` configuration:

```kotlin
val protocolJson = Json {
    // Omit null values from output (Section 3.3)
    explicitNulls = false

    // Do not encode default values (reduces payload size)
    encodeDefaults = false

    // Reject unknown keys on deserialization (strict mode)
    ignoreUnknownKeys = false

    // Use the class discriminator for polymorphic types
    classDiscriminator = "payloadType"

    // Compact output (no pretty-printing on the wire)
    prettyPrint = false
}
```

**Sealed class hierarchy for OperationPayload:**

```kotlin
@Serializable
sealed interface OperationPayload {
    val entityId: String
    val timestamp: Instant
    val operationType: OperationType
}

@Serializable
@SerialName("SetCustodySchedule")
data class SetCustodySchedule(
    override val entityId: String,
    override val timestamp: Instant,
    override val operationType: OperationType,
    val scheduleId: String,
    val childId: String,
    val anchorDate: LocalDate,
    val cycleLengthDays: Int,
    val pattern: List<String>,
    val effectiveFrom: Instant,
    val timeZone: String,
) : OperationPayload

// ... other variants follow the same pattern
```

### 9.3 Swift Codable Configuration (v0.4+)

iOS implementations MUST configure:

```swift
let decoder = JSONDecoder()
decoder.dateDecodingStrategy = .custom { decoder in
    let container = try decoder.singleValueContainer()
    let string = try container.decode(String.self)
    // Parse ISO 8601 with milliseconds and Z suffix
    guard let date = ISO8601DateFormatter.protocolFormatter.date(from: string) else {
        throw DecodingError.dataCorruptedError(
            in: container,
            debugDescription: "Expected ISO 8601 date with milliseconds and Z suffix"
        )
    }
    return date
}

let encoder = JSONEncoder()
encoder.dateEncodingStrategy = .custom { date, encoder in
    var container = encoder.singleValueContainer()
    try container.encode(ISO8601DateFormatter.protocolFormatter.string(from: date))
}
encoder.outputFormatting = [.sortedKeys]  // For canonical serialization
```

### 9.4 Canonical Serialization Implementation Notes

For hash chain computation (Section 3.7), both platforms MUST produce identical byte
output for the same logical payload:

- **kotlinx.serialization**: Use `Json { prettyPrint = false; explicitNulls = false }`
  and sort keys by configuring a custom serializer or by using `JsonObject` with
  sorted keys. Note: `kotlinx.serialization` does not sort keys by default. The
  recommended approach is to define a `canonicalize()` function that parses the JSON
  to a `JsonObject`, sorts keys recursively, and re-serializes.

- **Swift Codable**: Use `encoder.outputFormatting = [.sortedKeys]` which produces
  lexicographically sorted keys.

Both platforms MUST pass the canonical serialization conformance test vectors to verify
byte-identical output.

---

## 10. Versioning and Extensibility

### 10.1 Protocol Version

- The `protocolVersion` field in `OpLogEntry` identifies the wire format version.
- This specification defines **protocol version 1**.
- Clients MUST reject `OpLogEntry` objects with an unsupported `protocolVersion` and
  prompt the user to update the app.

### 10.2 Forward Compatibility

- Clients receiving an `OperationPayload` with an unknown `payloadType` MUST:
  1. Log a warning.
  2. Store the encrypted `OpLogEntry` in the local database (for hash chain integrity).
  3. Skip applying the payload to the local model.
  4. Continue processing subsequent ops.
- This allows newer clients to introduce new payload types without breaking older clients.

### 10.3 Backward Compatibility

- Protocol version N clients MUST support ops from version N and N-1.
- Deprecated payload types MUST be supported for decoding for at least one protocol
  version after deprecation.

### 10.4 Adding New Payload Types

New payload types are added by:

1. Defining the JSON schema for the new variant.
2. Adding the type name to the `payloadType` enum.
3. Incrementing the protocol version ONLY if the change is backward-incompatible
   (e.g., changing the envelope format). Adding new payload types within the same
   envelope format does NOT require a version bump.

---

## 11. Deferred Types (v0.2+)

The following operation types are explicitly excluded from protocol version 1 and will be
defined in a future specification amendment:

| Payload Type | Target Version | Description |
|-------------|----------------|-------------|
| `CreateInfoBankEntry` | v0.2 | Create a shared child record (medical, school, etc.) |
| `UpdateInfoBankEntry` | v0.2 | Update an existing info bank entry |
| `DeleteInfoBankEntry` | v0.2 | Soft-delete an info bank entry |

Clients MUST NOT generate these payload types in protocol version 1. If received (due to
a version mismatch), clients MUST handle them per the forward compatibility rules in
Section 10.2.

---

## 12. Complete Examples

### 12.1 Full Sync Upload (Client to Server)

A client uploads two operations in a single batch:

```
POST /sync/ops
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJFZDI1NTE5...

{
  "ops": [
    {
      "deviceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "devicePrevHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "protocolVersion": 1,
      "keyEpoch": 1,
      "encryptedPayload": "AAAAAAAAAAAAAAAA...base64-encoded-aes-256-gcm-ciphertext..."
    },
    {
      "deviceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "devicePrevHash": "7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069",
      "protocolVersion": 1,
      "keyEpoch": 1,
      "encryptedPayload": "BBBBBBBBBBBBBBBB...base64-encoded-aes-256-gcm-ciphertext..."
    }
  ]
}
```

Server response:

```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "accepted": [
    {
      "globalSequence": 42,
      "serverTimestamp": "2026-03-15T14:30:00.000Z"
    },
    {
      "globalSequence": 43,
      "serverTimestamp": "2026-03-15T14:30:00.001Z"
    }
  ]
}
```

### 12.2 Full Sync Pull (Server to Client)

```
GET /sync/ops?since=40&limit=10
Authorization: Bearer eyJhbGciOiJFZDI1NTE5...
```

```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "ops": [
    {
      "globalSequence": 41,
      "deviceId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
      "devicePrevHash": "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",
      "serverTimestamp": "2026-03-15T14:29:50.000Z",
      "protocolVersion": 1,
      "keyEpoch": 1,
      "encryptedPayload": "CCCCCCCCCCCCCCCC...base64..."
    },
    {
      "globalSequence": 42,
      "deviceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "devicePrevHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "serverTimestamp": "2026-03-15T14:30:00.000Z",
      "protocolVersion": 1,
      "keyEpoch": 1,
      "encryptedPayload": "AAAAAAAAAAAAAAAA...base64..."
    }
  ],
  "hasMore": false,
  "latestGlobalSequence": 42
}
```

### 12.3 Decrypted Payload Lifecycle: Expense with Receipt

**Step 1:** Upload receipt blob.

```
POST /blobs
Content-Type: multipart/form-data; boundary=----FormBoundary

------FormBoundary
Content-Disposition: form-data; name="file"; filename="receipt.enc"
Content-Type: application/octet-stream

<12-byte nonce><encrypted image bytes><16-byte GCM tag>
------FormBoundary
Content-Disposition: form-data; name="sha256"

a1b2c3d4e5f6...64-hex-characters...
------FormBoundary--
```

Response:

```json
{
  "blobId": "deadbeef-1234-5678-9abc-def012345678",
  "sizeBytes": 245760,
  "sha256": "a1b2c3d4e5f6...64-hex-characters...",
  "uploadedAt": "2026-03-18T16:20:00.000Z"
}
```

**Step 2:** Create expense OpLogEntry (decrypted payload shown):

```json
{
  "payloadType": "CreateExpense",
  "entityId": "a1234567-b890-cdef-1234-567890abcdef",
  "timestamp": "2026-03-18T16:22:00.000Z",
  "operationType": "CREATE",
  "expenseId": "a1234567-b890-cdef-1234-567890abcdef",
  "childId": "c1d2e3f4-5678-9abc-def0-123456789012",
  "paidByUserId": "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa",
  "amountCents": 4500,
  "currencyCode": "EUR",
  "category": "MEDICAL",
  "description": "Pediatrician visit - flu symptoms",
  "incurredAt": "2026-03-18",
  "payerResponsibilityRatio": 0.5,
  "receiptBlobId": "deadbeef-1234-5678-9abc-def012345678",
  "receiptDecryptionKey": "c2VjcmV0LWJsb2Ita2V5LTMyLWJ5dGVzLWhlcmUtcGFkZGVk"
}
```

**Step 3:** Other parent acknowledges:

```json
{
  "payloadType": "UpdateExpenseStatus",
  "entityId": "a1234567-b890-cdef-1234-567890abcdef",
  "timestamp": "2026-03-19T09:00:00.000Z",
  "operationType": "UPDATE",
  "expenseId": "a1234567-b890-cdef-1234-567890abcdef",
  "status": "ACKNOWLEDGED",
  "responderId": "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb"
}
```

### 12.4 Canonical Serialization Example

Given a `CreateEvent` payload, the canonical serialization (for hashing) is:

Input (logical):
```json
{
  "payloadType": "CreateEvent",
  "entityId": "11223344-5566-7788-99aa-bbccddeeff00",
  "timestamp": "2026-03-16T12:00:00.000Z",
  "operationType": "CREATE",
  "eventId": "11223344-5566-7788-99aa-bbccddeeff00",
  "childId": "c1d2e3f4-5678-9abc-def0-123456789012",
  "title": "Doctor visit",
  "date": "2026-04-10"
}
```

Canonical form (keys sorted lexicographically, compact, no whitespace):

```
{"childId":"c1d2e3f4-5678-9abc-def0-123456789012","date":"2026-04-10","entityId":"11223344-5566-7788-99aa-bbccddeeff00","eventId":"11223344-5566-7788-99aa-bbccddeeff00","operationType":"CREATE","payloadType":"CreateEvent","timestamp":"2026-03-16T12:00:00.000Z","title":"Doctor visit"}
```

SHA-256 of the UTF-8 bytes of the canonical form:

```
(computed by conformance test vectors -- see tests/conformance/)
```

---

## Appendix A: JSON Schema File Index

| Schema | Location | Description |
|--------|----------|-------------|
| OpLogEntry | `op-log-entry.schema.json` | Encrypted envelope |
| OperationPayload | `operation-payload.schema.json` | Discriminated union root |
| SetCustodySchedule | Inline in `operation-payload.schema.json` | Custody pattern |
| UpsertOverride | Inline | Schedule override |
| CreateExpense | Inline | New expense |
| UpdateExpenseStatus | Inline | Expense status change |
| CreateEvent | Inline | New calendar event |
| UpdateEvent | Inline | Event modification |
| CancelEvent | Inline | Event cancellation |
| DeviceSnapshot | Inline | Snapshot metadata |
| BlobUploadResponse | `blob-upload-response.schema.json` | Blob upload result |

## Appendix B: Error Response Format

All API error responses use a consistent JSON structure:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kidsync.app/protocol/v1/error-response.schema.json",
  "title": "ErrorResponse",
  "type": "object",
  "properties": {
    "error": {
      "type": "string",
      "description": "Machine-readable error code (e.g., BLOB_TOO_LARGE, HASH_MISMATCH)."
    },
    "message": {
      "type": "string",
      "description": "Human-readable error description. Clients SHOULD NOT parse this for logic."
    },
    "details": {
      "type": "object",
      "description": "Optional structured details about the error.",
      "additionalProperties": true
    }
  },
  "required": ["error", "message"],
  "additionalProperties": false
}
```

**Example:**

```json
{
  "error": "BLOB_TOO_LARGE",
  "message": "Uploaded file exceeds the 10 MB size limit.",
  "details": {
    "maxBytes": 10485760,
    "actualBytes": 12345678
  }
}
```

## Appendix C: Size Limits Summary

| Field / Resource | Limit | Rationale |
|-----------------|-------|-----------|
| `encryptedPayload` | 64 KB (Base64-encoded) | OpLog entries are small structured data |
| Blob file | 10 MB | Receipt photos and documents |
| `description` (expense) | 500 characters | Concise description |
| `title` (event) | 200 characters | Short title |
| `note` (override, expense status) | 2,000 characters | Explanatory text |
| `location` (event) | 500 characters | Address or description |
| `notes` (event) | 2,000 characters | Additional details |
| `cycleLengthDays` | 1-365 | Practical custody cycle limit |
| `pattern` array | 1-365 entries | Matches cycleLengthDays |
| Sync batch (`POST /sync/ops`) | 100 ops per request | Prevents oversized requests |
| Sync pull (`GET /sync/ops`) | 1,000 ops per response (paginated) | Bounded response size |
