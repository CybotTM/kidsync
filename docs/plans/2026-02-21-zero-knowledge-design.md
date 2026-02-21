# Zero-Knowledge Server Architecture Design

**Date:** 2026-02-21
**Status:** Draft
**Predecessor:** ADR-001

## Overview

Redesign the KidSync server from a traditional auth+relay server into a true zero-knowledge encrypted storage hub. The server authenticates clients via public key cryptography, stores only opaque encrypted blobs, and has no visibility into user identity, relationships, or data content.

## Core Principles

1. **Server sees nothing** -- no emails, names, relationships, entity types, or timestamps
2. **Public key = identity** -- no accounts, no usernames, no passwords on server
3. **Client controls everything** -- pairing, conflict resolution, state machines are all client-side
4. **QR code pairing** -- no server-managed invites; key exchange happens out-of-band
5. **Key transparency** -- devices cross-sign each other's keys to detect server substitution
6. **Self-revoke only** -- devices can only revoke themselves; admin revocation via signed ops inside encrypted payload

---

## 1. Identity and Authentication

### Current: Email + Password + JWT
### Proposed: Ed25519 Challenge-Response

Each device generates two keypairs at first launch:

| Purpose | Algorithm | Storage |
|---------|-----------|---------|
| Signing / Authentication | Ed25519 | Android Keystore via EncryptedSharedPreferences |
| Encryption / DEK wrapping | X25519 | Android Keystore via EncryptedSharedPreferences |

Both can be derived from the same 32-byte seed (stored once, derived via `crypto_sign_ed25519_sk_to_curve25519`).

### Registration

```
Client                              Server
  |                                   |
  |  POST /register                   |
  |  { signingKey: Ed25519_pub,       |
  |    encryptionKey: X25519_pub }    |
  | --------------------------------> |
  |                                   |  Store keys
  |  201 { deviceId: UUID }           |  (no email, no name)
  | <-------------------------------- |
```

### Authentication (per session)

```
Client                              Server
  |                                   |
  |  POST /auth/challenge             |
  |  { signingKey: Ed25519_pub }      |
  | --------------------------------> |
  |                                   |  Generate 32-byte nonce
  |                                   |  Store nonce with 60s TTL
  |  200 { nonce, expiresAt }         |  Mark as one-time-use
  | <-------------------------------- |
  |                                   |
  |  Construct challenge:             |
  |    msg = nonce ‖ signingKey       |
  |        ‖ serverOrigin ‖ timestamp |
  |  sig = Ed25519_sign(msg)          |
  |                                   |
  |  POST /auth/verify                |
  |  { signingKey, nonce, signature,  |
  |    timestamp }                    |
  | --------------------------------> |
  |                                   |  Verify nonce exists and unused
  |                                   |  Verify nonce not expired
  |                                   |  Consume nonce (delete from store)
  |                                   |  Reconstruct msg, verify signature
  |  200 { sessionToken, expiresIn }  |  Issue short-lived token
  | <-------------------------------- |
```

**Anti-replay protections:**
- Nonces are one-time-use: consumed (deleted) on successful verification
- Challenge message binds to the signing key, server origin, and client timestamp to prevent cross-context replay
- 60-second nonce TTL prevents delayed replay

The session token is a server-signed opaque token (not JWT) containing only `{ deviceId, signingKey, exp }`. No user ID, no family IDs, no email. The server does not know who this device belongs to.

### What disappears

- `Users` table (email, passwordHash, displayName, totpSecret, totpEnabled)
- `RefreshTokens` table
- `AuthService.kt` (register, login, refresh, TOTP setup/verify)
- BCrypt dependency
- TOTP dependency
- Password validation

---

## 2. Storage Model: Buckets

### Current: Families table with name, membership, roles
### Proposed: Anonymous buckets

A **bucket** is an anonymous, opaque storage namespace. The server does not know what a bucket represents (family, organization, individual). It only knows which public keys have access.

### Server Tables (complete schema)

```sql
-- Device identity (replaces Users + Devices)
devices (
    id              VARCHAR(36) PRIMARY KEY,
    signing_key     TEXT NOT NULL UNIQUE,     -- Ed25519 public key
    encryption_key  TEXT NOT NULL,            -- X25519 public key
    created_at      DATETIME NOT NULL
)

-- Anonymous storage namespaces (replaces Families)
buckets (
    id              VARCHAR(36) PRIMARY KEY,
    created_by      VARCHAR(36) REFERENCES devices(id),  -- for deletion authorization
    created_at      DATETIME NOT NULL
)

-- Access control (replaces FamilyMembers)
bucket_access (
    bucket_id       VARCHAR(36) REFERENCES buckets(id),
    device_id       VARCHAR(36) REFERENCES devices(id),
    granted_at      DATETIME NOT NULL,
    revoked_at      DATETIME,
    PRIMARY KEY (bucket_id, device_id)
)

-- Encrypted operations (replaces OpLog)
ops (
    sequence        BIGINT AUTO_INCREMENT PRIMARY KEY,
    bucket_id       VARCHAR(36) REFERENCES buckets(id),
    device_id       VARCHAR(36) REFERENCES devices(id),
    encrypted_payload TEXT NOT NULL,       -- opaque, no metadata
    prev_hash       VARCHAR(64) NOT NULL,
    current_hash    VARCHAR(64) NOT NULL,
    key_epoch       INTEGER NOT NULL,
    created_at      DATETIME NOT NULL      -- server timestamp only
)

-- Encrypted blobs (replaces Blobs)
blobs (
    id              VARCHAR(36) PRIMARY KEY,
    bucket_id       VARCHAR(36) REFERENCES buckets(id),
    file_path       VARCHAR(500) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    sha256_hash     VARCHAR(64) NOT NULL,
    uploaded_by     VARCHAR(36) REFERENCES devices(id),
    uploaded_at     DATETIME NOT NULL
)

-- Key attestations (cross-signing for key transparency)
key_attestations (
    id              INTEGER AUTO_INCREMENT PRIMARY KEY,
    signer_device   VARCHAR(36) REFERENCES devices(id),
    attested_device VARCHAR(36) REFERENCES devices(id),
    attested_key    TEXT NOT NULL,            -- X25519 pub key being attested
    signature       TEXT NOT NULL,            -- Ed25519 sig over (attested_device ‖ attested_key)
    created_at      DATETIME NOT NULL,
    UNIQUE(signer_device, attested_device)
)

-- Wrapped keys (unchanged concept)
wrapped_keys (
    id              INTEGER AUTO_INCREMENT PRIMARY KEY,
    target_device   VARCHAR(36) REFERENCES devices(id),
    wrapped_dek     TEXT NOT NULL,
    key_epoch       INTEGER NOT NULL,
    wrapped_by      VARCHAR(36) REFERENCES devices(id),
    created_at      DATETIME NOT NULL,
    UNIQUE(target_device, key_epoch)
)

-- Recovery blobs (unchanged concept)
recovery_blobs (
    device_id       VARCHAR(36) REFERENCES devices(id) PRIMARY KEY,
    encrypted_blob  TEXT NOT NULL,
    created_at      DATETIME NOT NULL
)

-- Push tokens
push_tokens (
    device_id       VARCHAR(36) REFERENCES devices(id) PRIMARY KEY,
    token           TEXT NOT NULL,
    platform        VARCHAR(10) NOT NULL,
    updated_at      DATETIME NOT NULL
)

-- Integrity checkpoints (unchanged concept)
checkpoints (
    id              INTEGER AUTO_INCREMENT PRIMARY KEY,
    bucket_id       VARCHAR(36) REFERENCES buckets(id),
    start_sequence  BIGINT NOT NULL,
    end_sequence    BIGINT NOT NULL,
    hash            VARCHAR(64) NOT NULL,
    created_at      DATETIME NOT NULL,
    op_count        INTEGER NOT NULL
)

-- Snapshots (unchanged concept)
snapshots (
    id              VARCHAR(36) PRIMARY KEY,
    bucket_id       VARCHAR(36) REFERENCES buckets(id),
    device_id       VARCHAR(36) REFERENCES devices(id),
    at_sequence     BIGINT NOT NULL,
    key_epoch       INTEGER NOT NULL,
    size_bytes      BIGINT NOT NULL,
    sha256_hash     VARCHAR(64) NOT NULL,
    signature       TEXT NOT NULL,
    file_path       VARCHAR(500) NOT NULL,
    created_at      DATETIME NOT NULL
)

-- One-time invite tokens (hashed, for pairing)
invite_tokens (
    token_hash      VARCHAR(64) PRIMARY KEY,
    bucket_id       VARCHAR(36) REFERENCES buckets(id),
    created_at      DATETIME NOT NULL,
    expires_at      DATETIME NOT NULL,
    used_at         DATETIME
)
```

### What disappeared from the schema

| Removed | Reason |
|---------|--------|
| `users.email` | No email-based auth |
| `users.password_hash` | No password auth |
| `users.display_name` | Client-side only |
| `users.totp_secret` | No server-side MFA |
| `families.name` | Client-side only |
| `families.is_solo` | Client-side concept |
| `family_members.role` | Client-side concept |
| `devices.device_name` | Client-side only |
| `ops.entity_type` | Inside encrypted payload |
| `ops.entity_id` | Inside encrypted payload |
| `ops.operation` | Inside encrypted payload |
| `ops.client_timestamp` | Inside encrypted payload |
| `ops.transition_to` | Client-side state machine |
| `ops.protocol_version` | Inside encrypted payload |
| `override_states.*` | Client-side state machine |
| `refresh_tokens.*` | No refresh tokens |
| `invites.*` | Replaced by `invite_tokens` (hash only) |

---

## 3. Pairing Flow (QR Code)

### How two devices connect to the same bucket

The QR code contains ONLY the invite token and connection info -- never the DEK. The DEK is exchanged via wrapped key exchange after both devices are authenticated.

```
Device A (initiator)                 Server                    Device B (joiner)
  |                                    |                          |
  |  1. Create bucket                  |                          |
  |  POST /buckets                     |                          |
  |  (authenticated)                   |                          |
  | ---------------------------------> |                          |
  |  { bucketId }                      |                          |
  | <--------------------------------- |                          |
  |                                    |                          |
  |  2. Generate invite token          |                          |
  |  POST /buckets/{id}/invite         |                          |
  |  { tokenHash: SHA256(token) }      |                          |
  | ---------------------------------> |                          |
  |  201 OK                            |                          |
  |                                    |                          |
  |  3. Build QR payload               |                          |
  |  {                                 |                          |
  |    serverUrl,                      |                          |
  |    bucketId,                       |                          |
  |    inviteToken,                    |                          |
  |    signingKeyFingerprint (A's)     |                          |
  |  }                                 |                          |
  |  NO DEK in QR code                 |                          |
  |  Encode as QR code                 |                          |
  |                                    |                          |
  |  4. Show QR =========================> 5. Scan QR             |
  |     (out-of-band, physical         |      Decode payload      |
  |      proximity or secure channel)  |      Store A's key       |
  |                                    |      fingerprint         |
  |                                    |                          |
  |                                    |  6. Register device      |
  |                                    |  POST /register          |
  |                                    |  { signingKey, encKey }  |
  |                                    | <----------------------- |
  |                                    |  { deviceId }            |
  |                                    | -----------------------> |
  |                                    |                          |
  |                                    |  7. Authenticate         |
  |                                    |  (challenge-response)    |
  |                                    | <---------------------> |
  |                                    |                          |
  |                                    |  8. Redeem invite        |
  |                                    |  POST /buckets/{id}/join |
  |                                    |  { inviteToken }         |
  |                                    | <----------------------- |
  |                                    |  Verify hash, grant      |
  |                                    |  access, mark used       |
  |                                    | -----------------------> |
  |                                    |                          |
  |  9. Notified (WebSocket/push)      |                          |
  |  "new device joined bucket"        |                          |
  | <--------------------------------- |                          |
  |                                    |                          |
  | 10. Verify B's key fingerprint     |                          |
  |  GET /buckets/{id}/devices         |                          |
  | ---------------------------------> |                          |
  |  [{ deviceId: B, encKey: ... }]    |                          |
  | <--------------------------------- |                          |
  |  Cross-sign B's key (see §3.1)     |                          |
  |  Wrap DEK with B's X25519 key      |                          |
  |  POST /keys/wrapped                |                          |
  |  { targetDevice: B, wrappedDek,    |                          |
  |    epoch, crossSignature }         |                          |
  | ---------------------------------> |                          |
  |                                    |                          |
  |                                    | 11. Device B fetches key |
  |                                    |  GET /keys/wrapped       |
  |                                    | <----------------------- |
  |                                    |  { wrappedDek, epoch,    |
  |                                    |    crossSignature }      |
  |                                    | -----------------------> |
  |                                    |  Verify A's cross-sig    |
  |                                    |  using fingerprint from  |
  |                                    |  QR code (trusted)       |
  |                                    |  Unwrap DEK with private |
  |                                    |  key                     |
  |                                    |  Now can decrypt all ops |
```

### QR code contents

The QR code is the secure out-of-band channel. It contains connection info and the initiator's key fingerprint for cross-verification, but **never the DEK**:

```json
{
  "v": 1,
  "s": "https://api.kidsync.app",
  "b": "bucket-uuid",
  "t": "invite-token-plaintext",
  "f": "SHA256-fingerprint-of-device-A-signing-key"
}
```

Compact encoding (base64url of CBOR or MessagePack) keeps the QR small.

### Security properties

- **Invite token**: One-time use, server stores only SHA-256 hash, expires in 24h
- **No DEK in QR**: The DEK is never in the QR code. It is wrapped with Device B's X25519 key and exchanged via the server after join. This prevents DEK exposure if the QR image is captured
- **Cross-signing**: Device A's key fingerprint in the QR code allows Device B to verify that the wrapped DEK actually came from Device A, not a server performing key substitution (see §3.1)
- **Physical proximity**: QR scanning requires physical proximity (or deliberate secure sharing by the user)
- **Server learns nothing**: Server sees "device B joined bucket X" but doesn't know what the bucket represents or who the users are

---

### 3.1. Key Transparency and Cross-Signing

**Threat**: A compromised server could substitute Device B's encryption key during the DEK wrapping step, intercepting the DEK.

**Mitigation**: Devices cross-sign each other's keys via signed attestations stored on the server:

```kotlin
@Serializable
data class KeyAttestation(
    val signerDeviceId: String,
    val attestedDeviceId: String,
    val attestedEncryptionKey: String,   // X25519 pub key being attested
    val signature: String,               // Ed25519 sig over (attestedDeviceId ‖ attestedEncryptionKey)
    val createdAt: String
)
```

**Verification flow:**
1. During pairing, Device A's signing key fingerprint is embedded in the QR code (trusted channel)
2. After join, Device A signs Device B's encryption key and uploads the attestation
3. Device B verifies Device A's attestation using the fingerprint from the QR code
4. Device B signs Device A's encryption key and uploads its own attestation
5. On subsequent key fetches, any device can verify that keys haven't been substituted by checking cross-signatures from at least one trusted device

**Key change detection:**
- When a device's encryption key changes (e.g., after recovery), existing attestations are invalidated
- Other devices see unattested keys and prompt the user to verify in-person before wrapping DEK with the new key
- This is similar to Signal's "safety number" change notification

---

## 4. Encrypted OpLog (Metadata Inside Payload)

### Current: Metadata in plaintext columns
### Proposed: Everything inside encrypted payload

The `OpInput` sent to the server becomes minimal:

```kotlin
@Serializable
data class OpInput(
    val deviceId: String,
    val keyEpoch: Int,
    val encryptedPayload: String,    // everything is in here
    val prevHash: String,
    val currentHash: String,
)
```

All metadata moves inside the encrypted payload:

```kotlin
@Serializable
data class DecryptedPayload(
    // Metadata (was plaintext, now encrypted)
    val deviceSequence: Int,
    val entityType: String,          // "CustodySchedule", "Expense", etc.
    val entityId: String,            // UUID
    val operation: String,           // "CREATE", "UPDATE", "DELETE"
    val clientTimestamp: String,      // ISO 8601
    val protocolVersion: Int,

    // Content (was already encrypted)
    val data: JsonObject,            // entity-specific fields
)
```

### What the server sees per op

| Field | Visible | Purpose |
|-------|---------|---------|
| sequence | Yes | Global ordering (server-assigned) |
| bucket_id | Yes | Routing (which bucket) |
| device_id | Yes | Hash chain per device |
| encrypted_payload | No | Opaque blob |
| prev_hash | Yes | Hash chain integrity |
| current_hash | Yes | Hash chain integrity |
| key_epoch | Yes | Key version (for DEK lookup) |
| created_at | Yes | Server timestamp only |

### What the server no longer sees

- Entity type (custody schedule? expense? info bank entry?)
- Entity ID
- Operation type (create? update? delete?)
- Client timestamp
- Override state transitions
- Protocol version
- Any content whatsoever

### Server-side validation changes

| Validation | Before | After |
|-----------|--------|-------|
| Entity type allowlist | Server checks | Removed (client-side) |
| Operation allowlist | Server checks | Removed (client-side) |
| Override state machine | Server manages `OverrideStates` table | Client-side (see section 5) |
| Hash chain | Server verifies (unchanged) | Server verifies (unchanged) |
| Max ops per batch | Server enforces (unchanged) | Server enforces (unchanged) |
| Payload size limit | Add server-side check | Per-op and per-batch byte limits |

---

## 5. Client-Side Override State Machine

### Current: Server tracks states in `OverrideStates` table
### Proposed: Each client computes state by replaying ops

The override state machine (PROPOSED -> APPROVED/DECLINED/CANCELLED/SUPERSEDED/EXPIRED) moves entirely to the client. Each client deterministically replays all ops and derives the current state.

```kotlin
class OverrideStateMachine {
    private val states = mutableMapOf<String, OverrideState>()

    fun apply(op: DecryptedPayload) {
        if (op.entityType != "CustodyOverride") return

        when (op.operation) {
            "CREATE" -> states[op.entityId] = OverrideState(
                status = "PROPOSED",
                proposerId = op.data["proposerId"]
            )
            "UPDATE" -> {
                val current = states[op.entityId] ?: return
                val transition = op.data["transitionTo"]
                if (isValidTransition(current.status, transition, op)) {
                    states[op.entityId] = current.copy(status = transition)
                }
            }
            "DELETE" -> states.remove(op.entityId)
        }
    }
}
```

### Determinism guarantee

Since all clients process the same ops in the same global sequence order, they all converge to the same state. This is the same guarantee that makes CRDTs work. No server arbitration needed.

---

## 6. API Surface

### Complete endpoint list

```
POST   /register                        Register device (public keys)
POST   /auth/challenge                  Request auth nonce
POST   /auth/verify                     Verify signed nonce, get session

POST   /buckets                         Create bucket
DELETE /buckets/{id}                    Delete bucket (creator only, all data purged)
POST   /buckets/{id}/invite             Register invite token hash
POST   /buckets/{id}/join               Redeem invite token
GET    /buckets/{id}/devices            List devices with access
DELETE /buckets/{id}/devices/me         Self-revoke (leave bucket)

POST   /buckets/{id}/ops                Upload encrypted ops
GET    /buckets/{id}/ops?since={seq}    Pull ops since sequence
GET    /buckets/{id}/checkpoint         Get latest checkpoint

POST   /buckets/{id}/blobs              Upload encrypted blob
GET    /buckets/{id}/blobs/{blobId}     Download encrypted blob

POST   /buckets/{id}/snapshots          Upload encrypted snapshot
GET    /buckets/{id}/snapshots/latest   Get latest snapshot

POST   /keys/wrapped                    Upload wrapped DEK
GET    /keys/wrapped?epoch={n}          Get wrapped DEK for device

POST   /keys/attestations               Upload key cross-signature
GET    /keys/attestations/{deviceId}    Get attestations for a device

POST   /recovery                        Upload encrypted recovery blob
GET    /recovery                        Download encrypted recovery blob

POST   /push/token                      Register push notification token

WS     /buckets/{id}/ws                 Real-time sync notifications

GET    /health                          Health check
```

### Authorization model

All endpoints (except `/register`, `/auth/*`, `/health`) require a valid session token. Bucket operations additionally verify that the authenticated device has access to the bucket (via `bucket_access` table).

```kotlin
fun Route.requireBucketAccess(bucketId: String, deviceId: String) {
    // Check bucket_access table: device has access and not revoked
}
```

**Device revocation policy (server-enforced):**

- **Self-revoke only**: `DELETE /buckets/{id}/devices/me` -- a device can only remove itself from a bucket. The server enforces `deviceId == authenticatedDeviceId`.
- **Admin revocation**: Not a server concept. If admin revocation is needed, it is implemented as a signed op inside the encrypted payload. Remaining devices observe the revocation op, stop wrapping DEK for the revoked device's key, and rotate the DEK to a new epoch.
- **Bucket deletion**: `DELETE /buckets/{id}` is restricted to the device that created the bucket (tracked in `buckets.created_by`). Deletes all ops, blobs, snapshots, and access records.

No roles, no admin/member distinction on the server. The concept of "admin" is a client-side convention stored inside encrypted ops.

---

## 7. Multi-Device (Same User)

A single user with multiple devices (phone + tablet):

1. Device A is already registered and has bucket access
2. User adds Device B:
   - Device B generates keypairs at first launch
   - Device B registers with server
   - Device A shows "Link Device" QR containing:
     ```json
     { "v": 1, "s": "serverUrl", "b": "bucketId",
       "t": "device-link-token", "f": "device-A-key-fingerprint" }
     ```
   - Device B scans QR, authenticates, redeems token, gets bucket access
   - Device A and B cross-sign each other's keys (§3.1)
   - Device A wraps DEK for Device B's verified X25519 key

Same flow as co-parent pairing. The server doesn't distinguish between "same user, new device" and "new user joining family" -- it's all just "new device gets bucket access."

---

## 8. Account Recovery

### Current: BIP39 mnemonic (already implemented)
### Proposed: Unchanged, but now it's the ONLY recovery path

Recovery flow:
1. User enters 24-word BIP39 mnemonic + optional passphrase (25th word)
2. Client derives recovery key via HKDF(mnemonic_seed ‖ passphrase)
3. Client downloads encrypted recovery blob from server
4. Client decrypts: gets DEK + device signing seed
5. Client re-derives Ed25519 + X25519 keypairs from seed
6. Client registers as new device, presents recovery proof
7. Client downloads ops and decrypts with recovered DEK

No email-based "forgot password" -- that concept doesn't exist anymore.

### Optional BIP39 passphrase (25th word)

The recovery blob is a high-blast-radius target: a stolen mnemonic grants full access to all data. To mitigate this, the app supports an optional BIP39 passphrase ("25th word") that is NOT stored anywhere:

- **Without passphrase**: Standard 24-word recovery (default, simpler UX)
- **With passphrase**: 24 words + user-chosen passphrase. Both are needed to derive the recovery key. A stolen mnemonic alone is useless without the passphrase.

The passphrase is presented as an opt-in during onboarding for security-conscious users.

### Recovery blob contents (encrypted with recovery key)

```json
{
  "seed": "base64-32-bytes",
  "bucketIds": ["uuid1", "uuid2"],
  "deks": {
    "uuid1": { "1": "base64-dek-epoch1", "2": "base64-dek-epoch2" },
    "uuid2": { "1": "base64-dek-epoch1" }
  }
}
```

---

## 9. Push Notifications

### Current: Server sends push with context
### Proposed: Server sends opaque "new data" signal

The push notification contains only:

```json
{
  "type": "sync",
  "bucket": "bucket-id"
}
```

No entity types, no operation types, no user names, no content previews. The client wakes up, pulls new ops, decrypts locally, and shows a local notification with the actual content.

---

## 10. Migration Path

KidSync is pre-launch (no production users). This is a clean-start redesign, not a live migration.

### Phase 1: Server changes (breaking, clean rewrite)

1. Replace `Users`/`Devices` tables with `devices` table
2. Replace `Families`/`FamilyMembers` with `buckets`/`bucket_access`
3. Strip metadata columns from `ops` table
4. Remove `OverrideStates` table
5. Remove `RefreshTokens` table
6. Add `invite_tokens` and `key_attestations` tables
7. Implement challenge-response auth with anti-replay
8. Add `DELETE /buckets/{id}` endpoint
9. Add key attestation endpoints
10. Implement self-revoke-only policy
11. Remove AuthService, simplify all route handlers

### Phase 2: Android changes

1. Replace login/register screens with key-based onboarding
2. Add QR code scanning/display for pairing (no DEK in QR)
3. Implement key cross-signing and verification (§3.1)
4. Move override state machine to client
5. Move entityType/entityId/operation inside encrypted payload
6. Replace email-based recovery UI with mnemonic-only (+ optional passphrase)
7. Remove all display name / email fields from server communication

### Phase 3: Protocol spec updates

1. Update `sync-protocol.md` -- remove metadata fields from wire format
2. Update `encryption-spec.md` -- document Ed25519 auth, key attestations
3. Update `wire-format.md` -- new OpInput format
4. Update OpenAPI spec -- new endpoints
5. Generate new conformance test vectors

---

## 11. Comparison: Before and After

### Server knowledge

| Data point | Before | After |
|-----------|--------|-------|
| User email | Plaintext | Not stored |
| Display name | Plaintext | Not stored (client-side only) |
| Password hash | BCrypt | Not stored |
| TOTP secret | Plaintext | Not stored |
| Family name | Plaintext | Not stored (client-side only) |
| Who is co-parenting with whom | FamilyMembers table | Not known (bucket = opaque) |
| Entity types exchanged | Plaintext in OpLog | Inside encrypted payload |
| Operation types | Plaintext in OpLog | Inside encrypted payload |
| Client timestamps | Plaintext in OpLog | Inside encrypted payload |
| Override states | OverrideStates table | Client-side only |
| Device names | Plaintext | Not stored (client-side only) |

### Server tables

| Before (13 tables) | After (11 tables) |
|--------------------|--------------------|
| Users | *(removed)* |
| Devices | devices |
| Families | buckets |
| FamilyMembers | bucket_access |
| OpLog | ops |
| Blobs | blobs |
| PushTokens | push_tokens |
| Invites | invite_tokens |
| RefreshTokens | *(removed)* |
| WrappedKeys | wrapped_keys |
| RecoveryBlobs | recovery_blobs |
| Snapshots | snapshots |
| Checkpoints | checkpoints |
| OverrideStates | *(removed)* |
| *(new)* | key_attestations |

### Security findings resolved

| Finding | Status |
|---------|--------|
| C-1: Hardcoded JWT secret | Eliminated (no JWT secrets, challenge-response auth) |
| H-4: No account lockout | Simplified (rate limit by signing key, no passwords to brute-force) |
| H-5: TOTP plaintext | Eliminated (no TOTP on server) |
| M-1: Stale family IDs in JWT | Eliminated (no family IDs in token, live bucket_access check) |
| M-5: CORS + auth bypass | Reduced (no cookies, no passwords, challenge-response only) |
| M-9: User enumeration | Eliminated (no emails to enumerate) |
| M-12: Refresh tokens unbound | Eliminated (no refresh tokens) |
| L-3: TOTP setup without re-auth | Eliminated (no TOTP) |
| L-5: Login leaks TOTP status | Eliminated (no login endpoint) |

---

## 12. Residual Metadata and Privacy Limitations

Even in the zero-knowledge design, the server still knows:

| Metadata | Inherent reason |
|----------|----------------|
| Which device keys access which bucket | Required for routing encrypted ops to the right devices |
| Number of ops and their sizes | Visible from storage |
| Timing of ops | Server timestamps for ordering |
| Number of devices per bucket | Visible from `bucket_access` |

This means a compromised server can infer a **social graph** (which public keys collaborate via shared buckets) and **activity patterns** (when and how much data is exchanged). This is an inherent limitation of any server-relayed E2E encrypted system (Signal, Matrix, and WhatsApp all share this limitation).

**Mitigations considered but deferred:**
- **Padding**: Fixed-size ops would hide data volume but increase bandwidth
- **Cover traffic**: Dummy ops would hide activity patterns but increase server load
- **PIR (Private Information Retrieval)**: Would hide which bucket a device reads from, but is computationally expensive

These are documented as known limitations, not design flaws.

---

## 13. Override Convergence Specification

All clients process ops in the same global sequence order and apply the same deterministic state transitions:

```
Valid transitions:
  PROPOSED → APPROVED    (by any device except proposer)
  PROPOSED → DECLINED    (by any device except proposer)
  PROPOSED → CANCELLED   (by proposer device only)
  PROPOSED → EXPIRED     (by any device, when clientTimestamp + TTL < now)
  PROPOSED → SUPERSEDED  (automatic: new PROPOSED for same date range supersedes previous)
```

**Convergence guarantee**: Given the same ordered sequence of ops, all clients compute the same override state. This is verified by:
1. Each client maintaining a state hash (SHA-256 of serialized state map)
2. Periodically exchanging state hashes via encrypted ops
3. If hashes diverge, the client with fewer ops pulls missing ops and re-derives

---

## Open Questions

1. **Rate limiting without user identity**: Rate limit by public key + IP combination? By session token?
2. **Abuse prevention**: Without email verification, how to prevent bucket spam? Proof-of-work on registration?
3. **Push notification UX**: Can we show useful notifications without server knowing content? (Yes -- client decrypts and shows local notification)
4. **Web client**: If a web client is ever needed, the challenge-response auth works with WebAuthn/passkeys stored in the browser
5. **Key rotation when device is revoked**: Remaining devices detect revocation, rotate DEK to new epoch, and wrap only for non-revoked devices
