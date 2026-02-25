# KidSync

Privacy-first co-parenting coordination app with end-to-end encryption.

## Features

- **Shared Calendar** with custody schedule management, swap requests, and override approvals
- **Expense Tracking** with receipt capture, category-based splitting, and settlement tracking
- **End-to-End Encrypted Sync** -- the server never sees your data in plaintext
- **Local-First Architecture** -- works offline, syncs when connected
- **Self-Hostable** -- run your own server for complete data sovereignty

## Architecture

KidSync uses a local-first, append-only OpLog architecture:

- **Sync Protocol**: REST + WebSocket with per-device hash chains for integrity verification
- **Encryption**: X25519 key agreement + AES-256-GCM with per-op random nonces
- **Conflict Resolution**: CRDTs with deterministic merge semantics
- **Key Management**: Per-family data encryption keys with device-based key wrapping and BIP39 recovery mnemonics

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Android | Kotlin, Jetpack Compose, Room + SQLCipher, BouncyCastle, Hilt, WorkManager |
| Server | Kotlin, Ktor, Exposed ORM, SQLite WAL |
| Crypto | X25519, AES-256-GCM, HKDF, Ed25519 signatures, BIP39 recovery |

## Getting Started

### Server

Build and run with Docker:

```bash
cp .env.example .env
# Edit .env -- adjust settings for your environment
docker compose up -d
```

Verify the server is running:

```bash
curl http://localhost:8080/health
```

### Android

1. Open the `android/` directory in Android Studio
2. Sync Gradle dependencies
3. Update the server URL in the app configuration to point to your server
4. Build and run on a device or emulator (min SDK 26)

### Development

Run server tests:

```bash
docker run --rm -v "$(pwd)/server:/app" -w /app gradle:8.12-jdk21 gradle test --no-daemon
```

## Self-Hosting

See the [Disaster Recovery Runbook](docs/disaster-recovery.md) for backup, restore, and operational procedures.

### Requirements

- Docker and Docker Compose
- 512 MB RAM minimum
- Persistent storage for database and encrypted blobs

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines.

## Privacy

KidSync is designed with privacy as a core principle. All parenting data is end-to-end encrypted before leaving the device. The server stores only encrypted blobs and cannot decrypt user data. See the [Privacy Policy](docs/privacy-policy.md) for details.

## License

This project is licensed under the GNU Affero General Public License v3.0. See [LICENSE](LICENSE) for the full text.
