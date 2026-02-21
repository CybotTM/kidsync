# Wire Format Specification

**Protocol Version:** 2
**Document Version:** 2.0.0
**Date:** 2026-02-21
**Status:** Draft (zero-knowledge architecture)
**Applies to:** All clients and the sync server
**Related documents:** `encryption-spec.md`, `sync-protocol.md`, `openapi.yaml`

---

## Table of Contents

1. [Overview](#1-overview)
2. [Conventions](#2-conventions)
3. [Serialization Rules](#3-serialization-rules)
4. [OpInput (Client-to-Server Envelope)](#4-opinput-client-to-server-envelope)
5. [OpOutput (Server-to-Client Envelope)](#5-opoutput-server-to-client-envelope)
6. [DecryptedPayload (Inside encryptedPayload)](#6-decryptedpayload-inside-encryptedpayload)
7. [Operation Payload Data Variants](#7-operation-payload-data-variants)
8. [Enumeration Types](#8-enumeration-types)
9. [Request and Response Formats](#9-request-and-response-formats)
10. [Binary Blob Protocol](#10-binary-blob-protocol)
11. [WebSocket Message Formats](#11-websocket-message-formats)
12. [Serialization Library Choices](#12-serialization-library-choices)
13. [Versioning and Extensibility](#13-versioning-and-extensibility)
14. [Complete Examples](#14-complete-examples)

---

## 1. Overview

This document defines the wire format for all data exchanged between clients and the KidSync sync server under the zero-knowledge architecture. Every mutation is recorded as an encrypted operation consisting of a minimal server-visible envelope and an opaque encrypted payload containing all metadata and business data.

**Key architectural constraints:**

- The server is a dumb relay. It stores encrypted blobs, assigns monotonic sequence numbers, and verifies hash chain integrity. It never decrypts payloads.
- The server sees only 5 fields per operation: `deviceId`, `keyEpoch`, `encryptedPayload`, `prevHash`, `currentHash`. All metadata (entity types, entity IDs, operation types, client timestamps, device sequence numbers, protocol versions) is inside the encrypted payload.
- There are no accounts, usernames, emails, or passwords. Devices are identified by Ed25519 signing keys.
- Binary assets (receipt photos, documents) are stored separately from the OpLog, scoped to a bucket, and referenced by blob ID.

### 1.1 What Changed from Protocol Version 1

| Aspect | v1 | v2 (this spec) |
|--------|------|------|
| Op envelope fields | 13+ fields (entityType, entityId, operation, clientTimestamp, deviceSequence, protocolVersion, transitionTo, signature visible to server) | 5 fields (deviceId, keyEpoch, encryptedPayload, prevHash, currentHash) |
| Auth token | JWT with sub, did, fam claims | Opaque session token (not JWT) |
| Storage namespace | Family (with name, roles) | Anonymous bucket |
| State machine | Server-side `OverrideStates` table | Client-side deterministic replay |
| Blob scope | Global (`/blobs`) | Per-bucket (`/buckets/{id}/blobs`) |
| Hash chain field name | `devicePrevHash` | `prevHash` |

---

## 2. Conventions

### 2.1 Terminology

| Term | Meaning |
|------|---------|
| MUST / MUST NOT | Absolute requirement per RFC 2119 |
| SHOULD / SHOULD NOT | Recommended but not absolute |
| **OpInput** | The minimal encrypted envelope sent from client to server (5 fields) |
| **OpOutput** | The server-enriched envelope returned to clients (adds globalSequence, serverTimestamp) |
| **DecryptedPayload** | The plaintext JSON inside `encryptedPayload`, containing all metadata and business data |
| **Bucket** | Anonymous, opaque storage namespace (replaces families) |
| **DEK** | Data Encryption Key (per-bucket symmetric key) |
| **Key epoch** | Monotonic version number for a DEK generation |
| **Session token** | Opaque server-signed token (not JWT) containing only deviceId, signingKey, and expiry |

### 2.2 Notation

- JSON Schema uses the 2020-12 draft vocabulary.
- All examples use pretty-printed JSON for readability. Implementations MUST NOT rely on whitespace.
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

- Implementations MUST NOT depend on field ordering for correctness when parsing.
- For **canonical serialization** (used before encryption), fields MUST be serialized in lexicographic order of their JSON key names at every nesting level. See [Section 3.7](#37-canonical-serialization-for-encryption).

### 3.3 Null Handling

- Fields with `null` values MUST be **omitted** from the serialized JSON, not included as explicit `null`. This reduces payload size and simplifies cross-platform handling.
- Receivers MUST treat a missing optional field as `null`.

### 3.4 Date and Time Formats

All temporal values follow ISO 8601 with the following specific rules:

| Type | Format | Example | Usage |
|------|--------|---------|-------|
| `Instant` (UTC timestamp) | `YYYY-MM-DDThh:mm:ss.sssZ` | `2026-03-15T14:30:00.000Z` | clientTimestamp inside DecryptedPayload, effectiveFrom, serverTimestamp |
| `LocalDate` | `YYYY-MM-DD` | `2026-03-15` | Calendar dates (anchorDate, expense incurredAt, event date) |
| `LocalTime` | `hh:mm:ss` | `09:30:00` | Event times (24-hour format) |
| `ZoneId` | IANA timezone identifier | `Europe/Berlin` | Timezone for custody schedule interpretation |

**Rules:**

- `Instant` values MUST always include the `Z` suffix (UTC). Offsets like `+02:00` MUST NOT be used.
- `Instant` values MUST include exactly 3 fractional digits (millisecond precision). Implementations MUST zero-pad if the source has sub-millisecond or no fractional precision (e.g., `14:30:00` becomes `14:30:00.000Z`).
- `LocalDate` values carry no timezone or offset.
- `LocalTime` values MUST use 24-hour format with seconds. No fractional seconds.
- `ZoneId` MUST be a valid IANA Time Zone Database identifier (e.g., `America/New_York`, `Europe/Berlin`). Abbreviations like `EST`, `CET` MUST NOT be used.

### 3.5 String Formats

| Field type | Format | Validation |
|------------|--------|------------|
| UUID | Lowercase hex with hyphens: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` | RFC 4122 v4 |
| SHA-256 hash | Lowercase hex, 64 characters | `^[0-9a-f]{64}$` |
| Base64 | Standard Base64 per RFC 4648 Section 4, with padding (`=`) | No line breaks |
| Base64url | URL-safe Base64 per RFC 4648 Section 5, no padding | No line breaks |
| Ed25519 public key | Base64, 32 bytes decoded | 44 characters encoded |
| X25519 public key | Base64, 32 bytes decoded | 44 characters encoded |
| Currency code | ISO 4217 uppercase, 3 characters | `^[A-Z]{3}$` |

### 3.6 Numeric Types

- Integer values (sequence numbers, amounts in cents, cycle lengths) MUST be represented as JSON numbers without fractional parts.
- `amountCents` is a signed 64-bit integer representing the monetary amount in the smallest currency unit (e.g., cents for EUR/USD).
- `payerResponsibilityRatio` is a JSON number in the range `[0.0, 1.0]` with at most 4 decimal places.
- `cycleLengthDays` MUST be a positive integer >= 1.

### 3.7 Canonical Serialization for Encryption

The **canonical serialization** of the `DecryptedPayload` is used as the plaintext for compression and subsequent AES-256-GCM encryption:

1. Serialize the `DecryptedPayload` as JSON.
2. Object keys MUST be sorted lexicographically (Unicode code point order) at every nesting level.
3. No whitespace between tokens (compact form).
4. Null/absent fields MUST be omitted (not serialized as `null`).
5. The resulting UTF-8 byte sequence is the canonical form.

**Note on the hash chain:** The per-device hash chain is computed over the **encrypted payload bytes** (ciphertext), NOT the plaintext. See Section 4.2 for the formula. This allows the server to verify hash chain integrity without decrypting.

### 3.8 Compression

Implementations MUST gzip-compress (RFC 1952) the canonical JSON payload **before** AES-256-GCM encryption, and gunzip-decompress **after** decryption.

```
Encryption:  canonical JSON bytes -> gzip compress -> AES-256-GCM encrypt -> Base64
Decryption:  Base64 -> AES-256-GCM decrypt -> gunzip decompress -> JSON bytes
```

See `encryption-spec.md` Section 13 for the full encryption/decryption pipeline including compression.

---

## 4. OpInput (Client-to-Server Envelope)

The `OpInput` is the minimal envelope sent from client to server when uploading operations. The server can read these 5 fields but cannot decrypt the payload. All metadata and business data is inside `encryptedPayload`.

### 4.1 JSON Schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kidsync.app/protocol/v2/op-input.schema.json",
  "title": "OpInput",
  "description": "Minimal encrypted envelope for uploading a single operation. All metadata (entityType, entityId, operation, clientTimestamp, deviceSequence, protocolVersion) is inside the encryptedPayload.",
  "type": "object",
  "required": ["deviceId", "keyEpoch", "encryptedPayload", "prevHash", "currentHash"],
  "properties": {
    "deviceId": {
      "type": "string",
      "format": "uuid",
      "description": "UUID of the device that created this operation. Must match the authenticated device."
    },
    "keyEpoch": {
      "type": "integer",
      "minimum": 1,
      "description": "The DEK epoch (version) used to encrypt this payload. Receivers use this to select the correct decryption key."
    },
    "encryptedPayload": {
      "type": "string",
      "contentEncoding": "base64",
      "description": "Base64-encoded AES-256-GCM ciphertext containing the canonical DecryptedPayload JSON. The first 12 bytes of the decoded value are the GCM nonce, followed by the ciphertext, followed by the 16-byte GCM authentication tag. All metadata and business data is inside this field."
    },
    "prevHash": {
      "type": "string",
      "pattern": "^[0-9a-f]{64}$",
      "description": "SHA-256 hash of the previous operation from this same device. For the first operation from a device, this is 64 hex zeros (representing 32 zero bytes)."
    },
    "currentHash": {
      "type": "string",
      "pattern": "^[0-9a-f]{64}$",
      "description": "SHA256(hexDecode(prevHash) + base64Decode(encryptedPayload)). Computed over ciphertext bytes, allowing the server and other clients to verify hash chain integrity without decrypting."
    }
  },
  "additionalProperties": false
}
```

### 4.2 Field Details

#### `deviceId`

- Must match the `deviceId` in the authenticated session token.
- The server verifies this match and rejects mismatches.

#### `keyEpoch`

- References a specific DEK version for this bucket.
- The server does not know the DEK itself but validates that the epoch is known (has wrapped keys uploaded for it).

#### `encryptedPayload`

All of the following are INSIDE this encrypted blob and invisible to the server:

- `deviceSequence` (per-device monotonic counter)
- `entityType` (CustodySchedule, ScheduleOverride, Expense, etc.)
- `entityId` (UUID of the entity)
- `operation` (CREATE, UPDATE, DELETE)
- `clientTimestamp` (device's UTC wall-clock time)
- `protocolVersion` (wire format version)
- `data` (entity-specific business fields)

**Binary layout of the decoded payload:**

```
| Nonce (12 bytes) | Ciphertext (variable) | GCM Auth Tag (16 bytes) |
```

The entire concatenation is Base64-encoded into the `encryptedPayload` string field.

#### `prevHash`

- Forms a per-device hash chain for tamper detection.
- For the very first operation from a device, `prevHash` is 64 hex zeros: `"0000000000000000000000000000000000000000000000000000000000000000"`
- The hash chain uses the **encrypted payload** (ciphertext), not the plaintext.

#### `currentHash`

Computed as:

```
currentHash = SHA256(hexDecode(prevHash) + base64Decode(encryptedPayload))
```

Where:
- `hexDecode(prevHash)` is the 32-byte binary representation of the hex-encoded previous hash.
- `base64Decode(encryptedPayload)` is the raw encrypted payload bytes after Base64 decoding.
- `+` denotes byte concatenation.

The server validates `currentHash` by recomputing and comparing. Clients MUST also validate the chain on sync pull.

### 4.3 What Disappeared from the Envelope

These fields were in the v1 `OpLogEntry` envelope (visible to server) and are now inside `encryptedPayload`:

| Removed from envelope | Now inside encrypted payload |
|----------------------|------------------------------|
| `deviceSequence` | `DecryptedPayload.deviceSequence` |
| `entityType` | `DecryptedPayload.entityType` |
| `entityId` | `DecryptedPayload.entityId` |
| `operation` | `DecryptedPayload.operation` |
| `clientTimestamp` | `DecryptedPayload.clientTimestamp` |
| `protocolVersion` | `DecryptedPayload.protocolVersion` |
| `transitionTo` | `DecryptedPayload.data.transitionTo` |
| `signature` | Not needed (challenge-response auth replaces per-op signatures) |

### 4.4 Example OpInput

```json
{
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "keyEpoch": 1,
  "encryptedPayload": "dGhpcyBpcyBhIHBsYWNlaG9sZGVyIGZvciBhY3R1YWwgQUVTLTI1Ni1HQ00gY2lwaGVydGV4dA==",
  "prevHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "currentHash": "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a"
}
```

---

## 5. OpOutput (Server-to-Client Envelope)

The `OpOutput` is the enriched envelope returned by the server when clients pull operations. It adds the server-assigned `globalSequence` and `serverTimestamp` to the 5 fields from `OpInput`.

### 5.1 JSON Schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kidsync.app/protocol/v2/op-output.schema.json",
  "title": "OpOutput",
  "description": "Server-enriched operation envelope returned during pull. Adds globalSequence and serverTimestamp to the OpInput fields.",
  "type": "object",
  "required": ["globalSequence", "deviceId", "keyEpoch", "encryptedPayload", "prevHash", "currentHash", "serverTimestamp"],
  "properties": {
    "globalSequence": {
      "type": "integer",
      "minimum": 1,
      "description": "Server-assigned monotonic sequence number, unique per bucket, strictly increasing with no gaps."
    },
    "deviceId": {
      "type": "string",
      "format": "uuid",
      "description": "UUID of the device that created this operation."
    },
    "keyEpoch": {
      "type": "integer",
      "minimum": 1,
      "description": "The DEK epoch used to encrypt this payload."
    },
    "encryptedPayload": {
      "type": "string",
      "contentEncoding": "base64",
      "description": "Base64-encoded AES-256-GCM ciphertext. Identical to the value uploaded by the client."
    },
    "prevHash": {
      "type": "string",
      "pattern": "^[0-9a-f]{64}$",
      "description": "Per-device hash chain previous hash."
    },
    "currentHash": {
      "type": "string",
      "pattern": "^[0-9a-f]{64}$",
      "description": "Per-device hash chain current hash."
    },
    "serverTimestamp": {
      "type": "string",
      "format": "date-time",
      "description": "UTC timestamp assigned by the server when the operation was persisted."
    }
  },
  "additionalProperties": false
}
```

### 5.2 Field Details

#### `globalSequence`

- Assigned atomically by the server upon receipt.
- Strictly monotonically increasing within a bucket, starting at 1, no gaps.
- Clients MUST NOT set this field when uploading (it is absent from `OpInput`).
- Once assigned, the value is immutable.

#### `serverTimestamp`

- The server's UTC wall-clock time when the operation was persisted.
- This is the ONLY timestamp visible to the server. The client's timestamp (`clientTimestamp`) is inside the encrypted payload.

### 5.3 Example OpOutput

```json
{
  "globalSequence": 42,
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "keyEpoch": 1,
  "encryptedPayload": "dGhpcyBpcyBhIHBsYWNlaG9sZGVyIGZvciBhY3R1YWwgQUVTLTI1Ni1HQ00gY2lwaGVydGV4dA==",
  "prevHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "currentHash": "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
  "serverTimestamp": "2026-03-15T14:30:00.000Z"
}
```

---

## 6. DecryptedPayload (Inside encryptedPayload)

After decrypting `encryptedPayload`, the resulting JSON conforms to the `DecryptedPayload` schema. This contains ALL metadata (formerly in the envelope) and a generic `data` object with entity-specific fields.

### 6.1 JSON Schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kidsync.app/protocol/v2/decrypted-payload.schema.json",
  "title": "DecryptedPayload",
  "description": "Plaintext content inside the encrypted payload. Contains all metadata (entity type, operation type, timestamps) and entity-specific business data.",
  "type": "object",
  "required": ["deviceSequence", "entityType", "entityId", "operation", "clientTimestamp", "protocolVersion", "data"],
  "properties": {
    "deviceSequence": {
      "type": "integer",
      "minimum": 1,
      "description": "Per-device monotonically increasing counter, starting at 1. Used for idempotency detection: each op is uniquely identified by (deviceId, deviceSequence)."
    },
    "entityType": {
      "type": "string",
      "description": "The type of entity this operation targets. Standard values: CustodySchedule, ScheduleOverride, Expense, ExpenseStatus, CalendarEvent, InfoBankEntry, DeviceRevocation, KeyRotation."
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
    "clientTimestamp": {
      "type": "string",
      "format": "date-time",
      "description": "UTC timestamp from the originating device when the operation was created. Used for conflict resolution tie-breaking."
    },
    "protocolVersion": {
      "type": "integer",
      "minimum": 1,
      "description": "Protocol version under which this payload was created. Current version is 2."
    },
    "data": {
      "type": "object",
      "description": "Entity-specific fields. The structure depends on entityType and operation. See Section 7 for variants."
    }
  },
  "additionalProperties": false
}
```

### 6.2 Common Field Semantics

#### `deviceSequence`

- Per-device monotonic counter starting at 1.
- Incremented by 1 for each op created by the device.
- Used for idempotency: `(deviceId, deviceSequence)` uniquely identifies an op.
- If a client encounters a duplicate `(deviceId, deviceSequence)`, it MUST skip it silently.

#### `entityType`

Standard entity types for protocol version 2:

| Value | Description |
|-------|-------------|
| `CustodySchedule` | Custody pattern definition |
| `ScheduleOverride` | Schedule override (swap, holiday, court order) |
| `Expense` | Child-related expense |
| `ExpenseStatus` | Expense status update (acknowledge, dispute) |
| `CalendarEvent` | Calendar event (appointment, school event) |
| `InfoBankEntry` | Shared child information record |
| `DeviceRevocation` | Client-side device revocation notice |
| `KeyRotation` | DEK rotation notification |

Clients MUST handle unknown entity types gracefully: store the encrypted op for hash chain integrity and skip applying it to the local model.

#### `operation`

- `CREATE`: New entity. Applying a CREATE for an entity that already exists is idempotent (deduplicate by entityId).
- `UPDATE`: Mutation of existing entity. Applying UPDATE for a nonexistent entity is a recoverable error (request re-sync).
- `DELETE`: Soft-delete. The entity is marked as cancelled/deleted but preserved in the log.

#### `clientTimestamp`

- Wall-clock time on the originating device at the moment the operation was created.
- Always UTC, always millisecond precision.
- Used for conflict resolution tie-breaking (see sync-protocol.md).
- Clients SHOULD use NTP-synchronized clocks.

#### `data`

Entity-specific fields. The schema depends on the `entityType` and `operation`. See Section 7 for all variants.

### 6.3 Example DecryptedPayload

```json
{
  "deviceSequence": 47,
  "entityType": "CustodySchedule",
  "entityId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "operation": "CREATE",
  "clientTimestamp": "2026-02-21T14:30:00.000Z",
  "protocolVersion": 2,
  "data": {
    "scheduleId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
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
}
```

---

## 7. Operation Payload Data Variants

The `data` field in `DecryptedPayload` contains entity-specific fields. This section defines the schema for each entity type.

### 7.1 CustodySchedule (CREATE / UPDATE)

Creates or replaces a custody schedule pattern for a child.

```json
{
  "$id": "https://kidsync.app/protocol/v2/data/custody-schedule.schema.json",
  "type": "object",
  "required": ["scheduleId", "childId", "anchorDate", "cycleLengthDays", "pattern", "effectiveFrom", "timeZone"],
  "properties": {
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
      "description": "Start date of the first cycle (LocalDate)."
    },
    "cycleLengthDays": {
      "type": "integer",
      "minimum": 1,
      "maximum": 365,
      "description": "Number of days in one complete cycle."
    },
    "pattern": {
      "type": "array",
      "items": { "type": "string", "format": "uuid" },
      "minItems": 1,
      "description": "Ordered list of parent identifiers, one per day. Length MUST equal cycleLengthDays."
    },
    "effectiveFrom": {
      "type": "string",
      "format": "date-time",
      "description": "UTC instant from which this schedule takes effect. Used for conflict resolution."
    },
    "timeZone": {
      "type": "string",
      "description": "IANA timezone identifier for interpreting anchorDate and day boundaries."
    }
  },
  "additionalProperties": false
}
```

**Validation rules:**
- `pattern.length` MUST equal `cycleLengthDays`.
- `scheduleId` MUST equal `entityId` in the parent `DecryptedPayload`.
- `operation` MUST be `CREATE` or `UPDATE`. Schedules are never deleted; they are superseded.

### 7.2 ScheduleOverride (CREATE / UPDATE)

Creates or updates a schedule override (swap request, holiday rule, court order, or manual override).

```json
{
  "$id": "https://kidsync.app/protocol/v2/data/schedule-override.schema.json",
  "type": "object",
  "required": ["overrideId", "type", "childId", "startDate", "endDate", "assignedParentId", "status", "proposerId"],
  "properties": {
    "overrideId": {
      "type": "string",
      "format": "uuid",
      "description": "Same value as entityId."
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
      "description": "First day of the override period (inclusive)."
    },
    "endDate": {
      "type": "string",
      "format": "date",
      "description": "Last day of the override period (inclusive)."
    },
    "assignedParentId": {
      "type": "string",
      "format": "uuid",
      "description": "Parent assigned custody for this date range."
    },
    "status": {
      "type": "string",
      "enum": ["PROPOSED", "APPROVED", "DECLINED", "CANCELLED", "SUPERSEDED", "EXPIRED"],
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
      "description": "The parent who approved or declined. Absent for PROPOSED status."
    },
    "transitionTo": {
      "type": "string",
      "enum": ["APPROVED", "DECLINED", "CANCELLED", "SUPERSEDED", "EXPIRED"],
      "description": "For UPDATE operations, the target state. The client-side state machine validates transitions."
    },
    "note": {
      "type": "string",
      "maxLength": 2000,
      "description": "Optional note explaining the reason for the override."
    }
  },
  "additionalProperties": false
}
```

**State machine transitions (client-side):**

```
PROPOSED -> APPROVED     (by any device except proposer)
PROPOSED -> DECLINED     (by any device except proposer)
PROPOSED -> CANCELLED    (by proposer device only)
PROPOSED -> EXPIRED      (by any device, when clientTimestamp + TTL < now)
PROPOSED -> SUPERSEDED   (automatic: new PROPOSED for same date range)
```

Terminal states (DECLINED, CANCELLED, SUPERSEDED, EXPIRED) are immutable. Ops attempting to transition from a terminal state are silently ignored by the client.

**Validation rules:**
- `overrideId` MUST equal `entityId`.
- `startDate` MUST be <= `endDate`.
- CREATE operations MUST have `status` = `PROPOSED` (or `APPROVED` for `COURT_ORDER`).
- UPDATE operations MUST include `transitionTo`.
- `responderId` MUST be present when `status` is `APPROVED` or `DECLINED`.

### 7.3 Expense (CREATE only)

Logs a new child-related expense. Expenses are append-only; status changes use ExpenseStatus.

```json
{
  "$id": "https://kidsync.app/protocol/v2/data/expense.schema.json",
  "type": "object",
  "required": ["expenseId", "childId", "paidByDeviceId", "amountCents", "currencyCode", "category", "description", "incurredAt", "payerResponsibilityRatio"],
  "properties": {
    "expenseId": {
      "type": "string",
      "format": "uuid",
      "description": "Same value as entityId."
    },
    "childId": {
      "type": "string",
      "format": "uuid",
      "description": "The child this expense relates to."
    },
    "paidByDeviceId": {
      "type": "string",
      "format": "uuid",
      "description": "Device identifier of the parent who paid. In the zero-knowledge model, device IDs serve as identity."
    },
    "amountCents": {
      "type": "integer",
      "minimum": 1,
      "description": "Amount in smallest currency unit (e.g., cents). Must be positive."
    },
    "currencyCode": {
      "type": "string",
      "pattern": "^[A-Z]{3}$",
      "description": "ISO 4217 currency code."
    },
    "category": {
      "type": "string",
      "enum": ["MEDICAL", "EDUCATION", "CLOTHING", "ACTIVITIES", "FOOD", "TRANSPORT", "CHILDCARE", "OTHER"],
      "description": "Expense category."
    },
    "description": {
      "type": "string",
      "minLength": 1,
      "maxLength": 500,
      "description": "Human-readable description."
    },
    "incurredAt": {
      "type": "string",
      "format": "date",
      "description": "Date the expense was incurred (LocalDate)."
    },
    "payerResponsibilityRatio": {
      "type": "number",
      "minimum": 0.0,
      "maximum": 1.0,
      "description": "Fraction of the expense that is the payer's responsibility. 0.5 = 50/50 split."
    },
    "receiptBlobId": {
      "type": "string",
      "format": "uuid",
      "description": "Reference to the encrypted receipt blob. Absent if no receipt."
    },
    "receiptBlobKey": {
      "type": "string",
      "contentEncoding": "base64",
      "description": "Base64-encoded per-blob AES-256 key for decrypting the receipt."
    },
    "receiptBlobNonce": {
      "type": "string",
      "contentEncoding": "base64",
      "description": "Base64-encoded 12-byte nonce used for receipt blob encryption."
    }
  },
  "additionalProperties": false
}
```

**Validation rules:**
- `expenseId` MUST equal `entityId`.
- `operation` MUST be `CREATE`.
- `receiptBlobId`, `receiptBlobKey`, and `receiptBlobNonce` MUST all be present or all be absent.

### 7.4 ExpenseStatus (UPDATE only)

Changes the status of an existing expense.

```json
{
  "$id": "https://kidsync.app/protocol/v2/data/expense-status.schema.json",
  "type": "object",
  "required": ["expenseId", "status", "responderDeviceId"],
  "properties": {
    "expenseId": {
      "type": "string",
      "format": "uuid",
      "description": "Same value as entityId. The expense being updated."
    },
    "status": {
      "type": "string",
      "enum": ["LOGGED", "ACKNOWLEDGED", "DISPUTED"],
      "description": "New status."
    },
    "responderDeviceId": {
      "type": "string",
      "format": "uuid",
      "description": "Device of the parent responding (must be a non-paying parent's device)."
    },
    "note": {
      "type": "string",
      "maxLength": 2000,
      "description": "Optional note, typically used when disputing."
    }
  },
  "additionalProperties": false
}
```

**Validation rules:**
- `expenseId` MUST equal `entityId`.
- `operation` MUST be `UPDATE`.
- Valid transitions: `LOGGED` -> `ACKNOWLEDGED`, `LOGGED` -> `DISPUTED`.

### 7.5 CalendarEvent (CREATE / UPDATE / DELETE)

Calendar events for a child (appointments, school events, etc.).

**CREATE and UPDATE:**

```json
{
  "$id": "https://kidsync.app/protocol/v2/data/calendar-event.schema.json",
  "type": "object",
  "required": ["eventId", "childId", "title", "date"],
  "properties": {
    "eventId": {
      "type": "string",
      "format": "uuid",
      "description": "Same value as entityId."
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
      "description": "Short title."
    },
    "date": {
      "type": "string",
      "format": "date",
      "description": "Date of the event (LocalDate)."
    },
    "time": {
      "type": "string",
      "pattern": "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$",
      "description": "Time of the event (24-hour HH:mm:ss). Absent for all-day events."
    },
    "location": {
      "type": "string",
      "maxLength": 500,
      "description": "Optional location."
    },
    "notes": {
      "type": "string",
      "maxLength": 2000,
      "description": "Optional additional notes."
    }
  },
  "additionalProperties": false
}
```

**DELETE:** For `operation` = `DELETE`, the `data` field requires only `eventId`:

```json
{
  "type": "object",
  "required": ["eventId"],
  "properties": {
    "eventId": { "type": "string", "format": "uuid" }
  },
  "additionalProperties": false
}
```

**Semantics:**
- `UPDATE` is a full replacement of mutable fields, not a partial patch.
- `DELETE` is a soft-delete. Cancelled events remain in the OpLog.
- Conflict resolution: last-write-wins based on `clientTimestamp`, with `deviceId` lexicographic tie-break.

### 7.6 InfoBankEntry (CREATE / UPDATE / DELETE)

Shared child information records (medical, school, contacts, etc.).

```json
{
  "$id": "https://kidsync.app/protocol/v2/data/info-bank-entry.schema.json",
  "type": "object",
  "required": ["entryId", "childId", "category", "title"],
  "properties": {
    "entryId": {
      "type": "string",
      "format": "uuid",
      "description": "Same value as entityId."
    },
    "childId": {
      "type": "string",
      "format": "uuid",
      "description": "The child this entry relates to."
    },
    "category": {
      "type": "string",
      "enum": ["MEDICAL", "EDUCATION", "CONTACT", "LEGAL", "ALLERGY", "INSURANCE", "OTHER"],
      "description": "Information category."
    },
    "title": {
      "type": "string",
      "minLength": 1,
      "maxLength": 200,
      "description": "Short title for the entry."
    },
    "content": {
      "type": "string",
      "maxLength": 10000,
      "description": "The information content (free text)."
    },
    "attachmentBlobId": {
      "type": "string",
      "format": "uuid",
      "description": "Reference to an encrypted attachment blob."
    },
    "attachmentBlobKey": {
      "type": "string",
      "contentEncoding": "base64",
      "description": "Per-blob AES-256 key for decrypting the attachment."
    },
    "attachmentBlobNonce": {
      "type": "string",
      "contentEncoding": "base64",
      "description": "12-byte nonce for attachment blob encryption."
    }
  },
  "additionalProperties": false
}
```

### 7.7 DeviceRevocation (CREATE only)

Client-side device revocation notice. Published by an "admin" device to signal that another device should be excluded from future DEK wrapping.

```json
{
  "$id": "https://kidsync.app/protocol/v2/data/device-revocation.schema.json",
  "type": "object",
  "required": ["revokedDeviceId", "reason"],
  "properties": {
    "revokedDeviceId": {
      "type": "string",
      "format": "uuid",
      "description": "The device being revoked from future DEK wrapping."
    },
    "reason": {
      "type": "string",
      "maxLength": 500,
      "description": "Reason for revocation (e.g., 'device lost', 'co-parent removed')."
    }
  },
  "additionalProperties": false
}
```

**Semantics:**
- The server does NOT enforce this. It is a client-side convention.
- Upon observing a DeviceRevocation op, remaining devices stop wrapping the DEK for the revoked device's key and rotate the DEK to a new epoch.

### 7.8 KeyRotation (CREATE only)

Notification that the DEK has been rotated to a new epoch. Published by the device that initiated the rotation.

```json
{
  "$id": "https://kidsync.app/protocol/v2/data/key-rotation.schema.json",
  "type": "object",
  "required": ["newEpoch", "reason"],
  "properties": {
    "newEpoch": {
      "type": "integer",
      "minimum": 2,
      "description": "The new DEK epoch number."
    },
    "reason": {
      "type": "string",
      "enum": ["DEVICE_REVOKED", "SCHEDULED", "COMPROMISE_SUSPECTED"],
      "description": "Reason for the rotation."
    },
    "revokedDeviceId": {
      "type": "string",
      "format": "uuid",
      "description": "If reason is DEVICE_REVOKED, the device that triggered rotation."
    }
  },
  "additionalProperties": false
}
```

---

## 8. Enumeration Types

All enumeration values are serialized as uppercase strings (except `entityType` which uses PascalCase). Clients MUST handle unknown enum values gracefully: store the op for hash chain integrity and skip applying it.

### 8.1 OverrideType

| Value | Description |
|-------|-------------|
| `SWAP_REQUEST` | Parent-initiated date swap |
| `HOLIDAY_RULE` | Recurring holiday assignment |
| `COURT_ORDER` | Court-mandated schedule change |
| `MANUAL_OVERRIDE` | One-off exception |

### 8.2 OverrideStatus

| Value | Terminal? | Description |
|-------|-----------|-------------|
| `PROPOSED` | No | Initial state, awaiting response |
| `APPROVED` | No | Accepted; active for the date range |
| `DECLINED` | Yes | Rejected |
| `CANCELLED` | Yes | Withdrawn by proposer |
| `SUPERSEDED` | Yes | Replaced by higher-precedence override |
| `EXPIRED` | Yes | Date range has passed |

### 8.3 ExpenseCategory

| Value | Description |
|-------|-------------|
| `MEDICAL` | Doctor visits, prescriptions, therapy |
| `EDUCATION` | School fees, tutoring, supplies |
| `CLOTHING` | Clothes and shoes |
| `ACTIVITIES` | Sports, music lessons, camps |
| `FOOD` | Groceries, school lunches |
| `TRANSPORT` | Travel, gas, public transit |
| `CHILDCARE` | Daycare, babysitting, after-school care |
| `OTHER` | Anything not in above categories |

### 8.4 ExpenseStatus

| Value | Description |
|-------|-------------|
| `LOGGED` | Initial state when expense is created |
| `ACKNOWLEDGED` | Non-paying parent confirms |
| `DISPUTED` | Non-paying parent disputes |

### 8.5 OperationType

| Value | Description |
|-------|-------------|
| `CREATE` | New entity |
| `UPDATE` | Mutation of existing entity |
| `DELETE` | Soft-delete |

### 8.6 InfoBankCategory

| Value | Description |
|-------|-------------|
| `MEDICAL` | Medical records, doctor contacts |
| `EDUCATION` | School information, grades |
| `CONTACT` | Emergency contacts, family members |
| `LEGAL` | Legal documents, custody agreements |
| `ALLERGY` | Allergies and dietary restrictions |
| `INSURANCE` | Insurance policy information |
| `OTHER` | Anything not in above categories |

---

## 9. Request and Response Formats

This section defines the JSON wire format for all REST API request and response bodies. See `openapi.yaml` for the complete HTTP-level contract.

### 9.1 Device Registration

**Request: `POST /register`**

```json
{
  "signingKey": "<base64, 32-byte Ed25519 public key>",
  "encryptionKey": "<base64, 32-byte X25519 public key>"
}
```

**Response: `201 Created`**

```json
{
  "deviceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### 9.2 Authentication Challenge

**Request: `POST /auth/challenge`**

```json
{
  "signingKey": "<base64, 32-byte Ed25519 public key>"
}
```

**Response: `200 OK`**

```json
{
  "nonce": "<base64, 32 bytes>",
  "expiresAt": "2026-02-21T14:31:00.000Z"
}
```

### 9.3 Authentication Verify

**Request: `POST /auth/verify`**

```json
{
  "signingKey": "<base64, 32-byte Ed25519 public key>",
  "nonce": "<base64, 32 bytes>",
  "timestamp": "2026-02-21T14:30:00.000Z",
  "signature": "<base64, 64-byte Ed25519 signature>"
}
```

**Response: `200 OK`**

```json
{
  "sessionToken": "<opaque-token-string>",
  "expiresIn": 3600
}
```

### 9.4 Create Bucket

**Request: `POST /buckets`**

No request body required. Authentication: `Authorization: Bearer <session-token>`.

**Response: `201 Created`**

```json
{
  "bucketId": "d4e5f6a7-b8c9-0123-4567-890abcdef012"
}
```

### 9.5 Register Invite Token

**Request: `POST /buckets/{id}/invite`**

```json
{
  "tokenHash": "<hex, 64 chars, SHA256 of plaintext invite token>"
}
```

**Response: `201 Created`**

```json
{
  "expiresAt": "2026-02-22T14:30:00.000Z"
}
```

### 9.6 Join Bucket

**Request: `POST /buckets/{id}/join`**

```json
{
  "inviteToken": "<plaintext invite token, base64url, 43 chars>"
}
```

**Response: `200 OK`**

```json
{
  "bucketId": "d4e5f6a7-b8c9-0123-4567-890abcdef012",
  "deviceId": "772a0622-a41d-43f6-c938-668877992222"
}
```

### 9.7 List Bucket Devices

**Request: `GET /buckets/{id}/devices`**

**Response: `200 OK`**

```json
{
  "devices": [
    {
      "deviceId": "550e8400-e29b-41d4-a716-446655440000",
      "signingKey": "<base64, Ed25519 public key>",
      "encryptionKey": "<base64, X25519 public key>",
      "grantedAt": "2026-02-21T12:00:00.000Z"
    }
  ]
}
```

### 9.8 Upload Operations

**Request: `POST /buckets/{id}/ops`**

```json
{
  "ops": [
    {
      "deviceId": "550e8400-e29b-41d4-a716-446655440000",
      "keyEpoch": 1,
      "encryptedPayload": "<base64>",
      "prevHash": "<hex, 64 chars>",
      "currentHash": "<hex, 64 chars>"
    }
  ]
}
```

**Response: `201 Created`**

```json
{
  "accepted": [
    {
      "index": 0,
      "globalSequence": 1043,
      "serverTimestamp": "2026-02-21T14:30:01.123Z"
    }
  ]
}
```

**Response: `207 Multi-Status`**

```json
{
  "accepted": [...],
  "rejected": [
    {
      "index": 1,
      "error": "HASH_CHAIN_BREAK",
      "message": "Expected prevHash 'abc123...' but got 'def456...'"
    }
  ]
}
```

### 9.9 Pull Operations

**Request: `GET /buckets/{id}/ops?since=1000&limit=100`**

**Response: `200 OK`**

```json
{
  "ops": [
    {
      "globalSequence": 1001,
      "deviceId": "550e8400-e29b-41d4-a716-446655440000",
      "keyEpoch": 1,
      "encryptedPayload": "<base64>",
      "prevHash": "<hex, 64 chars>",
      "currentHash": "<hex, 64 chars>",
      "serverTimestamp": "2026-02-21T14:30:01.123Z"
    }
  ],
  "hasMore": false,
  "latestSequence": 1043
}
```

### 9.10 Checkpoint

**Request: `GET /buckets/{id}/checkpoint`**

**Response: `200 OK`**

```json
{
  "checkpoint": {
    "startSequence": 901,
    "endSequence": 1000,
    "hash": "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",
    "timestamp": "2026-02-21T14:25:00.000Z",
    "opCount": 100
  },
  "latestSequence": 1043,
  "nextCheckpointAt": 1100
}
```

### 9.11 Upload Wrapped DEK

**Request: `POST /keys/wrapped`**

```json
{
  "targetDevice": "772a0622-a41d-43f6-c938-668877992222",
  "keyEpoch": 1,
  "wrappedDek": "<base64, wrapped DEK envelope>"
}
```

**Response: `201 Created`**

No body.

### 9.12 Get Wrapped DEK

**Request: `GET /keys/wrapped?epoch=1`**

**Response: `200 OK`**

```json
{
  "wrappedDek": "<base64, wrapped DEK envelope>",
  "keyEpoch": 1,
  "wrappedBy": "550e8400-e29b-41d4-a716-446655440000"
}
```

### 9.13 Upload Key Attestation

**Request: `POST /keys/attestations`**

```json
{
  "attestedDevice": "772a0622-a41d-43f6-c938-668877992222",
  "attestedKey": "<base64, X25519 public key being attested>",
  "signature": "<base64, Ed25519 signature, 64 bytes>"
}
```

**Response: `201 Created`**

No body.

### 9.14 Get Key Attestations

**Request: `GET /keys/attestations/{deviceId}`**

**Response: `200 OK`**

```json
{
  "attestations": [
    {
      "signerDevice": "550e8400-e29b-41d4-a716-446655440000",
      "attestedDevice": "772a0622-a41d-43f6-c938-668877992222",
      "attestedKey": "<base64, X25519 public key>",
      "signature": "<base64, Ed25519 signature>",
      "createdAt": "2026-02-21T12:30:00.000Z"
    }
  ]
}
```

### 9.15 Recovery Blob

**Upload: `POST /recovery`**

```json
{
  "encryptedBlob": "<base64, nonce || ciphertext || tag>"
}
```

**Response: `201 Created`**

No body.

**Download: `GET /recovery`**

**Response: `200 OK`**

```json
{
  "encryptedBlob": "<base64, nonce || ciphertext || tag>",
  "createdAt": "2026-02-21T12:00:00.000Z"
}
```

### 9.16 Push Token

**Request: `POST /push/token`**

```json
{
  "token": "<platform-specific push token>",
  "platform": "FCM"
}
```

**Response: `204 No Content`**

No body.

### 9.17 Error Response Format

All API error responses use a consistent structure:

```json
{
  "error": "HASH_CHAIN_BREAK",
  "message": "Expected prevHash 'abc123...' but got 'def456...'",
  "details": {
    "expectedPrevHash": "abc123...",
    "actualPrevHash": "def456..."
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `error` | string | yes | Machine-readable error code |
| `message` | string | yes | Human-readable description. Clients SHOULD NOT parse this for logic. |
| `details` | object | no | Structured error details. Contents vary by error code. |

---

## 10. Binary Blob Protocol

Binary assets (receipt photos, documents, snapshots) are stored separately from the OpLog, scoped to a bucket. The OpLog references blobs by ID and includes per-blob decryption keys inside the encrypted payload.

### 10.1 Blob Constraints

| Constraint | Value |
|------------|-------|
| Maximum blob size | 10 MiB (10,485,760 bytes) |
| Allowed content | Any binary data (images, documents, snapshots) |
| Encryption | AES-256-GCM with a per-blob key (not the bucket DEK) |
| Storage | Server stores encrypted bytes; cannot determine content type |
| Scope | Per-bucket (`/buckets/{id}/blobs`) |

### 10.2 Blob Encryption Format

Each blob is encrypted client-side before upload:

1. Generate a random 256-bit (32-byte) AES key for this blob: `blobKey = CSRNG(32)`.
2. Generate a random 96-bit (12-byte) GCM nonce: `blobNonce = CSRNG(12)`.
3. Encrypt the plaintext blob with AES-256-GCM: `ciphertext || tag = AES-256-GCM-Encrypt(blobKey, blobNonce, rawBlobBytes, AAD=blobId)`.
4. Concatenate: `blobNonce (12 bytes) || ciphertext || auth_tag (16 bytes)`.
5. The concatenated bytes are the upload payload.
6. The per-blob AES key and nonce are included in the referencing OpLog entry's DecryptedPayload `data` field (e.g., `receiptBlobKey`, `receiptBlobNonce` in Expense).

### 10.3 Upload: `POST /buckets/{id}/blobs`

**Request:**
- Content-Type: `multipart/form-data`
- Authentication: `Authorization: Bearer <session-token>`
- Parts:

| Part name | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | binary | yes | The encrypted blob bytes (nonce + ciphertext + tag) |
| `sha256` | text | yes | SHA-256 hex digest of the encrypted bytes |

**Response: `201 Created`**

```json
{
  "blobId": "deadbeef-1234-5678-9abc-def012345678",
  "sizeBytes": 245760,
  "sha256": "abc123def456...",
  "uploadedAt": "2026-02-21T16:20:00.000Z"
}
```

**Error responses:**

| Status | Condition |
|--------|-----------|
| 400 | Missing part, SHA-256 mismatch |
| 401 | Missing or invalid session token |
| 403 | Device does not have access to this bucket |
| 413 | File exceeds 10 MiB |
| 429 | Rate limit exceeded |

### 10.4 Download: `GET /buckets/{id}/blobs/{blobId}`

**Response: `200 OK`**
- Content-Type: `application/octet-stream`
- Headers: `Content-Length`, `X-Blob-SHA256`
- Body: raw encrypted blob bytes

### 10.5 Blob Upload Workflow

```
1. Client captures/selects file
2. Client generates random 256-bit per-blob AES key
3. Client generates random 96-bit nonce
4. Client encrypts file with per-blob key (AES-256-GCM, AAD=blobId)
5. Client computes SHA-256 of encrypted bytes
6. Client uploads encrypted bytes via POST /buckets/{id}/blobs
7. Server returns blobId
8. Client creates op with DecryptedPayload containing:
   - receiptBlobId = blobId from step 7
   - receiptBlobKey = Base64(per-blob key from step 2)
   - receiptBlobNonce = Base64(nonce from step 3)
9. Client uploads op via POST /buckets/{id}/ops
```

---

## 11. WebSocket Message Formats

### 11.1 Server-to-Client Messages

#### auth_ok

```json
{
  "type": "auth_ok",
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "bucketId": "d4e5f6a7-b8c9-0123-4567-890abcdef012",
  "latestSequence": 1043
}
```

#### auth_failed

```json
{
  "type": "auth_failed",
  "error": "TOKEN_EXPIRED",
  "message": "Session token has expired. Please re-authenticate."
}
```

#### ops_available

```json
{
  "type": "ops_available",
  "latestSequence": 1050,
  "sourceDeviceId": "661f9511-f30c-42e5-b827-557766881111"
}
```

#### device_joined

```json
{
  "type": "device_joined",
  "deviceId": "772a0622-a41d-43f6-c938-668877992222",
  "encryptionKey": "<base64, X25519 public key>"
}
```

#### checkpoint_available

```json
{
  "type": "checkpoint_available",
  "startSequence": 1001,
  "endSequence": 1100
}
```

#### snapshot_available

```json
{
  "type": "snapshot_available",
  "atSequence": 1043,
  "snapshotId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

#### pong

```json
{
  "type": "pong",
  "ts": "2026-02-21T14:30:00.050Z"
}
```

### 11.2 Client-to-Server Messages

#### auth

```json
{
  "type": "auth",
  "token": "<opaque-session-token>"
}
```

#### ping

```json
{
  "type": "ping",
  "ts": "2026-02-21T14:30:00.000Z"
}
```

### 11.3 Unknown Message Types

Clients MUST ignore unknown server message types. Servers MUST ignore unknown client message types. This allows forward-compatible additions.

---

## 12. Serialization Library Choices

### 12.1 Platform Libraries

| Platform | Library | Notes |
|----------|---------|-------|
| Android (client) | `kotlinx.serialization` | JSON format, `@Serializable` data classes |
| Server (Ktor) | `kotlinx.serialization` | Same library as Android for type sharing |
| iOS (future) | Swift `Codable` | `JSONEncoder` / `JSONDecoder` with custom date strategies |

### 12.2 kotlinx.serialization Configuration

```kotlin
val protocolJson = Json {
    explicitNulls = false       // Omit null values (Section 3.3)
    encodeDefaults = false      // Reduce payload size
    ignoreUnknownKeys = false   // Strict mode for known schemas
    prettyPrint = false         // Compact output
}
```

### 12.3 Data Classes

```kotlin
@Serializable
data class OpInput(
    val deviceId: String,
    val keyEpoch: Int,
    val encryptedPayload: String,
    val prevHash: String,
    val currentHash: String,
)

@Serializable
data class OpOutput(
    val globalSequence: Long,
    val deviceId: String,
    val keyEpoch: Int,
    val encryptedPayload: String,
    val prevHash: String,
    val currentHash: String,
    val serverTimestamp: String,
)

@Serializable
data class DecryptedPayload(
    val deviceSequence: Int,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val clientTimestamp: String,
    val protocolVersion: Int,
    val data: JsonObject,
)
```

### 12.4 Canonical Serialization Implementation

For canonical serialization (Section 3.7):

- **kotlinx.serialization**: Parse to `JsonObject`, sort keys recursively, re-serialize with `prettyPrint = false`.
- **Swift Codable**: Use `encoder.outputFormatting = [.sortedKeys]`.

Both platforms MUST pass the canonical serialization conformance test vectors to verify byte-identical output.

---

## 13. Versioning and Extensibility

### 13.1 Protocol Version

- The `protocolVersion` field is INSIDE the `DecryptedPayload` (not visible to the server).
- This specification defines **protocol version 2**.
- Clients encountering a `DecryptedPayload` with an unsupported `protocolVersion` MUST store the encrypted op for hash chain integrity and skip applying the payload to the local model.

### 13.2 Forward Compatibility

- Clients receiving a `DecryptedPayload` with an unknown `entityType` MUST:
  1. Log a warning.
  2. Store the encrypted op in the local database (for hash chain integrity).
  3. Skip applying the payload to the local model.
  4. Continue processing subsequent ops.
- This allows newer clients to introduce new entity types without breaking older clients.

### 13.3 Backward Compatibility

- Protocol version N clients MUST support DecryptedPayloads from version N and N-1.
- Deprecated entity types MUST be supported for decoding for at least one protocol version after deprecation.

### 13.4 Adding New Entity Types

New entity types are added by:

1. Defining the `data` schema for the new entity type.
2. Adding the type name to the `entityType` documentation.
3. Incrementing the `protocolVersion` ONLY if the change is backward-incompatible (e.g., changing the DecryptedPayload envelope format). Adding new entity types does NOT require a version bump.

---

## 14. Complete Examples

### 14.1 Full Upload (Client to Server)

```
POST /buckets/d4e5f6a7-b8c9-0123-4567-890abcdef012/ops
Content-Type: application/json
Authorization: Bearer <opaque-session-token>

{
  "ops": [
    {
      "deviceId": "550e8400-e29b-41d4-a716-446655440000",
      "keyEpoch": 1,
      "encryptedPayload": "AAAAAAAAAAAAAAAA...base64...",
      "prevHash": "0000000000000000000000000000000000000000000000000000000000000000",
      "currentHash": "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a"
    }
  ]
}
```

Server response:

```
HTTP/1.1 201 Created
Content-Type: application/json

{
  "accepted": [
    {
      "index": 0,
      "globalSequence": 1,
      "serverTimestamp": "2026-02-21T14:30:00.000Z"
    }
  ]
}
```

### 14.2 Full Pull (Server to Client)

```
GET /buckets/d4e5f6a7-b8c9-0123-4567-890abcdef012/ops?since=0&limit=10
Authorization: Bearer <opaque-session-token>
```

```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "ops": [
    {
      "globalSequence": 1,
      "deviceId": "550e8400-e29b-41d4-a716-446655440000",
      "keyEpoch": 1,
      "encryptedPayload": "AAAAAAAAAAAAAAAA...base64...",
      "prevHash": "0000000000000000000000000000000000000000000000000000000000000000",
      "currentHash": "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
      "serverTimestamp": "2026-02-21T14:30:00.000Z"
    }
  ],
  "hasMore": false,
  "latestSequence": 1
}
```

### 14.3 Decrypted Payload: Expense with Receipt

**Step 1:** Upload receipt blob.

```
POST /buckets/d4e5f6a7-b8c9-0123-4567-890abcdef012/blobs
Content-Type: multipart/form-data; boundary=----FormBoundary
Authorization: Bearer <opaque-session-token>

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
  "sha256": "a1b2c3d4e5f6...",
  "uploadedAt": "2026-02-21T16:20:00.000Z"
}
```

**Step 2:** Create expense op. The DecryptedPayload (before encryption):

```json
{
  "deviceSequence": 12,
  "entityType": "Expense",
  "entityId": "a1234567-b890-cdef-1234-567890abcdef",
  "operation": "CREATE",
  "clientTimestamp": "2026-02-21T16:22:00.000Z",
  "protocolVersion": 2,
  "data": {
    "expenseId": "a1234567-b890-cdef-1234-567890abcdef",
    "childId": "c1d2e3f4-5678-9abc-def0-123456789012",
    "paidByDeviceId": "550e8400-e29b-41d4-a716-446655440000",
    "amountCents": 4500,
    "currencyCode": "EUR",
    "category": "MEDICAL",
    "description": "Pediatrician visit - flu symptoms",
    "incurredAt": "2026-02-21",
    "payerResponsibilityRatio": 0.5,
    "receiptBlobId": "deadbeef-1234-5678-9abc-def012345678",
    "receiptBlobKey": "c2VjcmV0LWJsb2Ita2V5LTMyLWJ5dGVz",
    "receiptBlobNonce": "cmFuZG9tLW5vbmNl"
  }
}
```

This DecryptedPayload is canonicalized, compressed, encrypted, and sent as the `encryptedPayload` field in an OpInput with only 5 visible fields.

### 14.4 Canonical Serialization Example

Given a CalendarEvent CREATE DecryptedPayload:

Input (logical):
```json
{
  "deviceSequence": 5,
  "entityType": "CalendarEvent",
  "entityId": "11223344-5566-7788-99aa-bbccddeeff00",
  "operation": "CREATE",
  "clientTimestamp": "2026-03-16T12:00:00.000Z",
  "protocolVersion": 2,
  "data": {
    "eventId": "11223344-5566-7788-99aa-bbccddeeff00",
    "childId": "c1d2e3f4-5678-9abc-def0-123456789012",
    "title": "Doctor visit",
    "date": "2026-04-10"
  }
}
```

Canonical form (keys sorted lexicographically at every level, compact, no whitespace):

```
{"clientTimestamp":"2026-03-16T12:00:00.000Z","data":{"childId":"c1d2e3f4-5678-9abc-def0-123456789012","date":"2026-04-10","eventId":"11223344-5566-7788-99aa-bbccddeeff00","title":"Doctor visit"},"deviceSequence":5,"entityId":"11223344-5566-7788-99aa-bbccddeeff00","entityType":"CalendarEvent","operation":"CREATE","protocolVersion":2}
```

---

## Appendix A: Size Limits Summary

| Field / Resource | Limit | Rationale |
|-----------------|-------|-----------|
| `encryptedPayload` | 64 KiB (Base64-encoded) | OpLog entries are small structured data |
| Blob file | 10 MiB | Receipt photos and documents |
| `description` (expense) | 500 characters | Concise description |
| `title` (event / info bank) | 200 characters | Short title |
| `note` (override, expense status) | 2,000 characters | Explanatory text |
| `location` (event) | 500 characters | Address or description |
| `notes` (event) | 2,000 characters | Additional details |
| `content` (info bank) | 10,000 characters | Information entry text |
| `cycleLengthDays` | 1-365 | Practical custody cycle limit |
| `pattern` array | 1-365 entries | Matches cycleLengthDays |
| Upload batch | 100 ops per request | Prevents oversized requests |
| Pull page | 1,000 ops per response | Bounded response size |
| Snapshot | 50 MiB | Compressed encrypted state |

## Appendix B: Comparison with Protocol v1

| Aspect | Protocol v1 (wire-format.md) | Protocol v2 (this spec) |
|--------|-----|-----|
| Op envelope | `OpLogEntry` with 13+ fields | `OpInput` with 5 fields |
| `entityType` | In envelope (server-visible) | Inside `encryptedPayload` |
| `entityId` | In envelope (server-visible) | Inside `encryptedPayload` |
| `operation` | In envelope (server-visible) | Inside `encryptedPayload` |
| `clientTimestamp` | In envelope (server-visible) | Inside `encryptedPayload` |
| `deviceSequence` | In envelope (server-visible) | Inside `encryptedPayload` |
| `protocolVersion` | In envelope (server-visible) | Inside `encryptedPayload` |
| `transitionTo` | In envelope (server-visible) | Inside `data` field in `DecryptedPayload` |
| `signature` | Per-op Ed25519 signature | Not needed (session-based auth) |
| Payload union | `payloadType` discriminator | Generic `data: JsonObject` keyed on `entityType` |
| Blob endpoint | `/blobs` (global) | `/buckets/{id}/blobs` (bucket-scoped) |
| Auth | JWT Bearer | Opaque session token |
| Hash chain field | `devicePrevHash` | `prevHash` |

---

## Revision History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2026-02-20 | Initial draft (13-field envelope, server-visible metadata) |
| 2.0.0 | 2026-02-21 | Zero-knowledge rewrite: 5-field OpInput, all metadata inside encrypted payload, bucket-scoped blobs, opaque session tokens, client-side state machine, new entity types |
