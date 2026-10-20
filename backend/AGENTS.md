# Sofa King server

Kotlin/JVM media center server (live TV, recordings and media libraries) for the Sofa King app in `../app`, in one Gradle module, `server` (Ktor on Netty, SQLite through Exposed and sqlite-jdbc, FFmpeg for remuxing, recording and library transcodes). Read README, docs/README.md and docs/architecture.md for current setup and behavior, docs/api.md for the wire interface and docs/admin.md for the portal. Implement working slices, run the relevant checks and commit verified changes.

## Build

Use JDK 21 and FFmpeg on the path (the tests generate HLS fixtures with it through `MediaFixture`; CI uses Ubuntu 24.04's FFmpeg 6.1, the version the image ships, and its output differs from newer majors). Comskip on the path runs one more test, which skips without it; CI and the image install Ubuntu's. The server's Gradle build is `backend/`; on the shared host, run from the repository root:

```sh
/home/james/.claude/bin/gradle-queue -C backend -- test :server:installDist
```

Elsewhere run `./gradlew test :server:installDist` from `backend/`. One test class: `./gradlew :server:test --tests 'dev.jamesrich.sofaking.backend.catalog.LineupServiceTest'`. Then, from `backend/`, run both `python3 scripts/smoke.py` and `python3 scripts/live-smoke.py` before reporting live TV ready. CI also runs `shellcheck deploy/*.sh` and `python3 -I -m unittest discover -s scripts -p 'test_*.py'`. Test persistence against real temporary SQLite files and API behavior through Ktor's test host; a test that depends on a streamed body's lifetime or on an FFmpeg client runs on a real socket (`RealServer`). Test source-health time rules with an injected clock; do not depend on wall-clock sleeps. Native provider tests run against `ProviderFixture` and the sanitized documents under `server/src/test/resources/providers/`; docs/development.md has the steps for adding a provider.

Commits use Conventional Commits, `type(scope): summary` in the imperative, scoped by package or area: `providers`, `policy`, `hdhr`, `logos`, `playback`, `admin`, `server`, `backend`, `build`, `ci`, `docs`.

## Code map

All source is under `dev.jamesrich.sofaking.backend`; tests mirror it in `server/src/test`.

- `Main.kt`, `LiveRuntime.kt`, `ServerConfig.kt`, `Secrets.kt`: process start, wiring, the environment settings (documented only in docs/live-tv.md's server settings table) and the generated tokens in `data/secrets.json`.
- `http/`: the Ktor application and route files (`StatusRoutes`, `AdminRoutes`, `PolicyRoutes`, `PlexRoutes`, `HdhrRoutes`, `OutputRoutes`, `LogoRoutes`, `ViewerRoutes` for pairing and per-client preferences, `RecordingRoutes` for the DVR, `SetupRoutes` for the source file and the starter policy, `LibraryRoutes` and `LibraryAdminRoutes` for media libraries), `AddressAllowList` and `AuthFailures`. `api/`: the server-only response and request models (admin, policy, Plex, sources, status); the shared ones are in `../contract`.
- `catalog/`: `FeedService` and `FeedParser` (M3U and native imports into candidates and sources), `GuideService` (XMLTV and service guides), `PolicyService`, `StationBuilder`, `StationKeys`, `LearnedAliases` and `SourceIdentity` (the lineup policy and station identity), `LineupService` and `CatalogService` (what every client reads).
- `providers/`: `ProviderClient`, `NativeServices`, `ProviderRegistry` and one client per free service. `upstream/`: the HTTP and FFmpeg inputs. `health/`: `ProbeWorker`, `HealthPolicy` and `HttpQualifier` (source health and auto-cull).
- `setup/`: `SetupService` (writes the source file and the starter policy, then restarts), `ServiceCatalog` (the native services as the portal and the starter policy present them) and `DefaultGroups` (the shipped group map in `resources/policy/starter-groups.json`). `update/UpdateChecker`: the registry check and the host updater's status and request files. `io/AtomicFiles`: atomic file writes.
- `dvr/`: `Dvr` (rules, the schedule and the recording loop), `DvrPlanner` (which airings a rule takes, duplicates, conflicts and what cleanup deletes), `Recorder` (one channel into one file through an external session and FFmpeg), `Commercials` (Comskip's breaks and the EDL beside the file) and `RecordingLayout` (the Plex-style file names and the `.inprogress` folder).
- `playback/`: `SessionManager` (every stream is a session), `MediaRelay` and `HlsRewriter` (HLS), `TsFanout` (shared MPEG-TS), `TsRemux` (HLS to MPEG-TS for Plex) and `LoopbackRelay` (the loopback relay every FFmpeg reads sources through). `plex/PlexSync` pushes channel maps.
- `library/`: media libraries. `LibraryService` (add, rename, remove, the scan loop), `LibraryScanner` (walks a folder into items), `LibraryNames` (Plex and Jellyfin file names), `Nfo` (Kodi sidecars), `MediaProbe` (FFprobe), `LibraryMetadata` and `Tmdb` (TMDB lookups after each scan), `LibraryCatalog` (what clients read, with TMDB folded in, and watch state), `LibraryArtwork` (posters and backdrops) and `VodSessions` (library playback: direct play, or FFmpeg HLS). Item ids derive from the library and the relative path, so they survive rescans.
- `store/`: `BackendStore`, `DatabaseFactory`, `Tables` and one storage file per area. Migrations are `server/src/main/resources/db/Vnnn__name.sql`, listed in order in `Migrations.kt`; a schema change adds a new, higher-numbered file and its list entry and never edits an applied one. Versions may have gaps.
- `admin/`: the server-rendered portal at `/admin` (`AdminPortal`, `AdminPages`, `AdminSystem`, `AdminSessions`, `AdminSettings`, `AdminSetup` (the Sources page's services and playlists), `AdminViewers` with the ZXing-rendered pairing QR in `QrCode`, `AdminLibrary`, `AdminRecordings`, `AdminTheme` (the destinations and their tabs), `AdminPolicyEdits`, `AdminApi` (the API reference page)), which works without JavaScript and uses the vendored htmx in `resources/admin/`. Its actions mirror the admin API except the runtime settings edit, a source probe or job run on request, stopping a session, ignoring a home page item, cancelling a pairing code, and the update check and request, which are portal-only; give a new action its API route first unless it belongs with those. `settings/RuntimeSettings` holds the settings the portal edits at run time; `jobs/` lists the background jobs; `logging/EventRing` feeds the Events page; `logos/` is the logo cache; `pairing/PairingService` holds the one live pairing code in memory.
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
