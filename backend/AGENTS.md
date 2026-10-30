# Sofa King server

Kotlin/JVM media center server (live TV, recordings and media libraries) for the Sofa King app in `../app`, in one Gradle module, `server` (Ktor on Netty, SQLite through Exposed and sqlite-jdbc, FFmpeg for remuxing, recording and library transcodes). Read README, docs/README.md and docs/architecture.md for current setup and behavior, docs/api.md for the wire interface and docs/admin.md for the portal. Implement working slices, run the relevant checks and commit verified changes.

## Build

Use JDK 21 and FFmpeg on the path (the tests generate HLS fixtures with it through `MediaFixture`; CI uses Ubuntu 24.04's FFmpeg 6.1, the version the image ships, and its output differs from newer majors; on a Mac, put Homebrew's `ffmpeg@6` first on the path: `PATH="$(brew --prefix ffmpeg@6)/bin:$PATH" ./gradlew test`). Comskip on the path runs one more test, which skips without it; CI and the image install Ubuntu's. The server's Gradle build is `backend/`; on the shared host, run from the repository root:

```sh
/home/james/.claude/bin/gradle-queue -C backend -- spotlessCheck detekt test :server:installDist
```

Elsewhere run `./gradlew spotlessCheck detekt test :server:installDist` from `backend/`, and `./gradlew spotlessCheck detekt jvmTest` from `contract/` after a wire change. One test class: `./gradlew :server:test --tests 'dev.jamesrich.sofaking.backend.catalog.LineupServiceTest'`. Run both smoke scripts before reporting live TV ready, and `scripts/api-fuzz.py` after changing a route or the OpenAPI document; docs/development.md#build-and-test has them, and CONTRIBUTING.md#ci lists the script checks CI adds (shellcheck, the script unit tests). Test persistence against real temporary SQLite files and API behavior through Ktor's test host; a test that depends on a streamed body's lifetime or on an FFmpeg client runs on a real socket (`RealServer`). Test source-health time rules with an injected clock; do not depend on wall-clock sleeps. Native provider tests run against `ProviderFixture` and the sanitized documents under `server/src/test/resources/providers/`; docs/development.md has the steps for adding a provider.

Commit scopes: CONTRIBUTING.md#commits.

## Code map

All source is under `dev.jamesrich.sofaking.backend`; tests mirror it in `server/src/test`. Read the source for the file layout; these are the rules it doesn't show:

- Every `SOFA_` variable is one entry in `settings/Settings.kt` (type, range, default, label, help), which the startup reader, the runtime settings, the portal's runtime form and its startup list all use; a test keeps it equal to docs/live-tv.md's server settings table, the only place the variables are documented. A new runtime setting is its entry, the code that reads it and its table row. `api/` holds the server-only request and response models.
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
- The server's request and response types shared with the app come from `../contract`; change a wire field there, never in the server, and keep every field of the fixtures (CONTRIBUTING.md#wire-changes).

## Dependency policy

Use bleeding-edge server-side prereleases: the latest published Kotlin EAP and serialization prerelease, and the latest published supporting libraries, so one Kotlin, kotlinx.serialization and Ktor release serves the app and the contract too. Verify official release/EAP registries before updates and compile/test the complete selected stack. Avoid dynamic versions and unversioned snapshots. Pins and update steps: CONTRIBUTING.md#dependencies; update docs/dependencies.md's table when its pins change.

## Documentation

Document current facts and executable instructions, by the rules in CONTRIBUTING.md#documentation. Put change rationale and validation results in commit or pull-request descriptions. Do not advertise unimplemented API capabilities or successful media/device verification; list the exact remaining limitations in the README and the completion report.
