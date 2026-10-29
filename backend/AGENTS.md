# Sofa King server

Kotlin/JVM media center server (live TV, recordings and media libraries) for the Sofa King app in `../app`, in one Gradle module, `server` (Ktor on Netty, SQLite through Exposed and sqlite-jdbc, FFmpeg for remuxing, recording and library transcodes). Read README, docs/README.md and docs/architecture.md for current setup and behavior, docs/api.md for the wire interface and docs/admin.md for the portal. Implement working slices, run the relevant checks and commit verified changes.

## Build

Use JDK 21 and FFmpeg on the path (the tests generate HLS fixtures with it through `MediaFixture`; CI uses Ubuntu 24.04's FFmpeg 6.1, the version the image ships, and its output differs from newer majors). Comskip on the path runs one more test, which skips without it; CI and the image install Ubuntu's. The server's Gradle build is `backend/`; on the shared host, run from the repository root:

```sh
/home/james/.claude/bin/gradle-queue -C backend -- spotlessCheck detekt test :server:installDist
```

Elsewhere run `./gradlew spotlessCheck detekt test :server:installDist` from `backend/`, and `./gradlew spotlessCheck detekt jvmTest` from `contract/` after a wire change. One test class: `./gradlew :server:test --tests 'dev.jamesrich.sofaking.backend.catalog.LineupServiceTest'`. Then, from `backend/`, run both `python3 scripts/smoke.py` and `python3 scripts/live-smoke.py` before reporting live TV ready. CI also runs `shellcheck deploy/*.sh`, `python3 -I -m unittest discover -s scripts -p 'test_*.py'` and `python3 scripts/api-fuzz.py` (Schemathesis on every documented route; run it after changing a route or the OpenAPI document). Test persistence against real temporary SQLite files and API behavior through Ktor's test host; a test that depends on a streamed body's lifetime or on an FFmpeg client runs on a real socket (`RealServer`). Test source-health time rules with an injected clock; do not depend on wall-clock sleeps. Native provider tests run against `ProviderFixture` and the sanitized documents under `server/src/test/resources/providers/`; docs/development.md has the steps for adding a provider.

Commits use Conventional Commits, `type(scope): summary` in the imperative, scoped by package or area: `providers`, `policy`, `hdhr`, `logos`, `playback`, `admin`, `server`, `backend`, `build`, `ci`, `docs`.

## Code map

All source is under `dev.jamesrich.sofaking.backend`; tests mirror it in `server/src/test`. Read the source for the file layout; these are the rules it doesn't show:

- The environment settings in `ServerConfig.kt` are documented only in docs/live-tv.md's server settings table. Request and response models shared with the app live in `../contract`; `api/` holds the server-only ones.
- Live streams are sessions in `playback/SessionManager`, library playback is a session in `library/VodTable`, and multiview sessions live in `multiview/MultiviewSessions`; each keeps its own table.
- Library item ids derive from the library and the relative path, so they survive rescans.
- Migrations are `server/src/main/resources/db/Vnnn__name.sql`, listed in order in `store/Migration.kt`; a schema change adds a new, higher-numbered file and its list entry and never edits an applied one. Versions may have gaps.
- The admin portal at `/admin` (`admin/`) works without JavaScript and uses the vendored htmx in `resources/admin/`. Its actions mirror the admin API except the runtime settings edit, a job run on request, stopping a session, ignoring a home page item, canceling a pairing code, backups, the temporary DEBUG level, a TMDB match made by hand, and the update check and request, which are portal-only; give a new action its API route first unless it belongs with those.
- The OpenAPI document is the hand-kept `server/src/main/resources/api/openapi.json`, served at `/admin/api/openapi.json`; a route or field change updates it and docs/api.md together.

## Scope

- Live TV, its recordings and media libraries (read-only folders under `SOFA_MEDIA_DIR`; the server writes only its data directory and the DVR's `SOFA_RECORDINGS_DIR`). Prefer one deployed app, local SQLite, bounded work and explicit resource ownership.
- Preserve UUIDs, numbering, household edits and favorites; source failures must not delete channel identities.
- Auto-cull offerings reversibly with qualified independent evidence; distinguish provider outages, auth/rate limits, local faults and device-specific failures.
- Never import secrets, signed media URLs, private provider dumps or live databases into Git. The repository is public. Keep tokens and URLs out of exception messages, job notes and logs.
- No production OMV or Sofa King client changes as a side effect of local backend work.
- The server's request and response types come from `../contract`; change a wire field there, never in the server, and follow `../contract/README.md`. Keep every field of the fixtures in `contract/src/jvmTest/resources/wire`.

## Dependency policy

Use bleeding-edge server-side prereleases: the latest published Kotlin EAP and serialization prerelease, and the latest published supporting libraries. Versions come from the repository's one catalog, `../gradle/libs.versions.toml`, which the app and the contract share, so one Kotlin, kotlinx.serialization and Ktor release serves both and a test can load both on one classpath. Pin exact versions and the wrapper checksum. Verify official release/EAP registries before updates and compile/test the complete selected stack. Avoid dynamic versions and unversioned snapshots. docs/dependencies.md has the update steps; update its table when pins change.

## Documentation

Document current facts and executable instructions. Do not add review diaries, dated test-count summaries, design histories or speculative roadmaps. Put change rationale and validation results in commit or pull-request descriptions. Do not advertise unimplemented API capabilities or successful media/device verification; list the exact remaining limitations in the README and the completion report.
