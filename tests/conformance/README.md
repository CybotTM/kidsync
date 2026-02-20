# Conformance Test Vectors

This directory contains JSON test vector files for verifying implementations of the KidSync co-parenting app sync protocol. All client implementations (Android/Kotlin, iOS/Swift) MUST pass these test vectors before Gate P0 (Protocol Freeze).

## Test Vector Files

| File | ID | Description |
|------|----|-------------|
| `tv01-single-device-ops.json` | TV01 | Single device creating a custody schedule, 3 expenses, and 1 swap request. Includes canonical serialized forms and SHA-256 hashes. |
| `tv02-concurrent-offline-merge.json` | TV02 | Two devices creating conflicting custody schedules offline with the same `effectiveFrom`. Verifies deterministic conflict resolution (later `clientTimestamp` wins tie). |
| `tv03-hash-chain.json` | TV03 | Per-device hash chain verification with a valid 5-op chain and a tampered variant. All SHA-256 hashes are computed from real inputs. |
| `tv04-key-rotation.json` | TV04 | Key rotation triggered by device revocation. Epoch 1 (3 ops) then epoch 2 (2 ops). Verifies `keyEpoch` correctness and forward secrecy. |
| `tv05-override-state-machine.json` | TV05 | All valid and invalid override state transitions, plus proposerId/responderId authority constraints. |
| `tv06-clock-skew.json` | TV06 | Two devices with 2-hour clock difference creating conflicting schedules. Demonstrates that `globalSequence` determines application order while `clientTimestamp` is only a tie-breaker. |
| `tv07-canonical-serialization.json` | TV07 | Canonical serialization edge cases: missing optional fields, numeric precision, UUID formatting, and anti-patterns. |

## Test Vector Format

Each JSON file follows this general structure:

```json
{
  "id": "TV01",
  "title": "Human-readable title",
  "description": "What this test vector verifies",
  "protocolVersion": 1,
  "specReferences": ["wire-format.md Section X", "sync-protocol.md Section Y"],
  "context": { /* shared identifiers: familyId, childId, parentIds, deviceIds */ },
  "operations": [ /* or scenario-specific structure */ ],
  "expectedFinalState": { /* the deterministic result after applying all ops */ }
}
```

### Key Conventions

- **UUIDs** are lowercase hex with hyphens (RFC 4122 v4 format).
- **Timestamps** use ISO 8601 with exactly 3 fractional digits and `Z` suffix: `YYYY-MM-DDThh:mm:ss.sssZ`.
- **SHA-256 hashes** are 64-character lowercase hex strings.
- **Base64** uses standard RFC 4648 alphabet with padding.
- **Canonical JSON** means sorted keys (lexicographic), compact (no whitespace), null fields omitted.

## How to Use These Test Vectors

### 1. Canonical Serialization (TV01, TV07)

Given the `decryptedPayload` (or `input`) object, your implementation should:

1. Serialize to JSON with sorted keys, compact form, null fields omitted.
2. Compare the output byte-for-byte with `expectedCanonicalJson` (or `canonicalJson`).
3. Compute SHA-256 of the canonical UTF-8 bytes and compare with `expectedSha256` (or `canonicalSha256`).

### 2. Hash Chain Verification (TV03)

For each op in the `validChain`:

1. Hex-decode `devicePrevHash` to 32 raw bytes.
2. Base64-decode `encryptedPayload` to raw bytes.
3. Concatenate: `bytes(devicePrevHash) + rawPayloadBytes`.
4. Compute SHA-256 of the concatenation.
5. Compare with `currentHash`.

For the `tamperedChain`, verify that the chain breaks at the specified operation.

### 3. Conflict Resolution (TV02, TV06)

1. Apply all operations in `globalSequence` order.
2. When a conflict is detected (same `childId` + same `effectiveFrom`), apply the tie-breaking rules:
   - First tie-break: later `clientTimestamp` wins.
   - Second tie-break: lexicographically higher `deviceId` wins.
3. Verify the final state matches `expectedFinalState`.

### 4. State Machine (TV05)

For each entry in `validTransitions`, verify the transition is accepted.
For each entry in `invalidTransitions`, verify the transition is rejected with the specified error.
For each entry in `authorityConstraints`, verify proposer/responder rules are enforced.

### 5. Key Rotation (TV04)

Verify that:
- Ops 1-3 carry `keyEpoch: 1`.
- Ops 4-5 carry `keyEpoch: 2`.
- The revoked device cannot decrypt epoch 2 ops.
- Active devices can decrypt ops from both epochs.

## Spec References

- `/docs/protocol/wire-format.md` -- Wire format, serialization rules, JSON schemas
- `/docs/protocol/sync-protocol.md` -- Sync protocol, ordering, conflict resolution
- `/docs/protocol/encryption-spec.md` -- Encryption, key management, hash chains

## Verification

All SHA-256 hashes in these test vectors were computed programmatically and can be independently verified. For example, to verify a hash chain entry:

```python
import hashlib, base64
prev_hash_bytes = bytes.fromhex("0000000000000000000000000000000000000000000000000000000000000000")
payload_bytes = base64.b64decode("dGVzdC1wYXlsb2FkLTE=")
result = hashlib.sha256(prev_hash_bytes + payload_bytes).hexdigest()
# Expected: 4f3eb937b67738b3e9afef89501ca534612f364af3e7210a1a7b2f3ae64e9d72
```

To verify canonical serialization:

```python
import hashlib, json
payload = {"childId":"...", "entityId":"...", ...}  # your payload object
canonical = json.dumps(payload, separators=(',', ':'), sort_keys=True, ensure_ascii=False)
sha256 = hashlib.sha256(canonical.encode('utf-8')).hexdigest()
```
