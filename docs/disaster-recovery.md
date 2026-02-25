# KidSync Disaster Recovery Runbook

Last updated: 2026-02-20

## Table of Contents

1. [Server Backup and Restore](#1-server-backup-and-restore)
2. [Server Migration](#2-server-migration)
3. [Key Recovery Procedures](#3-key-recovery-procedures)
4. [Database Corruption Recovery](#4-database-corruption-recovery)
5. [Certificate and Secret Rotation](#5-certificate-and-secret-rotation)
6. [Monitoring and Alerting](#6-monitoring-and-alerting)

---

## 1. Server Backup and Restore

The KidSync server stores data in three locations, all under the data directory:

| Component | Default Path | Env Variable | Description |
|---|---|---|---|
| SQLite database | `data/kidsync.db` | `KIDSYNC_DB_PATH` | WAL-mode database with all app state |
| Blob storage | `data/blobs/` | `KIDSYNC_BLOB_PATH` | Encrypted file attachments |
| Snapshot storage | `data/snapshots/` | `KIDSYNC_SNAPSHOT_PATH` | CRDT snapshots for sync |

### Backup Procedure

**SQLite database** -- Use the `.backup` command, never copy the file directly. A raw file copy while the server is running can produce a corrupt backup because SQLite WAL mode uses `kidsync.db-wal` and `kidsync.db-shm` sidecar files.

```bash
# Online backup using sqlite3 .backup command
# This is safe to run while the server is active.
sqlite3 /app/data/kidsync.db ".backup '/backup/kidsync-$(date +%Y%m%d-%H%M%S).db'"
```

**Blob and snapshot directories** -- These are immutable once written (blobs are never modified, only soft-deleted in the database). A standard filesystem copy or rsync is safe.

```bash
rsync -a /app/data/blobs/ /backup/blobs/
rsync -a /app/data/snapshots/ /backup/snapshots/
```

**Full backup script:**

```bash
#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR="/backup/kidsync/$(date +%Y%m%d-%H%M%S)"
DB_PATH="${KIDSYNC_DB_PATH:-/app/data/kidsync.db}"
BLOB_PATH="${KIDSYNC_BLOB_PATH:-/app/data/blobs}"
SNAPSHOT_PATH="${KIDSYNC_SNAPSHOT_PATH:-/app/data/snapshots}"

mkdir -p "$BACKUP_DIR"

# 1. SQLite online backup
sqlite3 "$DB_PATH" ".backup '${BACKUP_DIR}/kidsync.db'"

# 2. Copy blobs and snapshots
rsync -a "$BLOB_PATH/" "${BACKUP_DIR}/blobs/"
rsync -a "$SNAPSHOT_PATH/" "${BACKUP_DIR}/snapshots/"

# 3. Record backup metadata
echo "backup_time=$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "${BACKUP_DIR}/metadata.txt"
echo "db_size=$(stat -c%s "${BACKUP_DIR}/kidsync.db")" >> "${BACKUP_DIR}/metadata.txt"
echo "blob_count=$(find "${BACKUP_DIR}/blobs" -type f | wc -l)" >> "${BACKUP_DIR}/metadata.txt"
echo "snapshot_count=$(find "${BACKUP_DIR}/snapshots" -type f | wc -l)" >> "${BACKUP_DIR}/metadata.txt"

# 4. Verify the backup database
INTEGRITY=$(sqlite3 "${BACKUP_DIR}/kidsync.db" "PRAGMA integrity_check;")
if [ "$INTEGRITY" != "ok" ]; then
    echo "ERROR: Backup integrity check failed: $INTEGRITY" >&2
    exit 1
fi

echo "Backup complete: $BACKUP_DIR"
```

### Recommended Cron Schedule

```cron
# Full backup every 6 hours
0 */6 * * * /opt/kidsync/backup.sh >> /var/log/kidsync-backup.log 2>&1

# Retain 7 days of backups, prune older ones daily at 03:00
0 3 * * * find /backup/kidsync -maxdepth 1 -type d -mtime +7 -exec rm -rf {} +
```

For production deployments, additionally replicate backups to off-host storage (S3, GCS, or a remote server) after each local backup completes.

### Restore Procedure

```bash
# 1. Stop the server
docker stop kidsync-server

# 2. Move current data aside
mv /app/data /app/data.damaged

# 3. Restore from backup
mkdir -p /app/data
cp /backup/kidsync/20260220-120000/kidsync.db /app/data/kidsync.db
cp -r /backup/kidsync/20260220-120000/blobs/ /app/data/blobs/
cp -r /backup/kidsync/20260220-120000/snapshots/ /app/data/snapshots/

# 4. Fix ownership (container runs as kidsync user)
chown -R 1000:1000 /app/data

# 5. Verify before starting
sqlite3 /app/data/kidsync.db "PRAGMA integrity_check;"
# Expected output: ok

# 6. Restart
docker start kidsync-server

# 7. Verify health
curl -f http://localhost:8080/health
# Expected: {"status":"ok","db":true}
```

---

## 2. Server Migration

Moving the KidSync server to a new host.

### Prerequisites

- New host has Docker installed with Eclipse Temurin JRE 21 Alpine base image available
- Network connectivity to pull the KidSync image or build from `server/Dockerfile`
- DNS or load balancer can be updated to point to the new host

### Migration Steps

```bash
# === On the OLD host ===

# 1. Create a fresh backup (ensures consistency)
sqlite3 /app/data/kidsync.db ".backup '/tmp/kidsync-migration.db'"
tar czf /tmp/kidsync-data.tar.gz -C /app data/

# 2. Copy to new host
scp /tmp/kidsync-data.tar.gz newhost:/tmp/

# === On the NEW host ===

# 3. Extract data
mkdir -p /app
tar xzf /tmp/kidsync-data.tar.gz -C /app

# 4. Verify database integrity
sqlite3 /app/data/kidsync.db "PRAGMA integrity_check;"

# 5. Verify blob file counts match
sqlite3 /app/data/kidsync.db "SELECT COUNT(*) FROM blobs WHERE deleted_at IS NULL;"
find /app/data/blobs -type f | wc -l

# 6. Build or pull the server image
docker build -t kidsync-server:latest -f server/Dockerfile server/

# 7. Run with the same environment variables (see .env.example for full list)
docker run -d \
    --name kidsync-server \
    -p 8080:8080 \
    -v /app/data:/app/data \
    -e KIDSYNC_SERVER_ORIGIN="https://api.kidsync.app" \
    kidsync-server:latest

# 8. Verify health
curl -f http://localhost:8080/health
# Expected: {"status":"ok","db":true}

# 9. Test a sync pull to verify data access
curl -s http://localhost:8080/health | jq .
```

**Note:** Session tokens are stored in the database and migrate with it. Active sessions remain valid on the new host as long as the database file is intact. If the database is lost, all devices must re-authenticate via Ed25519 challenge-response (no passwords involved).

### DNS Cutover

1. Verify the new server returns healthy responses
2. Update DNS or load balancer to point to the new host
3. Monitor error rates for 60 minutes (session token TTL)
4. Decommission the old host only after confirming zero traffic

---

## 3. Key Recovery Procedures

KidSync uses client-side encryption with a per-family Data Encryption Key (DEK) stored locally on each device. The server never sees plaintext data. If a user loses all their devices, the recovery key is the only way to regain access to encrypted data.

### How Recovery Keys Work

1. During setup, the app generates 256 bits of entropy encoded as a **24-word BIP39 mnemonic**
2. A recovery key is derived via `HKDF-SHA256(IKM=entropy, salt="kidsync-recovery-v1", info=userId, L=32)`
3. The current DEK is wrapped (AES-256-GCM, AAD="recovery-wrap") with this recovery key
4. The wrapped DEK blob is uploaded to the server (`POST /keys/recovery`)
5. The user must store the 24 words offline (paper, password manager)

### User Loses Device -- Recovery Flow

**Precondition:** The user has their 24-word recovery mnemonic.

1. User installs KidSync on a new device
2. The app generates new Ed25519/X25519 keypairs and registers with the server
3. User authenticates via Ed25519 challenge-response
4. User navigates to recovery restore and enters the 24 words (+ optional passphrase)
5. The app calls `GET /recovery` to download the encrypted recovery blob
6. The app derives the recovery key from the mnemonic via HKDF
7. The app decrypts the recovery blob, extracts seed, bucket IDs, and DEKs
8. The app re-wraps DEKs for the new device's keys and resumes sync

**If the user does NOT have the recovery mnemonic:**

There is no server-side backdoor. The encrypted op-log data on the server is unrecoverable without the DEK. The user must:

1. Log in on the new device (account access is password-based, independent of encryption)
2. Have a co-parent who still has an active device re-invite them to the family
3. The co-parent's device performs key rotation (`rotateKey`), generating a new DEK
4. The new DEK is wrapped with the new device's public key and uploaded
5. Historical encrypted data from before the rotation remains unreadable to the recovered user. Only new operations from the current epoch forward are accessible.

### Key Rotation After Device Compromise

If a device is suspected compromised:

1. Revoke the device via `DELETE /devices/{deviceId}` (sets `revoked_at`)
2. Trigger key rotation from another active device in the family
3. The new epoch's DEK is wrapped for all remaining active devices, excluding the revoked one
4. The revoked device can no longer authenticate (session validation checks device revocation status)

---

## 4. Database Corruption Recovery

### Detecting Corruption

```bash
# Run SQLite integrity check
sqlite3 /app/data/kidsync.db "PRAGMA integrity_check;"
```

Expected output for a healthy database: `ok`

Any other output indicates corruption. Example failure output:

```
*** in database main ***
Page 42: btreeInitPage() returns error code 11
```

### Corruption Causes

- Storage hardware failure or filesystem errors
- Incomplete write during power loss (mitigated by WAL mode, but not eliminated)
- Running out of disk space during a write transaction
- Copying `kidsync.db` directly while the server is running (WAL files not included)

### Recovery from Backup

This is the preferred and most reliable approach.

```bash
# 1. Stop the server
docker stop kidsync-server

# 2. Identify the most recent clean backup
ls -lt /backup/kidsync/ | head -5

# 3. Verify the backup is clean
sqlite3 /backup/kidsync/LATEST/kidsync.db "PRAGMA integrity_check;"

# 4. Replace the corrupt database
mv /app/data/kidsync.db /app/data/kidsync.db.corrupt
mv /app/data/kidsync.db-wal /app/data/kidsync.db-wal.corrupt 2>/dev/null || true
mv /app/data/kidsync.db-shm /app/data/kidsync.db-shm.corrupt 2>/dev/null || true
cp /backup/kidsync/LATEST/kidsync.db /app/data/kidsync.db
chown 1000:1000 /app/data/kidsync.db

# 5. Restart and verify
docker start kidsync-server
curl -f http://localhost:8080/health
```

**Data loss window:** Any operations between the last backup and the corruption event are lost. Clients will re-upload their local op-log entries on next sync (the hash chain will detect the gap and the client can replay from its local sequence).

### Recovery Without Backup (Last Resort)

If no clean backup exists, attempt to salvage data from the corrupt database:

```bash
# 1. Dump what SQLite can read
sqlite3 /app/data/kidsync.db.corrupt ".dump" > /tmp/salvaged.sql

# 2. Rebuild into a new database
sqlite3 /app/data/kidsync-rebuilt.db < /tmp/salvaged.sql

# 3. Check the rebuilt database
sqlite3 /app/data/kidsync-rebuilt.db "PRAGMA integrity_check;"

# 4. Verify table row counts look reasonable
sqlite3 /app/data/kidsync-rebuilt.db "
    SELECT 'users', COUNT(*) FROM users
    UNION ALL SELECT 'families', COUNT(*) FROM families
    UNION ALL SELECT 'op_log', COUNT(*) FROM op_log
    UNION ALL SELECT 'blobs', COUNT(*) FROM blobs;
"
```

This may recover partial data. Tables or rows in corrupted pages will be missing.

### Verifying Blob Consistency After Recovery

After any database restore, verify that blob references in the database match files on disk:

```bash
# Find blobs referenced in the database but missing on disk
sqlite3 /app/data/kidsync.db "SELECT id, file_path FROM blobs WHERE deleted_at IS NULL;" | \
while IFS='|' read -r id path; do
    if [ ! -f "$path" ]; then
        echo "MISSING: blob $id -> $path"
    fi
done
```

---

## 5. Certificate and Secret Rotation

### Session Invalidation

The server uses opaque session tokens (`sess_` prefix) with a configurable TTL (default: 1 hour via `KIDSYNC_SESSION_TTL_SECONDS`). Sessions are stored in the database and validated on each request.

**To force all devices to re-authenticate** (e.g., after suspected compromise):

```bash
# Delete all active sessions
sqlite3 /app/data/kidsync.db "DELETE FROM Sessions;"
```

All devices will receive 401 responses and must re-authenticate via Ed25519 challenge-response. No passwords or secrets are involved -- devices authenticate using their Ed25519 signing keypair.

**To invalidate sessions for a specific device:**

```bash
sqlite3 /app/data/kidsync.db "
    DELETE FROM Sessions
    WHERE device_id = '<device-uuid>';
"
```

### Signing Key Compromise

If a device's Ed25519 signing key is suspected compromised:

1. Revoke the device via `DELETE /devices/{deviceId}`
2. Delete any active sessions for that device
3. Trigger DEK rotation from another active device in the bucket
4. The compromised key can no longer be used to authenticate or sign attestations

---

## 6. Monitoring and Alerting

### Health Endpoint

The `/health` endpoint is unauthenticated and checks database connectivity:

```bash
curl -s http://localhost:8080/health | jq .
```

**Healthy response** (HTTP 200):
```json
{"status": "ok", "db": true}
```

**Degraded response** (HTTP 503):
```json
{"status": "degraded", "db": false}
```

The health check executes a simple database query via `dbQuery { true }`. A failure indicates the SQLite connection is broken (file locked, corrupt, or disk full).

### Recommended Monitoring Checks

**Uptime / health polling:**

```bash
# Simple cron-based check (every minute)
* * * * * curl -sf -o /dev/null -w '%{http_code}' http://localhost:8080/health | grep -q 200 || echo "KidSync health check failed" | mail -s "ALERT: KidSync down" ops@example.com
```

For production, use a proper monitoring tool (Prometheus blackbox exporter, UptimeRobot, Healthchecks.io) to poll `/health` every 30-60 seconds.

**Disk usage:**

```bash
#!/usr/bin/env bash
# Alert if data partition usage exceeds 80%
USAGE=$(df /app/data --output=pcent | tail -1 | tr -d ' %')
if [ "$USAGE" -gt 80 ]; then
    echo "ALERT: /app/data disk usage at ${USAGE}%"
fi

# Report data directory sizes
echo "Database: $(du -sh /app/data/kidsync.db | cut -f1)"
echo "Blobs: $(du -sh /app/data/blobs/ | cut -f1)"
echo "Snapshots: $(du -sh /app/data/snapshots/ | cut -f1)"
echo "WAL file: $(du -sh /app/data/kidsync.db-wal 2>/dev/null | cut -f1 || echo 'none')"
```

SQLite WAL files can grow unbounded under sustained write load. Monitor `kidsync.db-wal` size. The server sets `checkpoint_interval=100` operations, after which a checkpoint should occur, but manual checkpointing can be triggered:

```bash
sqlite3 /app/data/kidsync.db "PRAGMA wal_checkpoint(TRUNCATE);"
```

**Backup verification:**

```bash
#!/usr/bin/env bash
# Run daily to verify backup recency and integrity
LATEST_BACKUP=$(ls -td /backup/kidsync/*/ 2>/dev/null | head -1)

if [ -z "$LATEST_BACKUP" ]; then
    echo "ALERT: No backups found"
    exit 1
fi

# Check backup age (alert if older than 12 hours)
BACKUP_AGE=$(( $(date +%s) - $(stat -c %Y "$LATEST_BACKUP") ))
if [ "$BACKUP_AGE" -gt 43200 ]; then
    echo "ALERT: Latest backup is $(( BACKUP_AGE / 3600 )) hours old"
fi

# Verify backup database integrity
RESULT=$(sqlite3 "${LATEST_BACKUP}/kidsync.db" "PRAGMA integrity_check;" 2>&1)
if [ "$RESULT" != "ok" ]; then
    echo "ALERT: Backup integrity check failed: $RESULT"
fi

# Verify backup has data (not an empty database)
ROW_COUNT=$(sqlite3 "${LATEST_BACKUP}/kidsync.db" "SELECT COUNT(*) FROM users;")
if [ "$ROW_COUNT" -eq 0 ]; then
    echo "WARNING: Backup database has zero users"
fi
```

### Key Metrics to Track

| Metric | Source | Alert Threshold |
|---|---|---|
| Health endpoint status | `GET /health` | Any non-200 response |
| Database file size | `stat kidsync.db` | Sudden size drops (truncation) |
| WAL file size | `stat kidsync.db-wal` | Greater than 100 MB |
| Blob directory size | `du -s data/blobs/` | Approaching disk capacity |
| Backup age | Backup metadata | Older than 12 hours |
| Backup integrity | `PRAGMA integrity_check` | Any non-"ok" result |
| Disk usage percent | `df` | Greater than 80% |
| Container restart count | Docker | Any unexpected restart |

### Log Monitoring

The Ktor server logs to stdout via SLF4J at INFO level. In Docker:

```bash
# Follow live logs
docker logs -f kidsync-server

# Search for errors
docker logs kidsync-server 2>&1 | grep -i error
```

Key log patterns to alert on:

- `HASH_CHAIN_BREAK` -- Indicates sync integrity issues; a client's local chain diverged from the server
- `DEVICE_REVOKED` -- A revoked device attempted to sync; may indicate a compromise
- `SQLite` + `error` -- Database-level failures
- `OutOfMemoryError` -- JVM memory exhaustion
