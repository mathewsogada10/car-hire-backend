# Portable Car Hire deployment

This bundle runs the React web app, Spring Boot backend and PostgreSQL 17 together. It starts an empty installation; it does not contain existing business records, uploaded files or passwords. Android is installed separately using its APK.

## Another Windows laptop

Requirements: Docker Desktop running **Linux containers**, Compose 2.20+, and PowerShell 5.1+. The standard bundle targets **Linux AMD64/x64**. Use an ARM64 build for an ARM laptop; do not assume cross-CPU portability. Destination startup needs no Java, Maven, Node, source code, registry login or image downloads. Docker Desktop itself must already be installed and running.

1. Copy `car-hire-docker.zip` to the laptop. Extract it into a new private, permanent directory; do not extract over an existing configured installation. Keep `images.tar`, `bundle.json` and the scripts together.
2. For example, from the folder containing the ZIP, run these PowerShell commands (choose an unused destination):

```powershell
Expand-Archive -LiteralPath .\car-hire-docker.zip -DestinationPath .\CarHire
Set-Location .\CarHire\car-hire-docker
powershell -NoProfile -File .\Start-CarHire.ps1
```

If you extracted through File Explorer instead, open PowerShell in the folder containing `Start-CarHire.ps1` and run the last command. The script handles `docker load` and Compose startup; you do not need to import each image manually. After the first extraction, subsequent starts need only the last command, not another extraction.

3. Choose an owner password of at least 12 characters. The script generates a separate random database password, loads missing images, starts all services without pulling/building, and waits for health checks. Open `http://localhost:8080` and sign in as `owner`.

Repeat the same command after restarting the laptop. Existing secrets/settings/data are reused. The script verifies bundled file checksums and image identities; a checksum mismatch fails rather than silently starting altered payloads. Checksums detect corruption, not a malicious replacement of the bundle and its manifest: obtain the ZIP from a trusted source and compare its separately recorded SHA256.

`settings.env` contains non-secret settings only. To change port, stop the stack, set `HTTP_PORT=8085`, then start again. `OWNER_USERNAME`, `BIND_ADDRESS` and `COOKIE_SECURE` are configurable there. Image tags must continue matching the bundle manifest. Process environment variables override settings; clear an old `HTTP_PORT` override when changing the file. The startup script accepts `-ProjectName another-name` for a separate installation; always use the same project name for subsequent starts/stops or Docker will create different volumes.

## Stop, logs and retained data

```powershell
powershell -NoProfile -File .\Stop-CarHire.ps1
docker compose --project-name carhire --env-file settings.env --file compose.yaml logs --tail 100 backend
```

Stopping removes containers/network, **not** PostgreSQL or attachments. Named volumes are `carhire_database` and `carhire_attachments` with the default project. Never use `down --volumes` for a real installation. Docker Desktop factory reset or deleting volumes can destroy these records. Keep regular encrypted backups independently of this laptop.

Secrets live in `secrets\database-password` and `secrets\owner-password`, not images/environment settings. Keep the directory private and backed up securely; these are plaintext host files, not a vault. The startup script restricts directory permissions and never prints credentials. If data volumes already exist but a secret file is missing, it refuses to generate replacement credentials. Restore the original files. Changing the database password file alone does **not** rotate a password in an initialized PostgreSQL database. Owner-password changes require a backend restart and invalidate sessions; maintain the application's minimum password requirements.

## Move existing business data

Loading images does **not** copy Docker volumes. For a fresh installation on the other laptop, use the app's verified portable ZIP export/import workflow to transfer records and attachments, then verify counts, financial reconciliation and files before relying on it. Export/import is subject to the application's documented limits; it is not a byte-for-byte server disaster-recovery backup. For an exact server migration, stop writes and back up/restore PostgreSQL and attachment volumes consistently, with their original secrets and the same application version. Do not copy a running database's raw files or assume `images.tar` is a data backup. Preserve the old installation until the restored one is verified.

## Local HTTP versus remote access

Default publishing is **127.0.0.1:8080**, and `COOKIE_SECURE=false` is solely for this laptop's HTTP testing. Backend/database ports are not published. Do not expose this default deployment to a LAN/Internet. Android requires HTTPS and cannot use this loopback-only HTTP endpoint from a phone.

For real remote use, provide a trusted HTTPS endpoint that forwards to the web container, configure access/firewall rules and set `COOKIE_SECURE=true`. The application uses relative URLs, so keep web/API on the same origin. This bundle does not provision certificates or a public hostname. Browser reminders require an open tab and notification permission; there is no server push service.

## Build a new bundle from the repositories

Keep `car-hire-web` beside `car-hire-backend`. From the backend repository:

```powershell
powershell -NoProfile -File .\deployment\Build-PortableBundle.ps1
# Separate ARM64 bundle, when supported by the builder:
powershell -NoProfile -File .\deployment\Build-PortableBundle.ps1 -Platform linux/arm64 -OutputDirectory Q:\packages\car-hire-arm64
```

The builder needs network access for base images/Maven/npm. It builds from source using multi-stage Dockerfiles, saves only runtime images, writes exact image identities and SHA256 checksums, and produces `dist\car-hire-docker.zip`. Local app dependencies/build tools are not required. Existing output directories/ZIPs are never overwritten: choose a new `-OutputDirectory`. Source build contexts are allowlisted; Git history, local environment files, storage and host build directories are excluded. No volumes are exported. Do not ZIP a configured deployment folder to distribute fresh installations: it would include its local secrets.

The original backend root `compose.yaml` is **development database only**. This deployment's separate Compose file starts all three services, with database/backend/web health dependency ordering, non-root web/backend, read-only application root filesystems and persistent writable storage. Logs are available through Compose. Containerization does not remove functional/release gaps documented in the three application READMEs.

## Validation and manual startup

The web repository's existing browser tests can target a disposable deployment using `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080` and `PLAYWRIGHT_DOCKER=true`. Use the synthetic test password from `tests\business.spec.ts` only in that disposable stack, then run `npm run test:e2e`. Docker-specific tests cover proxy routing, private API behavior, caching, upload rejection and a stored attachment, and write a non-secret persistence fixture under ignored `test-results`. Never run these write tests against business data.

If not using the PowerShell startup helper, first verify `bundle.json` checksums, run `docker load --input images.tar`, copy `settings.env.example` to `settings.env`, and create the two secret files described above before:

```powershell
docker compose --project-name carhire --env-file settings.env --file compose.yaml up -d --no-build --pull never --wait --wait-timeout 300
```

For Linux/macOS manual setup, protect the `secrets` directory with mode `700` and make each mounted secret file readable by the non-root container user (mode `444` inside that private directory). A plain host `600` file owned by a different UID will not be readable by the backend. Compose secrets here are file-backed mounts, not encrypted Docker Swarm secrets. PowerShell 7 can also run the supplied helper on those systems. Check the bundle CPU architecture before starting.
