# Sofa King Backend

A Kotlin server for our Sofa King Android TV client: native channel and guide APIs, managed live playback, and automatic quarantine/recovery of bad sources.

## Current slice

The foundation provides a runnable Ktor/Netty application, native bootstrap DTOs, local SQLite persistence, transactional migrations and a persistent source-health policy.

- `GET /healthz`: process liveness.
- `GET /readyz`: migrated storage readiness.
- `GET /api/v1/bootstrap`: persistent server UUID, server time and truthful capability flags.
- Unknown routes return a structured JSON error.

Source-health operations are internal Kotlin APIs. They preserve state across restart, deduplicate worker incidents, quarantine repeated offering failures, calculate recovery backoff and require probation before restoring eligibility. Provider/auth/rate-limit/local/device failures do not count as offering strikes. Manual disables survive rediscovery and successful observations.

**Not implemented yet:** provider ingestion, catalog/guide routes, pairing, SSE, network recovery jobs, automatic archival, media playback, timeshift or DVR. The bootstrap reports catalog, guide, playback and events as unavailable. No production IPTV or client configuration is changed by running this foundation.

## Run locally

Requires JDK 21. The checksum-pinned Gradle wrapper downloads its distribution.

```sh
./gradlew test :server:installDist
python3 scripts/smoke.py
SOFA_HOST=127.0.0.1 SOFA_PORT=8080 SOFA_DATA_DIR=./data \
  server/build/install/server/bin/server
```

```sh
curl http://127.0.0.1:8080/healthz
curl http://127.0.0.1:8080/readyz
curl http://127.0.0.1:8080/api/v1/bootstrap
```

Configuration comes from `SOFA_HOST` (default `127.0.0.1`), `SOFA_PORT` (default `8080`) and `SOFA_DATA_DIR` (default `data`). Invalid ports or blank configuration fail before binding. Stop with Ctrl-C or SIGTERM; SQLite work and the Netty engine are closed. Retain the data directory across restart to preserve identity and source health. Database files and secrets are ignored by Git.

On James's shared development host, run Gradle through `/home/james/.claude/bin/gradle-queue -- test :server:installDist` to coordinate with other builds. This host-specific queue is not a project dependency.

Verified on 2026-10-10: all 32 tests passed on the selected preview stack; the installed-server smoke check passed real HTTP, migrations, SIGTERM and identity across restart. The smoke script requires Python 3 and uses a disposable local data directory.

## Documents and workflow

- [Architecture and migration roadmap](docs/architecture.md)
- [Living foundation plan](docs/plans/foundation.md)
- [Preview dependency pins and documented practices](docs/dependencies.md)

Use a lightweight native workflow: implement working slices, update the plan with decisions and evidence, test, fix and commit. `AGENTS.md` contains project-specific boundaries. No Superpowers process dependency remains.

The compiler and server stack use explicitly selected preview releases, pinned and locked for reproducibility. Live TV is the first release; timeshift and scheduled DVR are stretch goals. The API and media protocols evolve jointly with Sofa King. Implementation proceeds alongside the existing OMV stack until production parity and rollback have been demonstrated.
