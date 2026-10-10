# Sofa King Backend

A working Kotlin live TV backend for our Sofa King client, using a pinned preview compiler/server stack. One application owns the native API, SQLite, playback sessions and source qualification. DVR remains a stretch goal.

## Implemented

- Configured M3U/XMLTV ingestion with atomic activation and persistent last-good snapshots.
- Stable channel UUIDs, display numbers, legacy favorite migration keys, native catalog with ETags and bounded guide queries.
- Preview/watch sessions with idempotency, promotion, 90-second leases, capacity limits and cancellation.
- Native HLS relay with rendition/key/subtitle/map rewriting and byte ranges, plus streaming TS delivery.
- Automatic decode probes, persisted retry timing, quarantine/backoff/probation, and manual-disable APIs. Source withdrawal preserves channel identities.
- Typed Kotlin client handles with observable playback state, automatic lease renewal and cancellation cleanup.
- Local SQLite WAL, atomic migrations, persistent server identity and authenticated API access for LAN binding.

The Sofa King Android app still consumes M3U/XMLTV and needs native API integration. This implementation is a working migration backend; it still uses configured provider feeds and does not yet replace every provider-specific component in the household stack.

## Run

Requires JDK 21 and FFmpeg on PATH. Shared contract/client artifacts target JVM 17. Preview dependencies are pinned and locked; see [versions and practices](docs/dependencies.md).

```sh
./gradlew test :server:installDist
SOFA_SOURCES=./data/sources.json server/build/install/server/bin/server
```

On James's shared development host, build through `/home/james/.claude/bin/gradle-queue -- test :server:installDist`.

`data/sources.json` is an ignored local provider configuration. See [configuration, API and client usage](docs/live-tv.md) for its shape and the household bridge example. Default binding is `127.0.0.1:8080`; non-loopback binding requires `SOFA_API_TOKEN`. Keep the data directory across restarts. Set `probe: false` on bridge feeds when the existing stack still owns qualification.

```sh
curl http://127.0.0.1:8080/api/v1/bootstrap
curl http://127.0.0.1:8080/api/v1/channels
curl http://127.0.0.1:8080/api/v1/providers
curl http://127.0.0.1:8080/api/v1/sources
```

Without source configuration the service starts with an empty catalog. Guide/playback endpoints remain available and report missing channels explicitly. No provider URLs or secrets appear in catalog or source-status responses.

## Verification

```sh
python3 scripts/smoke.py
python3 scripts/live-smoke.py
```

These checks exercise the installed application over HTTP, persistence across restart, generated-media decoding, lease release and upstream cancellation. They do not certify Android TV playback or household cutover. See [acceptance commands](docs/live-tv.md#verification) and the [dated review record](docs/review-2026-10-10.md).

## Remaining work

Native Sofa King UI integration and device acceptance; provider-specific discovery/resolvers/auth renewal; device pairing/roles; SSE; upstream sharing and provider capacity reservations; remux/transcode exceptions; automatic archival and incident retention; timeshift/DVR. Current provider fault classification uses HTTP/FFmpeg evidence rather than a full provider-wide correlation service. One guide revision is retained; mismatches request resync. See [implementation details and limits](docs/live-tv.md).

## Project documents

- [Architecture and migration roadmap](docs/architecture.md)
- [Current live TV milestone](docs/plans/live-tv.md)
- [Completed foundation](docs/plans/foundation.md)
- [Dependency pins and documented practices](docs/dependencies.md)
- [Code and documentation review](docs/review-2026-10-10.md)

Follow the lightweight repository-native workflow in `AGENTS.md`: implement working slices, maintain the plan, verify and commit. No Superpowers process dependency remains.
