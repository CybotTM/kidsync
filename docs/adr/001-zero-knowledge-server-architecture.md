# ADR-001: Zero-Knowledge Server Architecture

**Status:** Proposed
**Date:** 2026-02-21
**Decision Makers:** Project maintainers

## Context

KidSync is a co-parenting app that advertises itself as "privacy-first" with end-to-end encryption. The server is described as a "dumb relay" that cannot decrypt user data.

However, a security audit (2026-02-21) revealed that the current implementation contradicts this claim. The server stores and has access to significant plaintext metadata:

| Data | Storage | Justification |
|------|---------|---------------|
| User email | Plaintext in `Users` table | Email/password authentication |
| Display name | Plaintext in `Users` table | Member list API response |
| Password hash | BCrypt in `Users` table | Email/password authentication |
| TOTP secret | Plaintext in `Users` table | Server-side TOTP verification |
| Family name | Plaintext in `Families` table | Family management |
| Family membership | `FamilyMembers` table | Server-side authorization |
| Entity type | Plaintext in `OpLog` | Server-side validation |
| Entity ID | Plaintext in `OpLog` | Override state machine |
| Operation type | Plaintext in `OpLog` | Server-side validation |
| Client timestamp | Plaintext in `OpLog` | Stored alongside ops |
| Override state | `OverrideStates` table | Server-side state machine |
| Device name | Plaintext in `Devices` table | Device management UI |

This metadata leakage means a compromised server (or a subpoena) reveals:
- Who uses the app and their email addresses
- Which users are co-parenting together
- What types of data they exchange and when
- The state of custody override negotiations

This contradicts the "server cannot see user data" promise and creates legal liability under GDPR (unnecessary PII processing).

## Decision

Redesign the server to be a true zero-knowledge encrypted storage hub. The server will authenticate clients via public key challenge-response, store only opaque encrypted blobs, and have no visibility into user identity, relationships, or data structure.

### What changes

1. **Authentication**: Email/password replaced by Ed25519 challenge-response with anti-replay (context-bound nonces)
2. **Identity**: Public key IS the identity. No email, display name, or TOTP on server
3. **Storage model**: "Families" become anonymous "buckets" -- server sees only bucket IDs
4. **OpLog metadata**: `entityType`, `entityId`, `operation`, `clientTimestamp` move inside the encrypted payload
5. **Override state machine**: Moves from server to client. Each client processes ops and computes state locally
6. **Pairing**: Server-managed invites replaced by QR code / out-of-band key exchange. QR contains invite token and key fingerprint only -- never the DEK
7. **Key transparency**: Devices cross-sign each other's keys to detect server key substitution
8. **Revocation**: Self-revoke only on server; admin revocation via signed ops in encrypted payload
9. **Account recovery**: BIP39 mnemonic + optional passphrase (25th word). No email-based recovery
10. **Bucket deletion**: `DELETE /buckets/{id}` for data purge (creator only)

### What stays the same

- AES-256-GCM payload encryption with AAD binding
- X25519 ECDH for DEK wrapping
- HKDF-SHA256 key derivation
- Hash chain integrity verification (works on encrypted bytes, no changes needed)
- Checkpoint system (hashes over encrypted payloads, no changes needed)
- BIP39 mnemonic recovery flow

## Consequences

### Benefits

- **True zero-knowledge**: Server stores only public keys and encrypted blobs
- **Legal simplification**: No PII on server, reduced GDPR/CCPA scope
- **Subpoena resistance**: Server cannot comply with data disclosure requests because it has no data to disclose
- **Reduced attack surface**: Server compromise yields nothing useful
- **Simpler server**: Fewer tables, no auth complexity, no state machine

### Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Lost device + lost mnemonic = permanent data loss | Clear UX warning during onboarding; periodic mnemonic backup reminders; optional BIP39 passphrase |
| QR-code pairing requires physical proximity or secure channel | Support multiple sharing methods: QR, NFC, paste-from-clipboard (user's risk) |
| No server-side override state machine | Client-side state machine is deterministic with specified convergence rules; all clients converge to same state |
| No email-based account recovery | BIP39 mnemonic + optional passphrase is the recovery mechanism; this is by design |
| No server-side rate limiting per user (no user identity) | Rate limit by public key or session token instead of email |
| Server could substitute device encryption keys | Key transparency via cross-signing; key fingerprint in QR code; change detection prompts user re-verification |
| Social graph visible via bucket_access | Inherent limitation of server-relayed E2E systems; documented, not a design flaw |
| Recovery blob is high-blast-radius target | Optional BIP39 passphrase (25th word) adds second factor to mnemonic |

### What we lose

- Email/password login (familiar UX)
- Server-side TOTP/MFA
- Server-managed invite flow
- Server-side override conflict resolution
- Ability to send email notifications
- Admin visibility into family membership

### What we gain

- Honest "zero-knowledge" claim
- No PII on server whatsoever
- Dramatically reduced server complexity (fewer tables, no auth service, no state machine)
- Better alignment with stated architecture goals

## Alternatives Considered

### 1. Keep current architecture, fix audit findings only

Fix the JWT secret, add lockout, encrypt TOTP secrets, etc. Server still sees metadata but is better hardened.

**Rejected because:** Does not address the fundamental metadata leakage. The server is still not zero-knowledge despite claiming to be.

### 2. Encrypt metadata fields in OpLog, keep email/password auth

Move entityType/entityId/operation inside the encrypted payload but keep email-based auth and family management.

**Rejected because:** Half measure. Server still knows user emails, family membership, and relationships. Does not achieve zero-knowledge.

### 3. Full zero-knowledge (this decision)

Complete redesign where server sees only public keys and encrypted blobs.

**Accepted because:** Only approach that makes the "privacy-first" and "server cannot see user data" claims honest.

## References

- Security audit report (2026-02-21)
- Signal Protocol design: https://signal.org/docs/
- Matrix protocol (Megolm): https://spec.matrix.org/latest/client-server-api/#end-to-end-encryption
