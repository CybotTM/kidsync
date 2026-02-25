# KidSync Encryption Specification

**Version:** 2.0-draft
**Date:** 2026-02-21
**Status:** Draft (zero-knowledge architecture)
**Applies to:** All clients (Android, iOS) and the sync server
**Related documents:** `wire-format.md`, `sync-protocol.md`, `openapi.yaml`

---

## Table of Contents

1. [Design Principles](#1-design-principles)
2. [Threat Model](#2-threat-model)
3. [Crypto Profile](#3-crypto-profile)
4. [Key Hierarchy](#4-key-hierarchy)
5. [Key Formats and Encoding](#5-key-formats-and-encoding)
6. [Device Keypair Generation and Storage](#6-device-keypair-generation-and-storage)
7. [Challenge-Response Authentication](#7-challenge-response-authentication)
8. [DEK Lifecycle](#8-dek-lifecycle)
9. [Key Epochs](#9-key-epochs)
10. [DEK Wrapping Protocol](#10-dek-wrapping-protocol)
11. [Key Attestation and Cross-Signing](#11-key-attestation-and-cross-signing)
12. [QR Code Pairing Cryptography](#12-qr-code-pairing-cryptography)
13. [OpLog Encryption and Decryption](#13-oplog-encryption-and-decryption)
14. [Blob Encryption](#14-blob-encryption)
15. [Hash Chain Integrity](#15-hash-chain-integrity)
16. [Snapshot Encryption and Signing](#16-snapshot-encryption-and-signing)
17. [Recovery Key](#17-recovery-key)
18. [Platform Implementation Notes](#18-platform-implementation-notes)
19. [Security Considerations](#19-security-considerations)
20. [Conformance Requirements](#20-conformance-requirements)
21. [Appendix A: Notation and Constants](#appendix-a-notation-and-constants)

---

## 1. Design Principles

1. **Zero-knowledge server.** The server never possesses plaintext data, DEKs, or private keys. It does not know user identities, entity types, operation types, or any business data. A compromised server yields only ciphertext and minimal structural metadata (device IDs, bucket IDs, key epochs, timing).

2. **Public key = identity.** No emails, passwords, or accounts. Each device is identified by its Ed25519 signing public key and authenticated via challenge-response.

3. **Defense in depth.** Data is protected at multiple layers: TLS in transit, AES-256-GCM for payload encryption, hardware-backed key storage on-device, and per-device hash chains for integrity.

4. **Forward secrecy on revocation.** When a device is revoked (client-side concept), remaining devices rotate the DEK. Future data is encrypted with a new key.

5. **Cross-platform determinism.** All cryptographic operations use identical algorithms, parameters, and encoding across Android and iOS. Conformance test vectors guarantee interoperability.

6. **Simplicity over cleverness.** Well-studied primitives (Ed25519, X25519, AES-256-GCM, HKDF-SHA256) composed in straightforward ways.

---

## 2. Threat Model

### 2.1 Adversaries

| Adversary | Capability | Goal |
|-----------|-----------|------|
| **Compromised server** | Full database access, network traffic interception | Read data, tamper with sync, substitute keys |
| **Network attacker** | TLS interception, traffic analysis | Read or modify data in transit |
| **Malicious co-parent** | Legitimate app access, valid device | Access data after removal, tamper with history |
| **Stolen device** | Physical access | Extract keys, read data |

### 2.2 Security Properties

| Property | Guaranteed | Notes |
|----------|-----------|-------|
| **Confidentiality** | Yes | Server and network attacker cannot read plaintext |
| **Integrity** | Yes | Hash chains, AES-GCM authentication, Ed25519 signatures |
| **Authentication** | Yes | Ed25519 challenge-response, no password brute-force possible |
| **Forward secrecy (post-revocation)** | Yes | DEK rotation after device revocation |
| **Key transparency** | Yes | Cross-signing detects server key substitution |
| **Metadata minimization** | Yes | Entity types, operations, timestamps all encrypted |
| **Availability** | No | Server can deny service (inherent to relay model) |

### 2.3 Assumptions

- Device OS and hardware security modules are not compromised.
- Users verify key fingerprints during QR code pairing.
- TLS 1.3 is enforced for all client-server communication.

---

## 3. Crypto Profile

No algorithm negotiation. Protocol version changes are required to change any algorithm.

| Function | Algorithm | Parameters |
|----------|-----------|------------|
| **Signing / Auth** | Ed25519 (RFC 8032) | 32-byte seed, 32-byte public key, 64-byte signature |
| **Key Agreement** | X25519 (RFC 7748) | 32-byte private key (derived from Ed25519 seed), 32-byte public key |
| **Payload Encryption** | AES-256-GCM | 256-bit key, 96-bit nonce, 128-bit auth tag |
| **Key Derivation** | HKDF-SHA256 (RFC 5869) | Variable-length IKM, application-specific salt and info |
| **Hash** | SHA-256 | 256-bit output; hash chains, checksums, fingerprints |
| **Compression** | gzip (RFC 1952) | Applied to plaintext before encryption |
| **Encoding** | Base64 (RFC 4648, standard, with padding) | Binary-to-text in JSON fields |
| **Recovery Mnemonic** | BIP39 (English wordlist) | 256 bits entropy = 24 words |

### 3.1 Platform Library Mapping

| Platform | Library | Notes |
|----------|---------|-------|
| **Android** | BouncyCastle + JCA | Ed25519 via BouncyCastle; X25519 derived via BouncyCastle; AES-256-GCM via `javax.crypto` |
| **iOS** | Apple CryptoKit | Ed25519 via `Curve25519.Signing`; X25519 via `Curve25519.KeyAgreement`; AES-GCM via `AES.GCM` |
| **Server** | None (no crypto on payloads) | Server verifies Ed25519 signatures for challenge-response auth only |

### 3.2 Nonce Generation

All AES-256-GCM nonces are 96 bits (12 bytes) from CSRNG. Birthday bound analysis: ~2^48 messages per key before collision risk. KidSync produces at most ~10^4 ops/year, far below the danger threshold.

---

## 4. Key Hierarchy

```
                    +---------------------------+
                    |   Recovery Key             |
                    |   (256-bit, BIP39 24       |
                    |   words + optional         |
                    |   passphrase "25th word")  |
                    |   User holds offline       |
                    +-----------+---------------+
                                | encrypts recovery blob
                                v
                    +---------------------------+
                    |   Recovery Blob            |
                    |   (on server, encrypted)   |
                    |   Contains: seed,          |
                    |   bucketIds, DEKs          |
                    +---------------------------+

+----------------------------------------------------------------------+
| BUCKET SCOPE                                                          |
|                                                                       |
|   +---------------------------+                                       |
|   |   Bucket DEK              |                                       |
|   |   (AES-256, symmetric)    |<-- One per bucket, versioned by       |
|   |   256-bit random          |    key epoch (monotonic integer)      |
|   +----------+----------------+                                       |
|              |                                                        |
|              | encrypts                                               |
|              v                                                        |
|   +---------------------------+   +---------------------------+       |
|   |   Op Payloads             |   |   Blob Keys               |       |
|   |   (AES-256-GCM)           |   |   (per-blob AES-256,      |       |
|   |   Contains ALL metadata   |   |    inside encrypted op)   |       |
|   +---------------------------+   +---------------------------+       |
|                                                                       |
+----------------------------------------------------------------------+

+----------------------------------------------------------------------+
| DEVICE SCOPE (one per device)                                         |
|                                                                       |
|   +---------------------------+                                       |
|   |   32-byte Seed            |                                       |
|   |   (stored once in         |                                       |
|   |    hardware-backed store) |                                       |
|   +-----+----------+---------+                                       |
|         |          |                                                  |
|     derive      derive (crypto_sign_ed25519_sk_to_curve25519)        |
|         |          |                                                  |
|         v          v                                                  |
|   +-----------+ +-----------+                                        |
|   | Ed25519   | | X25519    |                                        |
|   | Key Pair  | | Key Pair  |                                        |
|   | (signing) | | (encrypt) |                                        |
|   +-----------+ +-----------+                                        |
|         |          |                                                  |
|         |    used to unwrap                                           |
|         |          v                                                  |
|         |   +-----------+                                            |
|         |   | Wrapped   |<-- One per (device, epoch) pair            |
|         |   | DEK Blob  |    Server stores, cannot decrypt           |
|         |   +-----------+                                            |
|         |                                                             |
|     used for                                                         |
|         v                                                            |
|   +---------------------------+                                       |
|   | Challenge-Response Auth   |                                       |
|   | Key Attestations          |                                       |
|   | Snapshot Signing          |                                       |
|   +---------------------------+                                       |
+----------------------------------------------------------------------+
```

---

## 5. Key Formats and Encoding

### 5.1 Raw Key Material

| Key | Raw Size | Format |
|-----|----------|--------|
| Device seed | 32 bytes | Raw random bytes |
| Ed25519 private key | 64 bytes | Seed (32 bytes) + public key (32 bytes), per RFC 8032 |
| Ed25519 public key | 32 bytes | Compressed point |
| X25519 private key | 32 bytes | Derived from Ed25519 seed via `crypto_sign_ed25519_sk_to_curve25519` |
| X25519 public key | 32 bytes | Curve25519 u-coordinate |
| Bucket DEK | 32 bytes | Raw AES-256 key |
| Per-Blob Key | 32 bytes | Raw AES-256 key |
| AES-256-GCM nonce | 12 bytes | Random |
| AES-256-GCM tag | 16 bytes | Appended to ciphertext |
| Challenge nonce | 32 bytes | Random, server-generated |

### 5.2 X25519 Derivation from Ed25519 Seed

Both keypairs are derived from a single 32-byte seed:

```
seed = CSRNG(32)

// Ed25519 keypair
ed25519_private = Ed25519_ExpandSeed(seed)    // 64 bytes
ed25519_public  = Ed25519_PublicFromSeed(seed) // 32 bytes

// X25519 keypair (derived, not independently generated)
x25519_private = crypto_sign_ed25519_sk_to_curve25519(ed25519_private)
x25519_public  = crypto_sign_ed25519_pk_to_curve25519(ed25519_public)
```

This derivation is deterministic: the same seed always produces the same Ed25519 and X25519 keypairs. This means only one 32-byte value needs to be stored and backed up.

---

## 6. Device Keypair Generation and Storage

### 6.1 Key Generation

At first launch, the device:

1. Generates 32 bytes of entropy from CSRNG (the **seed**).
2. Derives Ed25519 keypair from the seed.
3. Derives X25519 keypair from the Ed25519 private key.
4. Stores the seed in hardware-backed secure storage.
5. Never stores derived keys separately (they are re-derived on demand).

### 6.2 Private Key Storage

| Platform | Storage | Extraction Policy |
|----------|---------|-------------------|
| Android | Android Keystore (StrongBox if available, TEE otherwise) via EncryptedSharedPreferences | Non-extractable from hardware |
| iOS | Secure Enclave (if available), otherwise Keychain with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` | Non-extractable from Secure Enclave |

**Fallback:** If hardware-backed storage is unavailable (emulator, old device):
1. Store seed encrypted in software keychain.
2. Display a warning to the user.
3. Set `hardwareBacked: false` internally (this is NOT sent to the server).

### 6.3 Key Lifecycle

```
+-------------+     +--------------+     +---------------+
|  Generated  |---->|  Registered  |---->|   Revoked     |
|  (seed on   |     |  (pub keys   |     |  (self-revoke |
|   device)   |     |   on server) |     |   or recovery)|
+-------------+     +--------------+     +---------------+
                           |
                           | recovery flow
                           v
                    +--------------+
                    |  Replaced    |
                    |  (new seed,  |
                    |   new keys)  |
                    +--------------+
```

---

## 7. Challenge-Response Authentication

### 7.1 Challenge Message Construction

The signed challenge message binds to multiple contexts to prevent replay:

```
msg = nonce (32 bytes, raw)
   || signingKey (32 bytes, raw Ed25519 public key)
   || serverOrigin (variable length, UTF-8 encoded, e.g., "https://api.kidsync.app")
   || timestamp (variable length, UTF-8 encoded ISO 8601, e.g., "2026-02-21T14:30:00.000Z")
```

The `||` operator denotes byte concatenation with no delimiters.

### 7.2 Signature

```
signature = Ed25519_Sign(ed25519_private_key, msg)
// 64 bytes
```

### 7.3 Server Verification

The server:
1. Looks up the device by `signingKey` in the `devices` table.
2. Retrieves the stored nonce by value. If not found or already consumed, rejects.
3. Verifies the nonce has not expired (60s TTL).
4. Consumes (deletes) the nonce atomically.
5. Reconstructs `msg` using the submitted `signingKey`, `nonce`, the server's own origin, and the submitted `timestamp`.
6. Verifies `Ed25519_Verify(signingKey, msg, signature)`.
7. Verifies `abs(serverTime - clientTimestamp) < 60 seconds`.

If all checks pass, the server issues an opaque session token.

### 7.4 Anti-Replay Properties

| Attack | Prevention |
|--------|------------|
| Replay same nonce | Nonce deleted on consumption |
| Delayed replay | 60s nonce TTL |
| Cross-device replay | signingKey bound in message |
| Cross-server replay | serverOrigin bound in message |
| Timestamp manipulation | Verified within 60s of server time |

---

## 8. DEK Lifecycle

### 8.1 DEK Creation

The first device to create a bucket generates the bucket DEK:

1. `DEK = CSRNG(32)` (256-bit AES key)
2. `keyEpoch = 1`
3. Store DEK locally in encrypted database.
4. Wrap DEK for own device (self-wrap, Section 10).
5. Upload wrapped DEK blob to server.

### 8.2 DEK Storage (Client-Side)

```
KeyRing = {
  bucketId: {
    1: <32 bytes>,    // epoch 1
    2: <32 bytes>,    // epoch 2
    ...
  },
  ...
}
```

Stored in device's encrypted local database (Room + SQLCipher on Android, encrypted CoreData on iOS).

### 8.3 DEK Destruction

DEKs are never explicitly destroyed because old epochs are needed to decrypt historical ops. The key ring grows monotonically. On the server, wrapped DEK blobs for removed devices are cleaned up when the device self-revokes.

---

## 9. Key Epochs

### 9.1 Epoch Numbering

- Monotonically increasing positive integers starting at 1.
- Each epoch corresponds to exactly one DEK.
- Globally unique within a bucket.

### 9.2 Epoch in Op Envelope

The `keyEpoch` field is one of the few pieces of information visible in the server-side op record, alongside `deviceId`, `encryptedPayload`, `prevHash`, and `currentHash`.

### 9.3 Epoch Advancement Rules

The epoch advances only on:
1. **Device revocation** (client-side concept): remaining devices generate new DEK.
2. **Suspected compromise**: a device marks another as compromised via encrypted op.

### 9.4 Decryption with Historical Epochs

1. Read `keyEpoch` from the op record.
2. Look up DEK in local key ring.
3. If not found, attempt to download historical wrapped DEK from server.
4. If unavailable, mark op as `UNDECRYPTABLE` and continue sync.

---

## 10. DEK Wrapping Protocol

### 10.1 Overview

DEK wrapping uses ephemeral X25519 key exchange to derive a one-time wrapping key, which encrypts the DEK with AES-256-GCM.

### 10.2 Wrapping Procedure

**Inputs:**
- `DEK`: 32-byte bucket DEK
- `recipientPublicKey`: target device's X25519 public key (32 bytes)
- `deviceId`: target device's UUID
- `keyEpoch`: the DEK epoch being wrapped

**Steps:**

```
1. Generate ephemeral X25519 key pair:
   ephemeralPrivate, ephemeralPublic = X25519_KeyGen(CSRNG)

2. Compute shared secret:
   sharedSecret = X25519(ephemeralPrivate, recipientPublicKey)

3. Generate random salt:
   salt = CSRNG(32)

4. Derive wrapping key:
   wrappingKey = HKDF-SHA256(
     IKM  = sharedSecret,
     salt = salt,
     info = "kidsync-dek-wrap-v1" || deviceId,
     L    = 32
   )

5. Generate random nonce:
   nonce = CSRNG(12)

6. Encrypt DEK:
   ciphertext || tag = AES-256-GCM-Encrypt(
     key       = wrappingKey,
     nonce     = nonce,
     plaintext = DEK,
     AAD       = "epoch=" || str(keyEpoch) || ",device=" || deviceId
   )

7. Securely erase: ephemeralPrivate, sharedSecret, wrappingKey

8. Construct wrapped DEK envelope with:
   ephemeralPublicKey, salt, nonce, ciphertext || tag
```

### 10.3 Unwrapping Procedure

**Inputs:**
- Wrapped DEK envelope
- `recipientPrivateKey`: this device's X25519 private key

**Steps:**

```
1. Parse envelope: extract ephemeralPublicKey, salt, nonce, ciphertext, deviceId, keyEpoch

2. Compute shared secret:
   sharedSecret = X25519(recipientPrivateKey, ephemeralPublicKey)

3. Derive wrapping key:
   wrappingKey = HKDF-SHA256(
     IKM  = sharedSecret,
     salt = salt,
     info = "kidsync-dek-wrap-v1" || deviceId,
     L    = 32
   )

4. Decrypt DEK:
   DEK = AES-256-GCM-Decrypt(
     key        = wrappingKey,
     nonce      = nonce,
     ciphertext = ciphertext,
     AAD        = "epoch=" || str(keyEpoch) || ",device=" || deviceId
   )

5. Verify DEK length == 32 bytes

6. Securely erase: sharedSecret, wrappingKey

7. Store DEK in local key ring under the given keyEpoch
```

### 10.4 Wrapped DEK Envelope Format

```json
{
  "version": 1,
  "keyEpoch": 1,
  "targetDevice": "550e8400-e29b-41d4-a716-446655440000",
  "wrappedBy": "660e8400-e29b-41d4-a716-446655440001",
  "algorithm": "X25519-HKDF-AES256GCM",
  "ephemeralPublicKey": "<base64, 32 bytes>",
  "salt": "<base64, 32 bytes>",
  "nonce": "<base64, 12 bytes>",
  "ciphertext": "<base64, 32 bytes DEK + 16 bytes GCM tag>",
  "createdAt": "2026-02-21T12:00:00Z"
}
```

---

## 11. Key Attestation and Cross-Signing

### 11.1 Attestation Signature

A key attestation is an Ed25519 signature by one device over another device's identity and encryption key:

```
attestation_message = attestedDeviceId (UTF-8, 36 bytes)
                   || attestedEncryptionKey (raw X25519 public key, 32 bytes)

signature = Ed25519_Sign(signer_ed25519_private, attestation_message)
```

### 11.2 Attestation Verification

To verify an attestation:

```
attestation_message = attestedDeviceId || attestedEncryptionKey
valid = Ed25519_Verify(signer_signing_key, attestation_message, signature)
```

The verifier must know the signer's Ed25519 public key through a trusted channel (e.g., the QR code fingerprint during pairing).

### 11.3 Attestation Format (Server-Stored)

```json
{
  "signerDevice": "device-A-uuid",
  "attestedDevice": "device-B-uuid",
  "attestedKey": "<base64, X25519 public key being attested>",
  "signature": "<base64, Ed25519 signature, 64 bytes>",
  "createdAt": "2026-02-21T12:00:00Z"
}
```

### 11.4 Cross-Signing Flow

1. **During pairing:** Device A's signing key fingerprint is in the QR code.
2. **After join:** Device A fetches Device B's encryption key, verifies it was the device that just joined, signs attestation, uploads.
3. **Device B verifies:** Fetches attestation, verifies signature using fingerprint from QR code.
4. **Device B reciprocates:** Signs Device A's encryption key, uploads attestation.
5. **Key change:** If a device's encryption key changes (recovery), existing attestations become invalid. Other devices detect unattested keys and require re-verification.

---

## 12. QR Code Pairing Cryptography

### 12.1 QR Code Contents

```json
{
  "v": 1,
  "s": "https://api.kidsync.app",
  "b": "bucket-uuid",
  "t": "invite-token-plaintext",
  "f": "SHA256-fingerprint-of-signing-key"
}
```

### 12.2 Key Fingerprint Computation

```
fingerprint = HexEncode(SHA256(ed25519_signing_public_key))[:32]
```

The first 32 hex characters (16 bytes) of the SHA-256 hash of the raw 32-byte Ed25519 public key. Displayed as: `A1B2C3D4 E5F67890 12345678 9ABCDEF0`.

### 12.3 What Is NOT in the QR Code

The DEK is **never** in the QR code. The QR code provides:
- Server URL (where to connect)
- Bucket ID (which namespace to join)
- Invite token (one-time access grant)
- Signing key fingerprint (trust anchor for cross-signing)

The DEK is exchanged via wrapped key exchange (Section 10) after both devices have authenticated and the joiner has been granted bucket access.

### 12.4 Security Reasoning

If the QR image is captured by an attacker:
1. The attacker gets the invite token and can join the bucket.
2. But the attacker generates their own keypair, which has a different fingerprint.
3. When Device A cross-signs, it signs the attacker's key -- but Device A will see an unexpected device fingerprint (not what it showed the co-parent).
4. The user should notice an unfamiliar device in the bucket device list.
5. Additionally, the attacker cannot forge attestations with the correct fingerprint.

---

## 13. OpLog Encryption and Decryption

### 13.1 Encryption Pipeline

```
+-----------+    +-----------+    +----------+    +-----------+    +----------+
| Decrypted |    |   JSON    |    |  gzip    |    | AES-256-  |    |  Base64  |
| Payload   |--->| Serialize |--->| Compress |--->| GCM       |--->|  Encode  |
| (all meta |    | (canon.)  |    |          |    | Encrypt   |    |          |
|  + data)  |    |           |    |          |    |           |    |          |
+-----------+    +-----------+    +----------+    +-----------+    +----------+
                                                       ^
                                                       |
                                           +-----------+-----------+
                                           | DEK[currentEpoch]     |
                                           | Nonce: random 12 bytes|
                                           | AAD: bucketId+deviceId|
                                           +-----------------------+
```

### 13.2 What Goes Inside the Encrypted Payload

ALL metadata and business data:

```json
{
  "deviceSequence": 47,
  "entityType": "CustodySchedule",
  "entityId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "operation": "CREATE",
  "clientTimestamp": "2026-02-21T14:30:00.000Z",
  "protocolVersion": 2,
  "data": {
    "scheduleId": "a1b2c3d4-...",
    "childId": "child-1",
    "anchorDate": "2026-03-17",
    "cycleLengthDays": 14,
    "pattern": ["parent-a", "parent-a", ...],
    "effectiveFrom": "2026-03-17T00:00:00Z",
    "timeZone": "Europe/Berlin"
  }
}
```

### 13.3 What the Server Sees Per Op

| Field | Visible? | Purpose |
|-------|----------|---------|
| globalSequence | Yes | Server-assigned ordering |
| deviceId | Yes | Hash chain per device |
| keyEpoch | Yes | DEK version lookup |
| encryptedPayload | No | Opaque ciphertext |
| prevHash | Yes | Hash chain integrity |
| currentHash | Yes | Hash chain integrity |
| serverTimestamp | Yes | Server's receive time |

### 13.4 Detailed Encryption Steps

```
1. Construct DecryptedPayload with all metadata + data fields

2. Serialize to canonical JSON:
   jsonBytes = canonicalJsonSerialize(decryptedPayload)
   // Sorted keys, no whitespace, omit nulls

3. Compress:
   compressed = gzip(jsonBytes)

4. Generate nonce:
   nonce = CSRNG(12)

5. Construct AAD:
   AAD = UTF-8(bucketId + "|" + deviceId)

6. Encrypt:
   ciphertext || tag = AES-256-GCM-Encrypt(
     key       = DEK[currentEpoch],
     nonce     = nonce,
     plaintext = compressed,
     AAD       = AAD
   )

7. Encode:
   encryptedPayload = Base64Encode(nonce || ciphertext || tag)

8. Construct OpInput:
   {
     "deviceId": "<UUID>",
     "keyEpoch": currentEpoch,
     "encryptedPayload": "<base64>",
     "prevHash": "<hex, 64 chars>",
     "currentHash": "<hex, 64 chars>"
   }
```

### 13.5 Decryption Steps

```
1. Base64 decode encryptedPayload

2. Split:
   nonce      = rawBytes[0..12]
   ciphertag  = rawBytes[12..]

3. Look up DEK:
   dek = keyRing.get(keyEpoch)
   if null: request wrapped DEK from server

4. Reconstruct AAD:
   AAD = UTF-8(bucketId + "|" + deviceId)

5. Decrypt:
   compressed = AES-256-GCM-Decrypt(dek, nonce, ciphertag, AAD)

6. Decompress:
   jsonBytes = gunzip(compressed)

7. Deserialize:
   decryptedPayload = jsonDeserialize(jsonBytes)
```

### 13.6 AAD Construction

The AAD binds the ciphertext to its context:

```
AAD = UTF-8(bucketId + "|" + deviceId)
```

- `bucketId` prevents cross-bucket replay
- `deviceId` prevents cross-device replay
- `globalSequence` is NOT in AAD (assigned after encryption)
- `keyEpoch` is authenticated via AES-GCM and AAD binding in the wrapped DEK

---

## 14. Blob Encryption

### 14.1 Per-Blob Key

Each blob uses a unique AES-256 key, NOT derived from the DEK:

```
blobKey = CSRNG(32)
```

The per-blob key is stored inside the OpLog entry that references the blob, encrypted by the DEK.

### 14.2 Blob Encryption Procedure

```
1. blobKey = CSRNG(32)
   blobNonce = CSRNG(12)

2. encryptedBlob = AES-256-GCM-Encrypt(blobKey, blobNonce, rawBlobBytes, AAD=blobId)

3. Upload: POST /buckets/{id}/blobs
   Body: blobNonce || encryptedBlob || tag

4. Include in OpLog payload (before encryption):
   {
     "data": {
       "receiptBlobId": "<blobId>",
       "receiptBlobKey": "<base64(blobKey)>",
       "receiptBlobNonce": "<base64(blobNonce)>"
     }
   }
```

---

## 15. Hash Chain Integrity

### 15.1 Per-Device Hash Chain

```
currentHash = SHA256(bytes(prevHash) || encryptedPayloadBytes)
```

For the first op: `prevHash = "0000...0000"` (64 hex zeros).

The hash chain uses encrypted payload bytes (ciphertext), allowing the server to verify integrity without decrypting.

### 15.2 Server Checkpoint Hash

Every 100 ops:

```
checkpointHash = SHA256(encPayloadBytes[start] || ... || encPayloadBytes[end])
```

### 15.3 Tamper Detection

| Attack | Detection |
|--------|-----------|
| Payload modified | Hash chain break + AES-GCM auth failure |
| Op deleted | Hash chain break (missing link) |
| Op reordered | Monotonic sequence violation + hash chain break |
| Op replayed | Duplicate sequence + hash chain branch |
| Checkpoint tampered | Client-computed hash mismatch |

---

## 16. Snapshot Encryption and Signing

### 16.1 Creation

```
1. Serialize complete bucket state to canonical JSON
2. Compress with gzip
3. Encrypt:
   nonce = CSRNG(12)
   encrypted = AES-256-GCM-Encrypt(
     DEK[currentEpoch], nonce, compressed,
     AAD = "snapshot:" || str(sequenceNo) || ":" || str(keyEpoch)
   )
4. Sign:
   signaturePayload = SHA256(nonce || encrypted || tag)
   signature = Ed25519_Sign(devicePrivateKey, signaturePayload)
5. Upload with metadata + signature
```

### 16.2 Verification

```
1. Verify Ed25519 signature against creating device's known signing key
2. Decrypt with DEK[snapshot.keyEpoch]
3. Decompress and deserialize
4. Load state into local database
```

---

## 17. Recovery Key

### 17.1 Generation

```
1. entropy = CSRNG(32)   // 256 bits
2. words = BIP39-Encode(entropy)   // 24 English words
3. Display to user, require acknowledgment
```

### 17.2 Optional BIP39 Passphrase (25th Word)

For security-conscious users, the app supports an optional BIP39 passphrase:

- **Without passphrase:** Standard 24-word recovery (default, simpler UX).
- **With passphrase:** 24 words + user-chosen passphrase. Both are needed.
- The passphrase is NOT stored anywhere (not on device, not on server).
- A stolen mnemonic alone is useless without the passphrase.
- Presented as opt-in during onboarding.

### 17.3 Recovery Key Derivation

```
recoveryKey = HKDF-SHA256(
  IKM  = BIP39-Decode(words) || UTF-8(passphrase),
  salt = "kidsync-recovery-v2",
  info = "recovery-key",
  L    = 32
)
```

If no passphrase, `passphrase` is the empty string.

### 17.4 Recovery Blob Contents

```json
{
  "version": 2,
  "seed": "<base64, 32 bytes>",
  "bucketIds": ["uuid1", "uuid2"],
  "deks": {
    "uuid1": { "1": "<base64, DEK epoch 1>", "2": "<base64, DEK epoch 2>" },
    "uuid2": { "1": "<base64, DEK epoch 1>" }
  }
}
```

The blob contains the device seed (from which Ed25519 and X25519 keys are derived), bucket IDs, and all DEK epochs.

### 17.5 Recovery Blob Encryption

```
1. Derive recoveryKey (Section 17.3)
2. Serialize recovery blob to JSON
3. nonce = CSRNG(12)
4. encrypted = AES-256-GCM-Encrypt(recoveryKey, nonce, blobJson, AAD="recovery")
5. Upload: POST /recovery
   Body: { encryptedBlob: Base64(nonce || encrypted || tag) }
```

### 17.6 Recovery Flow

```
1. User enters 24 words + optional passphrase on new device
2. Derive recoveryKey
3. New device generates new seed + keypairs
4. New device registers with server (POST /register)
5. New device authenticates (challenge-response)
6. New device downloads encrypted recovery blob (GET /recovery)
   Note: recovery blob is associated with a signing key;
   user provides old signing key fingerprint to locate it
7. Decrypt recovery blob with recoveryKey
8. Extract old seed, bucketIds, DEKs
9. Can now decrypt all historical ops
10. Re-wrap DEKs for new device's keys (self-wrap)
11. Upload new wrapped DEKs
12. Update recovery blob with new seed + new bucket access
```

---

## 18. Platform Implementation Notes

### 18.1 Android

```kotlin
// Seed generation and storage
val seed = SecureRandom().generateSeed(32)
// Store in EncryptedSharedPreferences backed by Android Keystore

// Ed25519 from seed (using libsodium via Lazysodium)
val ed25519KeyPair = lazySodium.cryptoSignSeedKeypair(seed)

// X25519 derived from Ed25519
val x25519Private = lazySodium.convertSecretKeyEd25519ToCurve25519(ed25519KeyPair.secretKey)
val x25519Public = lazySodium.convertPublicKeyEd25519ToCurve25519(ed25519KeyPair.publicKey)

// Challenge-response signing
val msg = nonce + signingKey + serverOrigin.toByteArray() + timestamp.toByteArray()
val signature = lazySodium.cryptoSignDetached(msg, ed25519KeyPair.secretKey)

// AES-256-GCM via javax.crypto
val cipher = Cipher.getInstance("AES/GCM/NoPadding")
cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, nonce))
cipher.updateAAD(aad)
val ciphertext = cipher.doFinal(compressed)
```

### 18.2 iOS

```swift
// Seed generation
var seed = Data(count: 32)
_ = seed.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }

// Ed25519 from seed
let ed25519Private = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
let ed25519Public = ed25519Private.publicKey

// X25519 from Ed25519 seed
let x25519Private = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: seed)
let x25519Public = x25519Private.publicKey

// Challenge-response signing
let msg = nonce + signingKey.rawRepresentation + serverOrigin.data(using: .utf8)! + timestamp.data(using: .utf8)!
let signature = try ed25519Private.signature(for: msg)

// AES-256-GCM
let sealedBox = try AES.GCM.seal(compressed, using: SymmetricKey(data: dek), nonce: AES.GCM.Nonce(data: nonce), authenticating: aad)
```

### 18.3 Server

The server performs:
- Ed25519 signature verification during challenge-response authentication
- Hash chain verification on ciphertext (SHA-256 only)
- Checkpoint hash computation

The server MUST NOT:
- Decrypt any `encryptedPayload`
- Unwrap any DEK
- Access any private key
- Inspect payload contents

---

## 19. Security Considerations

### 19.1 Server Key Substitution

**Risk:** Compromised server replaces a device's encryption key with its own.
**Mitigation:** Cross-signing (Section 11). Device B's key is attested by Device A, whose fingerprint was verified via QR code. The server cannot forge Device A's Ed25519 signature.

### 19.2 Nonce Reuse in AES-256-GCM

**Risk:** Catastrophic confidentiality and authenticity loss.
**Mitigation:** Random 96-bit nonces from CSRNG. Birthday bound: 2^48 messages per key. KidSync: ~10^4 ops/year << 2^48.

### 19.3 Key Compromise

**Impact:** Can unwrap all wrapped DEK blobs, decrypt all ops for those epochs.
**Mitigation:** Hardware-backed storage, device revocation triggers DEK rotation.

### 19.4 Recovery Key Compromise

**Impact:** Full access to all historical data across all buckets.
**Mitigation:** Optional BIP39 passphrase (25th word) makes stolen mnemonic useless without passphrase. Secure physical storage of mnemonic.

### 19.5 Metadata Exposure (Minimized)

| Visible to Server | Not Visible (now encrypted) |
|-------------------|----------------------------|
| Device IDs, bucket IDs | Entity types, entity IDs |
| Key epochs | Operation types |
| Encrypted payload sizes | Client timestamps |
| Server timestamps | Device sequence numbers |
| Access patterns | Override state transitions |
| IP addresses | All business data content |

### 19.6 Quantum Resistance

Ed25519 and X25519 are not quantum-resistant. Future protocol version can adopt hybrid key exchange (X25519 + ML-KEM). AES-256 remains secure against Grover's algorithm (128-bit effective security).

---

## 20. Conformance Requirements

### 20.1 Test Vector Categories

| ID | Description |
|----|-------------|
| **TV-SEED-01** | Ed25519 + X25519 derivation from seed: given seed, verify both keypairs |
| **TV-AUTH-01** | Challenge message construction and signing: given seed, nonce, origin, timestamp, verify signature |
| **TV-ATTEST-01** | Key attestation: given signer seed, attested device ID + key, verify signature |
| **TV-WRAP-01** | DEK wrapping with known inputs: verify ciphertext |
| **TV-WRAP-02** | DEK unwrapping: verify recovered DEK |
| **TV-OPLOG-01** | OpLog encryption: given DEK, nonce, DecryptedPayload, verify encryptedPayload |
| **TV-OPLOG-02** | OpLog decryption: given encryptedPayload, DEK, verify DecryptedPayload |
| **TV-HASH-01** | Hash chain: given sequence of encryptedPayloads, verify chain |
| **TV-HASH-02** | Checkpoint hash: given ops in range, verify hash |
| **TV-RECOVERY-01** | Recovery blob encryption with optional passphrase |
| **TV-RECOVERY-02** | Recovery blob decryption |
| **TV-FINGERPRINT-01** | Key fingerprint: given Ed25519 public key, verify hex fingerprint |
| **TV-BLOB-01** | Blob encryption and decryption |
| **TV-SNAPSHOT-01** | Snapshot signing and verification |
| **TV-HKDF-01** | HKDF-SHA256 with known inputs |
| **TV-CANONICAL-01** | Canonical JSON serialization (sorted keys, compact) |

### 20.2 Test Vector Format

```json
{
  "id": "TV-AUTH-01",
  "description": "Challenge-response signing with deterministic inputs",
  "inputs": {
    "seed": "<hex, 32 bytes>",
    "nonce": "<hex, 32 bytes>",
    "serverOrigin": "https://api.kidsync.app",
    "timestamp": "2026-02-21T14:30:00.000Z"
  },
  "expected": {
    "signingPublicKey": "<hex, 32 bytes>",
    "encryptionPublicKey": "<hex, 32 bytes>",
    "challengeMessage": "<hex>",
    "signature": "<hex, 64 bytes>"
  }
}
```

---

## Appendix A: Notation and Constants

### A.1 Notation

| Symbol | Meaning |
|--------|---------|
| `\|\|` | Byte concatenation |
| `CSRNG(n)` | n bytes from cryptographically secure RNG |
| `X25519(private, public)` | X25519 Diffie-Hellman (RFC 7748) |
| `HKDF-SHA256(IKM, salt, info, L)` | HKDF extract-and-expand (RFC 5869) |
| `AES-256-GCM-Encrypt(key, nonce, plaintext, AAD)` | Authenticated encryption |
| `AES-256-GCM-Decrypt(key, nonce, ciphertext, AAD)` | Authenticated decryption |
| `Ed25519_Sign(privateKey, message)` | Ed25519 signature (RFC 8032) |
| `Ed25519_Verify(publicKey, message, signature)` | Ed25519 verification |
| `SHA256(data)` | SHA-256 hash |
| `Base64(data)` | Base64 encoding (RFC 4648, standard, with padding) |
| `Base64url(data)` | Base64url encoding (RFC 4648, URL-safe, no padding) |
| `HexEncode(data)` | Hexadecimal encoding (lowercase) |

### A.2 Constants

| Constant | Value | Usage |
|----------|-------|-------|
| `HKDF_INFO_DEK_WRAP` | `"kidsync-dek-wrap-v1"` | HKDF info for DEK wrapping |
| `HKDF_SALT_RECOVERY` | `"kidsync-recovery-v2"` | HKDF salt for recovery key derivation |
| `GENESIS_PREV_HASH` | 64 hex zeros | Previous hash for first op in chain |
| `INITIAL_KEY_EPOCH` | `1` | First DEK epoch |
| `CHALLENGE_NONCE_BYTES` | `32` | Challenge nonce size |
| `CHALLENGE_TTL_SECONDS` | `60` | Challenge nonce validity |
| `TIMESTAMP_DRIFT_SECONDS` | `60` | Max client-server clock difference |
| `INVITE_TOKEN_BYTES` | `32` | Invite token entropy |
| `INVITE_EXPIRY_HOURS` | `24` | Invite token validity |
| `FINGERPRINT_HEX_CHARS` | `32` | Key fingerprint display length (16 bytes) |
| `SNAPSHOT_INTERVAL_OPS` | `500` | Approximate ops between snapshots |
| `CHECKPOINT_INTERVAL_OPS` | `100` | Ops between checkpoints |
| `MAX_BLOB_SIZE_BYTES` | `10485760` | Maximum blob size (10 MiB) |
| `MAX_PAYLOAD_SIZE_BYTES` | `65536` | Maximum encrypted payload (64 KiB) |
| `ALGORITHM_ID` | `"X25519-HKDF-AES256GCM"` | Algorithm identifier in wrapped DEK |

---

## Revision History

| Version | Date | Changes |
|---------|------|---------|
| 1.0-draft | 2026-02-20 | Initial specification (email/password auth) |
| 2.0-draft | 2026-02-21 | Zero-knowledge rewrite: Ed25519 auth, X25519 derivation from seed, challenge-response, key attestations, QR pairing, all metadata encrypted, optional BIP39 passphrase |
