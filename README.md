# Vault — fault-tolerant distributed object storage

Vault stores each object on several independent storage nodes, verifies every copy with SHA-256, and keeps
itself healthy in the background: it detects failed nodes, repairs missing/corrupt/outdated replicas,
and rebalances data — while serving concurrent reads and writes.

```
Client ──REST──▶ vault-api (Spring Boot controller)  ──gRPC──▶ storage-node ×N (disk)
                    │  metadata: PostgreSQL (H2 in dev/tests)
                    └─ background: health monitor · repair queue+workers · integrity scrubber · rebalancer
```

## Frontend

React + Vite app in [`frontend/`](frontend/README.md): `cd frontend && npm install && npm run dev` (needs the backend on :8080).

## Modules

| Module | What it is |
|---|---|
| `vault-proto` | gRPC/Protobuf contract (`StorageNodeService`: Put/Get streaming, Delete, Verify, CopyReplica, CheckHealth) |
| `storage-node` | Standalone storage process: atomic disk writes, SHA-256 on write, capacity limits. No policy. |
| `vault-api` | The controller: REST API, metadata (JPA + Flyway), replication, placement, health, repair, integrity, rebalance, metrics |

## Run it

Requirements: JDK 21+ and Maven 3.9+ (the build targets Java 21 bytecode; tested on JDK 26).

```bash
mvn install            # builds everything and runs all tests (~1 min)
```

**Without Docker** (Windows/PowerShell; in-memory H2 metadata, 4 nodes on :9091-9094, API on :8080):

```powershell
.\scripts\run-local.ps1                # starts everything
.\scripts\stop-local.ps1 -Node 2       # crash node-2 and watch Vault detect + repair
```

**With Docker** (PostgreSQL, Kafka, Redis, 4 nodes, API, Prometheus :9090, Grafana :3000; the `docker` profile switches Kafka/Redis/rate-limiting on): `docker compose up --build`, then `docker compose stop storage-node-2`.
*(The compose/Docker files are written but have not been run in this environment — Docker was not installed.)*

### Access model

**Accounts.** Everyone signs in with **Google**. The frontend gets a Google ID token, the backend verifies it (audience = your client ID, issuer, verified email), creates the user on first sign-in and issues its own signed session token (12 h). Every object records its owner; users only ever see and change **their own** files — someone else's file id looks like a 404, not a 403.

**Admin.** Admin access needs *both*: (1) a Google account whose email is on the allowlist `VAULT_ADMIN_EMAILS` (comma-separated) and (2) the admin password `VAULT_ADMIN_PASSWORD` (second step at `/admin/login`, 8 h admin session, attempts throttled). Anyone not on the allowlist never sees an Admin link, is redirected away from `/admin`, and gets 404 from the admin-login endpoint. Admins can inspect all objects (owner, node placement), download and delete, but never upload or replace another user's content. The backend enforces all of it: `/api/v1/{admin,nodes,stats,health}` need `Authorization: Bearer <user token>` **and** `X-Admin-Token`.

**Setting up Google sign-in** (required outside dev mode):
1. Google Cloud Console → APIs & Services → Credentials → *Create credentials → OAuth client ID* → type **Web application**.
2. Add *Authorized JavaScript origins*: `http://localhost:5173` (and your production URL). No redirect URI is needed (Google Identity Services popup flow).
3. Start the API with `VAULT_GOOGLE_CLIENT_ID=<client id>.apps.googleusercontent.com`, `VAULT_ADMIN_EMAILS=you@gmail.com`, `VAULT_ADMIN_PASSWORD=<strong password>` and (for several instances) `VAULT_TOKEN_SECRET`. If the password is unset a random one is printed in the API startup log.

**Where settings live:** copy `.env.example` to `.env` (git-ignored) and fill it in. `scripts\run-local.ps1` and `docker compose` both read it; values already set in your shell win over the file. Restart the backend after editing.

**Local demo without Google:** the `dev` profile (used by `run-local.ps1`) enables *dev sign-in* — type any email, no verification — with admin email `admin@vault.local` and admin password `admin`. **Never use the dev profile in production.**

*Not yet verified against real Google:* the Google verification code path is covered by tests using a stand-in verifier; it has not been run against Google's servers because no OAuth client ID exists yet.

### API

```text
GET    /api/v1/auth/config                 public: {googleClientId, devLogin}
POST   /api/v1/auth/google                 {credential} → {token, user}
POST   /api/v1/auth/admin-login            {password} (allowlisted accounts only) → {token}
GET    /api/v1/auth/me                     {email, name, picture, canAdmin, admin}
GET    /api/v1/objects[?q=&type=&modifiedAfter=&modifiedBefore=&projectId=&sort=&dir=&trashed=&page=&size=&scope=all]
                                            your files. type = pdf|document|spreadsheet|presentation|image|video|audio|archive|other;
                                            sort = name|size|modified|created; trashed=true lists the trash; scope=all is admin-only
GET    /api/v1/objects/usage               storage used (trash included) vs your quota, bytes per type
DELETE /api/v1/objects/{id}                move to trash (restorable 30 days); ?permanent=true deletes forever (admins always permanent)
POST   /api/v1/objects/{id}/restore        take a file out of the trash
POST   /api/v1/objects/trash/empty         permanently delete everything in your trash
PUT    /api/v1/objects/{id}/project        {projectId|null} move a file into / out of a project
GET|POST /api/v1/projects, PATCH|DELETE /api/v1/projects/{id}   your projects (deleting one ungroups its files)
POST   /api/v1/objects                     multipart "file" [+ ?replicationFactor=N] [Idempotency-Key header] → 201
PUT    /api/v1/objects/{id}?expectedVersion=N   (or If-Match: N)  new version; 409 if N is stale
GET    /api/v1/objects/{id}                download, checksum-verified (ETag = SHA-256, X-Vault-Version)
DELETE /api/v1/objects/{id}                204 (tombstoned)
GET    /api/v1/objects/{id}/metadata       replicas, versions, per-replica + node status
GET    /api/v1/nodes                       node status / capacity
GET    /api/v1/health                      cluster summary: UP | DEGRADED | DOWN, objects needing repair
POST   /api/v1/admin/{heartbeat|repair/scan|integrity/verify|rebalance/run}   manual triggers (admin only)
GET    /actuator/prometheus                metrics (vault_upload_*, vault_repair_*, vault_corruption_total, vault_nodes{status}, ...)
```

```bash
curl -F file=@photo.jpg http://localhost:8080/api/v1/objects
curl -o out.jpg http://localhost:8080/api/v1/objects/obj-…
```

Errors are always `{"error": CODE, "message": …, "objectId": …}`: 400 `INVALID_REQUEST`, 404 `OBJECT_NOT_FOUND`,
409 `VERSION_CONFLICT`, 503 `INSUFFICIENT_REPLICAS`.

## How it meets the requirements

| Requirement | Mechanism |
|---|---|
| Configurable replication / durability | `vault.replication.factor` (per-object override); write quorum W enforced on every write, reads can enforce R (`vault.quorum.*`) |
| Node failure | Heartbeats → `HEALTHY → SUSPECTED → UNHEALTHY` (`failure-threshold` consecutive misses). Only UNHEALTHY nodes lose their replicas' durability credit. Returning nodes go through `RECOVERING` first |
| Automatic repair | `NodeFailed`/corruption events + periodic scan → de-duplicated queue → bounded worker pool (`max-concurrent-tasks`) so repair cannot starve client I/O |
| Data corruption | SHA-256 verified on read (bad replica skipped, flagged, re-copied), plus a background scrubber that has nodes re-hash from disk |
| Replica inconsistency | Replicas that miss an update become `OUTDATED` and are refreshed from a current replica |
| Partial network partition | Suspected ≠ dead; a single failed request only *suspects*. **Partition guard** pauses repair/rebalance when >50% of nodes are unreachable. Rows on unreachable nodes are never destroyed; surplus copies are trimmed when the node returns |
| Concurrent reads/writes | Reads take no locks (virtual threads). Writes use optimistic concurrency (compare-and-set on `version`) → exactly one of N concurrent updaters wins, the rest get 409 |
| Metadata consistency | Single transactional PostgreSQL source of truth; every multi-row change is one transaction; deletes leave tombstones |
| Rebalancing | Moves replicas off nodes above `threshold-percent`: copy → target verifies SHA-256 → metadata commit → only then delete the old copy |
| Safe copying | Always copy → verify → update metadata → delete. Nodes publish files by atomic rename after the checksum matches |
| Storage overhead | Replication factor is per object (e.g. RF=2 for cold data, RF=1 for scratch) |
| Observability | Micrometer/Prometheus metrics, `/api/v1/health` |

Measured on the automated end-to-end test (1 s heartbeat, threshold 2): node killed → detected in ~1.8 s →
replication factor restored ~0.1 s later.

## Tests (73)

`mvn test` boots the real Spring app against real in-process storage nodes (gRPC over localhost, real files on disk):
lifecycle & REST, node crash, multi-node failure (incl. honest `INSUFFICIENT_REPLICAS`), corruption & missing files,
partition guard, stale-replica repair, node-return trimming, 100 concurrent reads, 50 concurrent updates
(exactly one winner), 50 concurrent uploads, rebalancing, and an automatic-recovery test where no repair is
triggered by hand.

## Optional infrastructure (config flags, all off by default)

- **Kafka** (`vault.kafka.enabled`): failure/corruption events go to `vault.node-events` / `vault.repair-events`; controllers share one consumer group so each event is handled once. Falls back to the in-process bus if Kafka is unreachable. Tested with an in-process KRaft broker.
- **Redis** (`vault.redis.enabled`): cluster-wide per-object locks (safe to run several controllers) and shared rate-limit counters. Lock/limit logic is tested against an in-memory store; the Redis implementation itself is untested without a server.
- **API key** (`vault.security.api-key` / `VAULT_API_KEY`): requires `X-API-Key` on `/api/**`. **Rate limit** (`vault.rate-limit.*`): 429 + `Retry-After`.

## Not built yet / known limitations

Deferred: **Kubernetes**, **Raft** metadata replication, Grafana dashboards (Grafana runs, no dashboard provisioned). Also:

- Metadata is a single PostgreSQL (no HA). A crash between writing node files and committing metadata can leave
  orphan files; there is no orphan sweeper yet.
- Rows for a permanently dead node are retained (never auto-decommissioned).
- No authentication/TLS (plain gRPC, open admin endpoints) — prototype only.
- Uploads are spooled to a temp file on the controller (needed to hash once and stream to N nodes).
- Docker/compose files are untested here.
