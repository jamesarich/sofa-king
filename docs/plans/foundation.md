# Backend foundation

James authorized a lightweight repository-native workflow on 2026-10-10: maintain this plan, implement working slices, verify and commit. No Superpowers review gates apply.

## Goal and boundaries

Deliver a locally runnable Kotlin server with a native bootstrap API, persistent server identity and persistent, deterministic source quarantine/recovery policy. Sofa King is the only client; live TV comes first, DVR remains a stretch goal. See [architecture](../architecture.md) for the complete migration roadmap and [dependencies](../dependencies.md) for verified preview pins and practices.

One JVM application owns HTTP and local SQLite. `api-contract` holds serializable wire DTOs; `server` owns Ktor, storage and health policy. Blocking JDBC runs in short serialized transactions on an owned dispatcher. Network and media work stays outside transactions. UUIDs, household edits and favorites must survive losing an offering. Rediscovery must preserve quarantine and manual disable.

## Completed foundation

- [x] Two-module JVM build, JDK 21 toolchain, exact preview versions, checksum-pinned Gradle wrapper and dependency locks; Kotlin BOM aligns runtime libraries with the compiler.
- [x] Ktor/Netty application with validated environment configuration and owned shutdown.
- [x] Liveness, storage readiness, native bootstrap and structured errors. Unimplemented capabilities explicitly report false.
- [x] Persistent server UUID, local SQLite WAL/foreign keys/busy timeout and atomic numbered migrations. Unsupported future schemas stop startup without recreating data.
- [x] Pure offering-health reducer with fixed-time tests: three independent failures within six hours spanning ten minutes quarantine; recovery requires probation and two qualified successes at least ten minutes apart.
- [x] Offering-local strikes exclude provider, authentication, rate-limit, local and device faults. Manual disables and archived states take precedence; stale or tied observations cannot reverse the latest applied decision.
- [x] Persistent offering state and atomic observation ledger deduplicate worker incidents across concurrent delivery and restart. Registration cannot reset existing health.
- [x] Installed-distribution smoke check exercises real HTTP, SIGTERM, migrations, SQLite WAL and identity across restart.

## Verification evidence — 2026-10-10

The selected preview stack passed `test :server:installDist --write-locks` through the shared-host Gradle queue. All 32 tests passed: 15 health-policy, 10 persistent-store, three HTTP, three configuration and one migration rollback test. Tests use real temporary SQLite files and fixed health timestamps.

`python3 scripts/smoke.py` passed against the installed distribution. It starts the server twice with one disposable data directory, checks liveness/readiness/bootstrap/404 responses, stops each with SIGTERM and verifies persistent identity and applied schema versions.

The packaging check caught a Kotlin-inferred return type that prevented Java from recognizing the entry point; `main` now explicitly returns `Unit`. Tests and packaging were rerun after the fix, then the real-server smoke passed.

## Next working slices

1. Native catalog and bounded guide API, stable channel/offering identities, and an initial read-only provider adapter. Preserve existing household channel numbering and migrate Sofa King favorites deliberately.
2. Sofa King client integration and pairing before exposing administration or household mutations beyond localhost.
3. Owned playback sessions, source selection and measured native HLS playback; failover changes stream epoch and explicitly reopens the client player.
4. Bounded durable probe/recovery jobs using real decode-qualified evidence, provider correlation and capacity-aware scheduling; reversible automatic archival and explicit ledger retention.
5. Retire existing services only after sustained household playback/guide acceptance and a demonstrated rollback path. Timeshift and DVR follow live TV.

## Current limits

No provider fetching, catalog/guide routes, pairing, events, network recovery scheduling, automatic archival, playback or DVR is implemented. Health operations are internal Kotlin APIs: this foundation does not yet cull production sources. Probe qualification and eligibility enforcement belong to the playback/jobs slices. Observation ledger pruning needs an explicit retention policy before continuous production use.

No production OMV configuration or Sofa King client files were changed. No push or deployment is included in this milestone.
