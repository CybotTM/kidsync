# Conformance Test Vectors

This directory contains JSON test vector files for verifying implementations of the KidSync co-parenting app sync protocol **v2 (zero-knowledge architecture)**. All client implementations (Android/Kotlin, iOS/Swift) MUST pass these test vectors.

## Protocol Version 2 Changes

In protocol v2, the architecture is fundamentally different from v1:

- **All metadata is encrypted.** The server-visible envelope (`OpInput`) contains only 5 fields: `deviceId`, `keyEpoch`, `encryptedPayload`, `prevHash`, `currentHash`. Entity types, entity IDs, operation types, client timestamps, and device sequence numbers are all inside the encrypted payload (`DecryptedPayload`).
- **No accounts or passwords.** Devices are identified by Ed25519 signing keys and authenticated via challenge-response.
- **Buckets replace families.** The server does not know what a bucket represents.
- **Client-side everything.** Conflict resolution, override state machine, and all business logic are client-side.
- **Hash chain field renamed.** `devicePrevHash` (v1) is now `prevHash` (v2).
- **Canonical serialization** applies to the full `DecryptedPayload` wrapper (with `deviceSequence`, `entityType`, `entityId`, `operation`, `clientTimestamp`, `protocolVersion`, `data`), not the flat v1 payload format.

## Test Vector Files

| File | ID | Description |
|------|----|-------------|
| `tv01-single-device-ops.json` | TV01 | Single device creating a custody schedule, 3 expenses, and 1 swap request. Includes canonical serialized forms and SHA-256 hashes for the v2 `DecryptedPayload` format. |
| `tv02-concurrent-offline-merge.json` | TV02 | Two devices creating conflicting custody schedules offline with the same `effectiveFrom`. Verifies deterministic client-side conflict resolution (later `clientTimestamp` wins tie). |
| `tv03-hash-chain.json` | TV03 | Per-device hash chain verification with a valid 5-op chain and a tampered variant. Uses `prevHash` (v2 field name). All SHA-256 hashes are computed from real inputs. |
| `tv04-key-rotation.json` | TV04 | Key rotation triggered by device revocation. Epoch 1 (3 ops) then epoch 2 (2 ops). Includes `DeviceRevocation` and `KeyRotation` entity types (v2). Verifies `keyEpoch` correctness and forward secrecy. |
| `tv05-override-state-machine.json` | TV05 | All valid and invalid override state transitions, plus proposerId/responderId authority constraints. In v2, this is entirely client-side (server has no knowledge of states). |
| `tv06-clock-skew.json` | TV06 | Two devices with 2-hour clock difference creating conflicting schedules. Demonstrates that `globalSequence` determines application order while `clientTimestamp` (inside encrypted payload, invisible to server) is only a tie-breaker. |
| `tv07-canonical-serialization.json` | TV07 | Canonical serialization edge cases for the v2 `DecryptedPayload` wrapper format: missing optional fields, numeric precision, UUID formatting, and anti-patterns. |

## Test Vector Format

Each JSON file follows this general structure:

```json
{
  "id": "TV01",
  "title": "Human-readable title",
  "description": "What this test vector verifies",
  "protocolVersion": 2,
  "specReferences": ["wire-format.md Section X", "sync-protocol.md Section Y"],
  "context": { /* shared identifiers: bucketId, childId, deviceIds */ },
  "operations": [ /* or scenario-specific structure */ ],
  "expectedFinalState": { /* the deterministic result after applying all ops */ }
}
```

### Key Changes from v1 Test Vectors

| Aspect | v1 | v2 |
|--------|------|------|
| `decryptedPayload` | Flat structure with `payloadType` discriminator | `DecryptedPayload` wrapper with `deviceSequence`, `entityType`, `entityId`, `operation`, `clientTimestamp`, `protocolVersion`, `data` |
| `envelope` | 7+ fields visible to server | 5 fields: `deviceId`, `keyEpoch`, `encryptedPayload`, `prevHash`, `currentHash` |
| Hash chain field | `devicePrevHash` | `prevHash` |
| `context.familyId` | Family UUID | `bucketId` |
| `context.parentA.userId` | User UUID | `signingKeyFingerprint` |
| State machine | Server-enforced | Client-side only |
| Canonical JSON scope | Just entity data | Full `DecryptedPayload` including wrapper |

### Key Conventions

- **UUIDs** are lowercase hex with hyphens (RFC 4122 v4 format).
- **Timestamps** use ISO 8601 with exactly 3 fractional digits and `Z` suffix: `YYYY-MM-DDThh:mm:ss.sssZ`.
- **SHA-256 hashes** are 64-character lowercase hex strings.
- **Base64** uses standard RFC 4648 alphabet with padding.
- **Canonical JSON** means sorted keys at every nesting level (including inside `data`), compact (no whitespace), null fields omitted.

## How to Use These Test Vectors

### 1. Canonical Serialization (TV01, TV07)

Given the `decryptedPayload` object (full `DecryptedPayload` with wrapper fields), your implementation should:

1. Serialize to JSON with sorted keys at every nesting level, compact form, null fields omitted.
2. Compare the output byte-for-byte with `expectedCanonicalJson` (or `canonicalJson`).
3. Compute SHA-256 of the canonical UTF-8 bytes and compare with `expectedSha256` (or `canonicalSha256`).

### 2. Hash Chain Verification (TV03)

For each op in the `validChain`:

1. Hex-decode `prevHash` to 32 raw bytes.
2. Base64-decode `encryptedPayload` to raw bytes.
3. Concatenate: `bytes(prevHash) + rawPayloadBytes`.
4. Compute SHA-256 of the concatenation.
5. Compare with `currentHash`.

For the `tamperedChain`, verify that the chain breaks at the specified operation.

### 3. Conflict Resolution (TV02, TV06)

1. Apply all operations in `globalSequence` order.
2. After decrypting each payload, when a conflict is detected (same `childId` + same `effectiveFrom`), apply the client-side tie-breaking rules:
   - First tie-break: later `clientTimestamp` wins.
   - Second tie-break: lexicographically higher `deviceId` wins.
3. Verify the final state matches `expectedFinalState`.

### 4. State Machine (TV05)

For each entry in `validTransitions`, verify the transition is accepted during client-side replay.
For each entry in `invalidTransitions`, verify the transition is rejected with the specified error.
For each entry in `authorityConstraints`, verify proposer/responder rules are enforced.

### 5. Key Rotation (TV04)

Verify that:
- Ops 1-3 carry `keyEpoch: 1`.
- Ops 4-5 carry `keyEpoch: 2`.
- The revoked device cannot decrypt epoch 2 ops.
- Active devices can decrypt ops from both epochs.
- `DeviceRevocation` and `KeyRotation` entity types are handled correctly.

## Spec References

- `/docs/specs/wire-format.md` -- Wire format, serialization rules, JSON schemas
- `/docs/specs/sync-protocol.md` -- Sync protocol, ordering, conflict resolution
- `/docs/specs/encryption-spec.md` -- Encryption, key management, hash chains
- `/docs/specs/openapi.yaml` -- OpenAPI 3.1.0 HTTP-level contract

## Verification

All SHA-256 hashes in these test vectors were computed programmatically and can be independently verified. For example, to verify a hash chain entry:

```python
import hashlib, base64
prev_hash_bytes = bytes.fromhex("0000000000000000000000000000000000000000000000000000000000000000")
payload_bytes = base64.b64decode("dGVzdC1wYXlsb2FkLTE=")
result = hashlib.sha256(prev_hash_bytes + payload_bytes).hexdigest()
# Expected: 4f3eb937b67738b3e9afef89501ca534612f364af3e7210a1a7b2f3ae64e9d72
```

To verify canonical serialization of a v2 `DecryptedPayload`:

```python
import hashlib, json
# Full DecryptedPayload including wrapper fields
payload = {
    "deviceSequence": 1,
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
canonical = json.dumps(payload, separators=(',', ':'), sort_keys=True, ensure_ascii=False)
sha256 = hashlib.sha256(canonical.encode('utf-8')).hexdigest()
# Expected: 59be3dc47b9b1bf178f421681a433f7924da191c0cb88c8846db1093cf995f41
```
