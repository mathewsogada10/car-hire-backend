# Car Hire backend

Private, single-owner Spring Boot application for a Kenyan car-hire business. This repository is a local implementation, not a published service. The companion repositories are `..\car-hire-web` and `..\car-hire-android`.

## Functionality

| Area | Implemented behavior |
| --- | --- |
| Fleet and customers | UUID-based vehicle/customer CRUD, normalized unique registration, operational status, contacts, odometer corrections, history through immutable audit hashes, referenced-record deletion protection. |
| Hires and availability | Draft / Booked / Active / Completed / Cancelled transitions and exact status filtering; one hire register ordered by planned pickup before pagination (UUID breaks ties); vehicle/customer references; planned and actual instants; zero-charge confirmation; half-open overlap validation; maintenance windows; configurable turnaround buffer. Overdue active hires continue blocking. |
| Payments | Pending / Received / Failed entries; payments, refundable security deposits, deposit applications, ordinary refunds and deposit refunds. Refunds reference originals and cannot exceed them; deposits cannot be applied/refunded twice. Received entries are immutable: use compensating entries. |
| Expenses | Vehicle, category, provider, business date, amount, planned/due/paid/cancelled state, paid date, notes and private receipt attachments. Categories/providers are owner-managed. Maintenance links an expense rather than counting costs twice. |
| Operations | Maintenance windows and next-due details; date/mileage reminder records with open/snoozed/completed/dismissed history; odometer/fuel readings; basic pre/post condition notes; chargeable fines/damages. |
| Reports | Recognized hire charges, collections net of refunds, accrued/paid expenses, expected/cash profit, outstanding balances and separately disclosed deposit cash. Date/vehicle filtering and balance CSV download. |
| Sync | At most 100 operations per push; durable operation IDs and content hashes; replayed acknowledgements; explicit revision conflicts with both versions; cursor pull including tombstones; independently checkpointed private attachments. |
| Files | Private PDF/PNG/JPEG files, content-signature checks, 20 MiB/file and 20 files/parent, SHA-256 verification, authenticated downloads, stable replay-safe attachment UUIDs. |
| Portable data | Full-snapshot ZIP exports, manifest, CSV, JSONL, SHA-256 checksums, reopen verification, export history; bounded ZIP validation and atomic UUID-based import, conflict preview, counts and financial reconciliation, repeat-import acknowledgement. |
| Purge | Verified safe/ingested export required; explicit date scope, reauthentication, fresh preview hash and typed confirmation; retain masters/settings/evidence/current reminders/referenced records/unsettled deposits; erase eligible payloads and keep minimal sync tombstones. Only matching exported attachments are eligible, with a durable post-commit file-cleanup queue. |
| Security | BCrypt owner credential, HttpOnly/SameSite session cookies, CSRF, HTTPS-only Android transport, sign-out, login/reauthentication throttles, private files and append-only database audit triggers. No hardcoded production credentials. |

## Portable Docker deployment

`deployment` contains the complete web/backend/PostgreSQL stack, offline image bundle builder, first-start credential setup and data-preserving stop script. See [deployment/README.md](deployment/README.md). The root `compose.yaml` below remains database-only for development. Application containers run without root, only the web port is published, and attachments/database use persistent named volumes. Images contain no business data or owner credentials.

Build the portable ZIP from this repository with `powershell -NoProfile -File .\deployment\Build-PortableBundle.ps1`. On another compatible laptop, extract it and run `powershell -NoProfile -File .\Start-CarHire.ps1` inside the extracted folder. Docker with Linux containers is required; Java, Maven, Node and source repositories are not needed there. Default builds target Linux AMD64; the builder also accepts `-Platform linux/arm64` for a separately built ARM64 bundle.

## Run on Windows

Requirements: Java 21, Docker with Linux containers, network access for the first Maven wrapper run. Set secrets in the process environment or your deployment secret manager; do not commit them.

```powershell
$env:DATABASE_PASSWORD = '<choose-a-database-password>'
$env:OWNER_PASSWORD = '<choose-an-owner-password-of-at-least-12-characters>'
docker compose up -d --wait
# Local loopback development only; production cookies must remain secure.
$env:COOKIE_SECURE = 'false'
$env:SERVER_ADDRESS = '127.0.0.1'
.\mvnw.cmd spring-boot:run
```

Defaults: port 8080, database `jdbc:postgresql://localhost:5432/carhire`, database user `carhire`, owner username `owner`, private file root `storage`. Overrides: `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `OWNER_USERNAME`, `OWNER_PASSWORD`, `ATTACHMENT_STORAGE`, `COOKIE_SECURE`. Keep PostgreSQL and attachment storage backed up together. Container data uses the named `carhire-db` volume; do not remove it when updating.

Production requires TLS termination, a same-origin reverse proxy for the web app, secure cookies, private storage permissions and an operator-managed encrypted PostgreSQL/files backup. Do not expose port 8080 directly to the Internet. Owner credential changes require restarting the service and therefore invalidate its in-memory sessions. Stop a running packaged JAR before rebuilding that same file on Windows.

## API and contract

Authenticated OpenAPI is available at `/v3/api-docs`; Swagger UI is at `/swagger-ui/index.html`. Health is public at `/actuator/health` without internal detail. Web uses same-origin cookies; Android uses an HTTPS Basic credential encrypted by its Keystore plus CSRF for mutations.

Core API families under `/api/v1`:

```text
auth/csrf, auth/login, auth/logout, auth/me, auth/reauth
records/{kind}, records/{kind}/{uuid}, changes
reports, reports/balances.csv, audit, data-health
attachments, attachments/{uuid}, attachments/changes, attachments/cleanup
sync/push, sync/pull
exports, exports/{uuid}/acknowledge, imports
purge/preview, purge/commit
```

`kind` is one of `vehicles`, `customers`, `hires`, `payments`, `expenses`, `categories`, `providers`, `maintenance`, `reminders`, `readings`, `conditions`, `damages`, `settings`. List pages are bounded to 200 rows and support search/status/vehicle/business-date filters. The web form schemas describe the supported payload fields. Writes use `{operationId,id,kind,baseRevision,data,deleted,reason}`. IDs are UUIDs; new rows use revision 0; never reuse an operation ID for changed content. Financial conflicts are not silently merged. Rejected sync operations require explicit correction and a new operation ID.

The settings singleton UUID is `00000000-0000-4000-8000-000000000001`. It stores KES, Africa/Nairobi, `bufferMinutes`, `remindersEnabled`, `reminderLeadDays`, `reminderTime` and `backupReminderDays`. Enabled reminders require valid configuration. Notification delivery is performed by the clients, not a server push service.

The internal persistence model uses versioned, centrally validated JSONB records with relational indexes, protected financial workflows and a globally serialized writer. This deliberately favors correctness for one owner over concurrent-write throughput. Flyway migrations are forward-only; never edit an applied migration.

## Code organization (MVC without views)

`ke.carhire.Application` remains the application entrypoint. The REST backend has no server-rendered view layer:

| Package | Responsibility |
| --- | --- |
| `controllers` | Thin HTTP adapters, multipart/download responses, session attributes and centralized REST error advice. |
| `models` | Shared record envelopes and request/result DTOs, including changes, sync batches, attachments, portable packages and purge confirmation. |
| `services` | Business validation, record writes, sync orchestration, reports/CSV, authentication, audit, data health, export/import verification and guarded purge. |
| `repositories` | Encapsulated JDBC persistence for records, operation acknowledgements, attachments/cleanup, data-transfer evidence and audit events. |
| `config` | Spring Security configuration and login throttling. |
| `exceptions` | Structured application exceptions. |
| `utils` | JSON serialization, conversion and canonical SHA-256 helpers. |

Requests flow from controller to service to repository. Controllers never execute SQL or depend directly on repositories; services do not expose JDBC or servlet objects. Dependencies are constructor-injected and private. `DomainPolicy` centralizes business invariants, while `PortablePackageVerifier` handles bounded ZIP/manifest/reconciliation validation separately from transfer orchestration. The existing JSONB storage, database migrations, routes and wire formats are unchanged.

Sync intentionally invokes the transactional `RecordService` bean for each operation rather than wrapping an entire batch or self-invoking writes. Accepted changes therefore survive independently rejected operations. Export acknowledgement and its audit evidence are committed atomically. Integration tests guard these boundaries and exercise the HTTP adapters in addition to business workflows.

## Financial/date policy

All money is an integer KES minor-unit amount (KES 40,000 = 4,000,000). UTC instants are used for hire windows; date-only fields are ISO dates. Business/report dates use Africa/Nairobi. Recognition defaults to a completed hire's actual return date. Collections exclude refundable security deposits; their cash movement is disclosed separately. Expected profit is recognized charges minus expenses on expense date; cash profit is net collections minus expenses on paid date. Outstanding balances include customer-chargeable damages and deposit applications. Balance report scope selects hires by planned pickup date and then shows their current allocations. Default turnaround buffer is zero minutes.

## Export, import and purge details

Portable schema `1.0.0` includes all named business CSV files, `condition_checks.jsonl`, extra master/configuration files, attachments when explicitly selected, `manifest.json`, `checksums.sha256` and `README.txt`. CSV is UTF-8 RFC4180 with LF, `\N` nulls and snake_case columns. `data/records.jsonl` is this implementation's **required lossless typed envelope extension**; import is compatible with the companion Android exporter, not arbitrary third-party CSV ZIPs. Every file hash, count and CSV/envelope agreement is checked. ZIP uploads are limited to 20 MiB compressed / 100 MiB expanded, 10,000 entries and 50,000 records, with traversal/duplicate-path and expansion controls. Export also fails rather than producing a package its importer cannot verify.

Import never overwrites a different existing UUID silently: it reports a conflict. Resolve existing records explicitly, then export/validate again. Identical packages are idempotent. Inspect the returned source and destination reconciliation before considering an import accepted. Export with attachments before purging attachment-bearing records. External exported copies cannot be deleted or revoked by this service.

Safe acknowledgement requires recent reauthentication and the exact phrase `I VERIFIED AND SAFELY STORED THIS EXPORT`. Purge requires `PURGE <preview-count>`. Preview is re-evaluated in the transaction. Minimal UUID tombstones remain to prevent resurrection; replay acknowledgement payloads are scrubbed for erased records. Hash-only evidence remains. File cleanup can be retried through `/attachments/cleanup`; failures remain visible in `/data-health`.

## Validation

```powershell
.\mvnw.cmd spotless:check verify
```

Tests use disposable PostgreSQL Testcontainers, never the configured business database. They cover financial examples, adjacent/overlapping and concurrent bookings, deposits/refunds, revision replay/conflicts, tombstones, corrupt/traversal ZIPs, import replay, purge guards, attachment identity/integrity and immutable audit. A synthetic ZIP produced on Android is retained as a cross-module import/replay fixture; checksummed but false financial totals are rejected. Build output is `target\car-hire-backend-0.1.0.jar`.

## Remaining specification/release work

This is **not a declaration that all AC-001..AC-030 release criteria are met**. Advanced recurring expenses/reminders, full condition-item checklists, utilization/ageing analytics, PDF statements, communications templates, per-device credential/token rotation and remote device revocation, arbitrary spreadsheet/MySQL adapters, a web conflict-resolution workspace, production backup monitoring and measured 50,000-row/launch performance remain unfinished or unverified. Multi-user/multi-tenant operation is not implemented. Existing-UUID import updates deliberately stop for conflict review rather than attempting an unproven merge. No business data or credentials have been published.
