# KidSync Phase 1 Protocol Specification Review

**Date:** 2026-02-20
**Review Models:** Gemini 3 Pro Preview (secaudit, analyze), Gemini 2.5 Pro (codereview)
**Review Tools:** PAL MCP `secaudit`, `codereview`, `analyze`
**Spec Version:** Protocol v1 (Phase 1 / Gate P0)

---

## Files Reviewed

| File | Lines | Description |
|------|-------|-------------|
| `docs/protocol/wire-format.md` | ~1908 | Wire format: OpLogEntry envelope, 8 payload types, canonical serialization |
| `docs/protocol/sync-protocol.md` | ~2002 | Sync protocol: hash chains, conflict resolution, WebSocket, snapshots |
| `docs/protocol/encryption-spec.md` | ~1910 | Encryption: X25519, AES-256-GCM, HKDF-SHA256, Ed25519, key epochs |
| `docs/api/openapi.yaml` | ~2118 | OpenAPI 3.1.0 REST API contract |
| `tests/conformance/tv01-tv07` | 7 files | Conformance test vectors |

---

## Critical Issues (Must Fix Before Implementation)

### SEC-01: Hash Chain Formula Contradiction
**Source:** Security Audit + Code Review (independently confirmed by both Gemini models)
**Files:** `sync-protocol.md` Section 6.1, `encryption-spec.md` Section 14, `tv03-hash-chain.json`

The hash chain computation formula -- the PRIMARY tamper detection mechanism -- is defined differently across specifications:

- **sync-protocol.md:** `currentHash = SHA256(bytes(devicePrevHash) + rawEncryptedPayloadBytes)` (raw byte concatenation)
- **encryption-spec.md:** Uses string concatenation with pipe separator: `SHA256(devicePrevHash_hex + "|" + encryptedPayload_base64)`
- **Test vectors (tv03):** Use the raw bytes approach: `SHA256(hexDecode(devicePrevHash) + base64Decode(encryptedPayload))`

**Impact:** Implementations following different specs will produce incompatible hashes, rendering the hash chain useless for interoperability. This is a total loss of tamper detection between client implementations.

**Recommendation:** Unify all specs to match tv03: `SHA256(RawBytes(HexDecode(prevHash)) + RawBytes(Base64Decode(encryptedPayload)))`. Add explicit byte-level examples in all three documents.

---

### SEC-02: AES-GCM AAD Inconsistency (Logical Impossibility)
**Source:** Security Audit (escalated by Gemini 3 Pro from High to Critical with new evidence)
**Files:** `encryption-spec.md` Section 12.1 diagram, Section 12.3, Section 12.6

The Additional Authenticated Data (AAD) for AES-256-GCM encryption is defined in three conflicting ways:

- **Section 12.1 diagram:** AAD = `deviceId + globalSequence`
- **Section 12.3 text:** AAD = UTF-8 bytes of `deviceId` only
- **Section 12.6 example:** AAD = `deviceId + "|" + keyEpoch`

The Section 12.1 formulation is **logically impossible**: `globalSequence` is assigned by the server AFTER the client uploads the encrypted payload. The client cannot include a server-assigned value in encryption AAD before uploading.

**Impact:** AES-GCM REQUIRES matching AAD for decryption. Mismatched AAD = authentication tag failure = **permanent data loss**. Every op encrypted with the wrong AAD is unreadable.

**Recommendation:** Standardize on AAD = `deviceId` only (Section 12.3 text) or `deviceId + "|" + deviceSequence` (which IS known at encryption time). Remove `globalSequence` from AAD entirely. Add a dedicated AAD test vector.

---

### SEC-03: AES-GCM Nonce Management Unspecified
**Source:** Security Audit
**Files:** `encryption-spec.md` Section 12

AES-256-GCM requires a unique 96-bit nonce per encryption operation with the same key. The spec does NOT define:
- Nonce generation strategy (random? counter-based? derived from deviceSequence?)
- Nonce storage location (prepended to ciphertext? separate field?)
- Nonce uniqueness guarantees

**Impact:** Nonce reuse with the same key **catastrophically breaks** AES-GCM confidentiality AND authenticity. This is not a theoretical concern; it enables plaintext recovery via XOR of ciphertexts.

**Recommendation:** Specify random 96-bit nonce generation. Require the nonce to be prepended to the ciphertext (standard practice). Add an explicit test vector showing nonce || ciphertext || tag layout.

---

### SEC-04: Plaintext vs Encrypted Field Boundary Undefined
**Source:** Security Audit + Code Review
**Files:** `openapi.yaml` OpInput/OpOutput, `wire-format.md` OpLogEntry, `encryption-spec.md`

The OpenAPI spec's divergence from wire-format creates a fundamental architectural ambiguity: it is unclear which OpLogEntry fields the server sees in plaintext vs which are inside the encrypted payload.

If `entityType`, `entityId`, `operation` are plaintext server metadata: this violates the "dumb relay" zero-knowledge principle.
If they are inside the encrypted payload: the server cannot index, route, or validate by them, and `OpOutput` cannot return them.

**Impact:** This is an architectural identity crisis. The protocol cannot be implemented until this boundary is explicitly defined.

**Recommendation:** Create a clear "Envelope vs Payload" table specifying exactly which fields are server-visible (plaintext envelope) and which are client-only (inside encrypted payload). Update all four specs to match.

---

### QA-01: OpenAPI OpInput Schema Missing 7 Required Fields
**Source:** Code Review (confirmed by both Gemini models)
**Files:** `openapi.yaml` OpInput schema vs `wire-format.md` OpLogEntry

The `OpInput` schema for `POST /sync/ops` is critically divergent from the protocol:

| Field | wire-format.md | openapi.yaml OpInput |
|-------|---------------|---------------------|
| `deviceId` | Required | Present |
| `deviceSequence` | Required | **MISSING** |
| `entityType` | Required | **MISSING** |
| `entityId` | Required | **MISSING** |
| `operation` | Required | **MISSING** |
| `keyEpoch` | Required | Present |
| `encryptedPayload` | Required | Present |
| `currentHash` | Required | **MISSING** |
| `devicePrevHash` | Required | Present |
| `clientTimestamp` | Required | **MISSING** |
| `protocolVersion` | Required | **MISSING** |
| `localId` | **Not defined** | Present (extra) |

**Impact:** The API contract cannot implement the protocol. Any implementation will violate either the API spec or the wire format.

**Recommendation:** Align `OpInput` with `OpLogEntry`. Keep `localId` as a transient correlation field (not persisted). Add all missing required fields.

---

### QA-02: OpenAPI OpOutput Schema Incomplete
**Source:** Code Review
**Files:** `openapi.yaml` OpOutput vs `wire-format.md`

`OpOutput` is missing `entityType`, `entityId`, `operation`, `protocolVersion`, `transitionTo`. Without `entityType` and `entityId`, clients cannot perform conflict resolution as defined in `sync-protocol.md` Section 9 (which requires grouping ops by entity type).

**Recommendation:** Add all missing fields to `OpOutput`.

---

### QA-03: KeyRotation Operation Schema Contradiction
**Source:** Code Review + Security Audit
**Files:** `encryption-spec.md` Section 11.4, `wire-format.md`, `sync-protocol.md` Section 5.3.3

The KeyRotation mechanism is defined in three mutually contradictory ways:
1. `encryption-spec.md`: A special `OpLogEntry` with `encryptedPayload: null` and custom `metadata` field
2. `wire-format.md`: Does NOT list `KeyRotation` as one of the 8 valid `payloadType` values
3. `sync-protocol.md`: Treats key rotation as a WebSocket notification, not an oplog entry

**Recommendation:** Unify the definition. Either make KeyRotation a proper oplog entry (add to wire-format, encrypt like other ops for consistency) or explicitly define it as an out-of-band server event (remove from encryption-spec's oplog format).

---

### ARCH-01: Protocol Version Translation Contradicts Zero-Knowledge Architecture
**Source:** Architecture Analysis (independently confirmed by both models)
**Files:** `sync-protocol.md` Section 12.3/12.5, `encryption-spec.md`

`sync-protocol.md` states the server MAY translate ops between protocol versions at the API boundary. However, the server CANNOT read encrypted payloads (zero-knowledge design). These are mutually exclusive.

**Impact:** The protocol version migration strategy is broken. Two clients on different versions cannot interoperate through server translation.

**Recommendation:** Remove server-side translation entirely. Define client-side version negotiation:
1. Newer clients must understand older payload formats (forward compatibility)
2. Old clients must reject ops with unknown `protocolVersion` and prompt for app upgrade
3. Newer clients may dual-write during a transition period

---

## Important Issues (Should Fix Before Beta)

### SEC-05: JWT Signing Algorithm Unspecified
**Files:** `openapi.yaml`, `sync-protocol.md`

No JWT signing algorithm is specified anywhere. This allows `alg: none` attacks (complete auth bypass) and HS256 attacks (symmetric key confusion with public key).

**Recommendation:** Mandate ES256 (ECDSA P-256) or EdDSA. Explicitly reject HS256 and `none`. Add algorithm to OpenAPI security scheme description.

---

### SEC-06: Password Hashing Without Work Factor
**Files:** `encryption-spec.md` Section 3, `openapi.yaml`

bcrypt is mentioned without a minimum cost parameter. Default implementations may use cost=10 or lower, which is insufficient against modern GPU cracking.

**Recommendation:** Switch to Argon2id with explicit parameters (m=64MB, t=3, p=4) or specify bcrypt minimum cost >= 12.

---

### SEC-07: Recovery Key Enables Snapshot Forgery
**Files:** `encryption-spec.md` Section 15

The BIP39 recovery mnemonic reconstructs the full Ed25519 signing key pair. A compromised recovery key enables not just data decryption but forgery of snapshots.

**Recommendation:** On recovery, generate a NEW signing key pair. Link it to the identity through a key rotation event. The recovery key should only restore decryption (X25519) capability, not signing capability.

---

### SEC-08: No Device Attestation for Enrollment
**Files:** `encryption-spec.md` Section 7

Device enrollment accepts any X25519 public key without attestation. An attacker with stolen credentials can enroll unlimited rogue devices, each receiving the current DEK.

**Recommendation:** Require TOTP verification for device enrollment. Consider platform attestation (Android Play Integrity, iOS DeviceCheck) for higher assurance.

---

### QA-04: Upload Response Format Mismatch
**Files:** `sync-protocol.md` Section 5.3.1, `openapi.yaml` POST /sync/ops response

- `sync-protocol.md`: `{ "accepted": [...], "rejected": [...] }`
- `openapi.yaml`: `{ "assignedSequences": [...] }` (no rejection mechanism)

**Recommendation:** Align on sync-protocol.md's `accepted`/`rejected` structure which handles partial failures.

---

### QA-05: HTTP Status Code Conflicts
**Files:** `sync-protocol.md` Appendix A, `openapi.yaml`

| Error | sync-protocol.md | openapi.yaml |
|-------|-----------------|-------------|
| Hash chain break | 400 Bad Request | 409 Conflict |
| Device revoked | 403 Forbidden | 401 Unauthorized |

**Recommendation:** Create a unified error code table. Use 409 Conflict for hash chain breaks (semantically correct) and 403 Forbidden for revoked devices (authenticated but not authorized).

---

### QA-06: Missing Handshake Endpoint in OpenAPI
**Files:** `sync-protocol.md` Section 3.2, `openapi.yaml`

The mandatory `/sync/handshake` endpoint for version negotiation and sequence checking is completely absent from `openapi.yaml`.

**Recommendation:** Add `POST /sync/handshake` with request/response schemas to `openapi.yaml`.

---

### QA-07: 6+ Missing Crypto Conformance Test Vectors
**Files:** `encryption-spec.md` Section 20.1, `tests/conformance/`

The spec defines 12 categories of required conformance tests. Only 7 test vector files exist. Missing:
- Encryption round-trip (AES-256-GCM with known key/nonce/plaintext)
- HKDF key derivation (known IKM/salt/info -> expected OKM)
- X25519 DEK wrapping/unwrapping (known key pairs -> expected wrapped DEK)
- Ed25519 snapshot signing (known message/key -> expected signature)
- Blob encryption round-trip
- Device enrollment flow
- gzip compression before encryption (known plaintext -> compressed -> encrypted)

**Impact:** Without crypto test vectors, cross-platform implementations cannot verify interoperability.

**Recommendation:** Generate test vectors for all 12 categories with known keys, nonces, and expected outputs.

---

### ARCH-02: Admin Role Governance Gap
**Files:** `encryption-spec.md` Section 11, `tv04-key-rotation.json`

The admin role is critical for security operations (device revocation, key rotation) but is completely undefined:
- No mechanism for initial admin assignment
- No admin role transfer protocol
- No fallback if admin's only device is lost
- No `isAdmin` field in OpenAPI `FamilyMember` schema

**Impact:** Family lockout scenario: if the admin loses all devices, no one can perform key rotation or device management.

**Recommendation:** Define admin as family creator. Add `isAdmin` to `FamilyMember` schema. Define admin succession rule (e.g., earliest-joining parent becomes admin if current admin leaves). Consider co-admin capability.

---

### ARCH-03: Single Snapshot Retention Fragility
**Files:** `sync-protocol.md` Section 10

Only one snapshot per family is retained. If corrupted during creation, upload, or storage, new devices cannot bootstrap efficiently.

**Recommendation:** Retain N=3 most recent snapshots. Add snapshot integrity verification on upload. Define snapshot repair protocol.

---

### ARCH-04: Sync Backlog Blocks UI
**Files:** `sync-protocol.md` Section 8

"Client MUST apply all pending ops from server before creating new local ops." After extended offline: pulling 1000+ ops blocks the UI.

**Recommendation:** Define streaming sync with UI updates after each batch. Allow optimistic local state with pending remote merge.

---

### ARCH-05: No Server Disaster Recovery Protocol
**Files:** `sync-protocol.md`, `encryption-spec.md`

Server data loss is unrecoverable despite clients having full local copies. No re-upload mechanism is defined.

**Recommendation:** Add `serverInstanceId` to handshake response. If a client detects a changed server instance, initiate "Restoration Mode" to re-upload local ops with new globalSequence assignment.

---

## Suggestions (Improve Before v1.0)

### SEC-09: WebSocket JWT in Query Parameter
**Files:** `sync-protocol.md` Section 5.1, `openapi.yaml`

JWT in URL appears in server logs, browser history, proxy logs. Use ticket-based auth or `Sec-WebSocket-Protocol` header instead.

---

### SEC-10: No Rate Limiting on Sync/Blob Endpoints
**Files:** `openapi.yaml`

Define rate limits per-device: sync/ops POST (100 ops/minute), blob upload (10/hour), device enrollment (3/day).

---

### SEC-11: Future Timestamp Conflict Exploitation
**Files:** `sync-protocol.md` Section 9.1, `tv06-clock-skew.json`

A malicious user could set their device clock far into the future to always win timestamp-based conflict tie-breaks. The server should reject ops with `clientTimestamp > serverTime + 5 minutes`.

---

### QA-08: Expense Status State Machine Incomplete
**Files:** `wire-format.md`, `sync-protocol.md` Section 9

No formal state machine for ExpenseStatus transitions between ACKNOWLEDGED, DISPUTED, SETTLED. Define explicit valid transitions similar to ScheduleOverride.

---

### QA-09: CustodySchedule Pattern Length Unconstrained
**Files:** `wire-format.md`

No schema constraint enforcing `pattern.length === cycleLengthDays`. Add JSON Schema constraint or explicit MUST requirement.

---

### QA-10: Field Name Inconsistency
**Files:** `wire-format.md`, `sync-protocol.md`

The envelope uses `operation` (CREATE/UPDATE/DELETE) while the payload uses `operationType`. Standardize on one name across all specs.

---

### ARCH-06: No Family Lifecycle Management
**Files:** All specs

No archival, dissolution, data export, or child migration mechanism. Define family states (ACTIVE, ARCHIVED, DISSOLVED) and data export format.

---

### ARCH-07: Unbounded Oplog Growth
**Files:** `sync-protocol.md`

Append-only log grows indefinitely with no compaction. Define log truncation: once a snapshot at sequence S is confirmed by multiple devices, ops with sequence < S can be archived.

---

### ARCH-08: WebSocket Reconnection Lacks Backoff
**Files:** `sync-protocol.md`

No exponential backoff, jitter, or max retry limits specified. Could cause thundering herd after server restarts. Specify reconnection strategy with `Retry-After` header support.

---

## Overall Assessment

### Strengths

The KidSync protocol demonstrates a strong foundation in modern cryptographic design and local-first architecture:

- **Strong crypto primitives:** AES-256-GCM, X25519 ECDH, HKDF-SHA256 with domain separation, Ed25519 -- all well-vetted, standards-based choices
- **Zero-knowledge server design** minimizes the blast radius of server compromise
- **Per-device hash chains** provide tamper detection without trusted server
- **Key epoch rotation** on device revocation provides forward secrecy for new operations
- **Deterministic conflict resolution** ensures eventual consistency without coordination
- **Canonical serialization** rules are precise, well-specified, with anti-pattern examples
- **BIP39 recovery** mechanism provides user-friendly backup with 256-bit entropy
- **Existing test vectors** (tv01-tv07) are well-structured and cover complex scenarios (concurrent merge, clock skew, state machine transitions)
- **Compression before encryption** (correct order, avoiding compression oracle attacks)
- **HaveIBeenPwned k-anonymity** password check is a thoughtful security-in-depth measure

### Weaknesses

The protocol is critically undermined by **specification contradictions** that render it **unimplementable as written**:

1. **Cross-document inconsistency** is the single biggest risk. The four spec documents were clearly written with partial independence, resulting in conflicting definitions for hash chains, AAD, field schemas, response formats, and HTTP status codes. An implementer following any single document will produce a client incompatible with an implementer following a different document.

2. **The OpenAPI contract is significantly behind** the protocol specs. It is missing required fields, endpoints, and error handling that the other three documents define.

3. **Missing crypto test vectors** make cross-platform verification impossible for the most critical operations (encryption/decryption, key derivation, signing).

4. **Governance and lifecycle gaps** (admin role, family dissolution, disaster recovery) will become painful in production but are addressable in later phases.

### Risk Summary

| Severity | Count | Status |
|----------|-------|--------|
| Critical | 7 | Must fix before any implementation begins |
| Important | 11 | Should fix before beta |
| Suggestion | 8 | Improve before v1.0 |

### Recommended Priority Order

1. **Immediate (Week 1):** Fix AAD definition, hash chain formula, nonce management, plaintext/encrypted boundary -- these are spec-only fixes with zero implementation cost
2. **Short-term (Week 2):** Reconcile OpenAPI with protocol specs (OpInput, OpOutput, response formats, status codes, handshake endpoint)
3. **Short-term (Week 2-3):** Generate missing crypto test vectors, resolve KeyRotation ambiguity
4. **Medium-term (Week 3-4):** Define admin governance, specify JWT algorithm, password hashing params
5. **Pre-beta:** Server disaster recovery, snapshot retention, sync streaming, rate limiting
6. **Pre-v1.0:** Family lifecycle, oplog compaction, WebSocket backoff, expense state machine

---

## Review Methodology

Three independent review cycles were executed using PAL MCP tools with Google Gemini models:

| Cycle | Tool | Model | Focus |
|-------|------|-------|-------|
| 1 - Security | `mcp__pal__secaudit` | gemini-3-pro-preview | OWASP Top 10, crypto, auth, key management, compliance |
| 2 - Code Quality | `mcp__pal__codereview` | gemini-2.5-pro | Schema consistency, edge cases, test coverage, determinism |
| 3 - Architecture | `mcp__pal__analyze` | gemini-3-pro-preview | Scalability, recovery, versioning, governance, lifecycle |

Each cycle performed a 2-step review: strategic analysis (step 1) followed by expert-validated detailed findings (step 2). Findings were cross-referenced between cycles and deduplicated. Issues confirmed by multiple review cycles are noted in the report.

**Note:** The user requested Cycle 2 use the "codex" model, but this model is not available in the PAL MCP tool suite (which only supports Gemini models). Gemini 2.5 Pro was substituted as the highest-capability alternative available.
