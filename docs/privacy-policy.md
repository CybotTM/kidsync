# KidSync Privacy Policy

**Last Updated:** 2026-02-20

## Overview

KidSync is an open-source, self-hostable co-parenting coordination app. Privacy is a core design principle: all parenting data is end-to-end encrypted before leaving your device.

## Data We Collect

### Account Data

When you create an account, we store:

- **Email address** -- used for authentication and account recovery
- **Display name** -- shown to your co-parent
- **Password hash** -- your password is hashed with bcrypt; we never store it in plaintext

### Parenting Data (End-to-End Encrypted)

All parenting data is encrypted on your device before being sent to the server. This includes:

- Calendar events and custody schedules
- Expense records and receipt images
- Schedule override requests and approvals
- Chat messages between co-parents

The server stores this data as encrypted blobs. **The server cannot decrypt or read your parenting data.** Decryption keys are generated on your devices and never transmitted to the server in plaintext.

### Technical Data

- **Device identifiers** -- randomly generated UUIDs for sync coordination
- **Sync metadata** -- sequence numbers and hash chains for data integrity (not content)

## Data Storage

- **On your device**: All data is stored in an encrypted SQLite database (SQLCipher) with a key derived from your credentials
- **On the server**: Only encrypted blobs, account credentials (hashed), and sync metadata
- **Key management**: Data encryption keys are wrapped per-device using X25519 key agreement. A BIP39 recovery mnemonic allows account recovery if all devices are lost

## Data Sharing

We do not share your data with any third parties. The server operator (which may be you, if self-hosted) has access only to encrypted blobs and account metadata.

## Analytics and Tracking

KidSync v1.0 does not include any analytics, tracking, or telemetry.

## Data Retention

- Your data is retained as long as your account is active
- You can delete your account and all associated data at any time
- Encrypted blobs on the server are deleted when the associated account or family is deleted

## Your Rights (GDPR)

If you are in the European Economic Area, you have the right to:

- **Access** your personal data
- **Rectify** inaccurate personal data
- **Erase** your personal data ("right to be forgotten")
- **Port** your data to another service
- **Restrict** processing of your personal data
- **Object** to processing of your personal data

To exercise any of these rights, contact us at the email below.

## Self-Hosting

KidSync is open-source and can be self-hosted. When you self-host, you are the data controller and are responsible for compliance with applicable data protection laws.

## Children's Data

KidSync is designed for parents to coordinate parenting logistics. The app is intended for use by adults (parents/guardians). We do not knowingly collect personal data from children.

## Changes to This Policy

We will update this policy as needed and note the date of the last update at the top of this page.

## Contact

For privacy-related questions or to exercise your data rights, contact:

**Email:** privacy@kidsync.dev
