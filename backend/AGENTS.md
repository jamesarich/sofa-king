# Sofa King server

Kotlin/JVM live TV server for the Sofa King app in `../app`, in one Gradle module, `server`. Read README, docs/README.md and docs/architecture.md for current setup and behavior, and docs/api.md for the wire interface. Implement working slices, run the relevant checks and commit verified changes.

## Build

Use JDK 21 and FFmpeg on the path. The server's Gradle build is `backend/`; on the shared host, run from the repository root:

```sh
/home/james/.claude/bin/gradle-queue -C backend -- test :server:installDist
```

Then, from `backend/`, run both `python3 scripts/smoke.py` and `python3 scripts/live-smoke.py` before reporting live TV ready. Test persistence against real temporary SQLite files and API behavior through Ktor's test host. Test source-health time rules with an injected clock; do not depend on wall-clock sleeps.

Commits use Conventional Commits, `type(scope): summary` in the imperative, scoped by package or area: `providers`, `policy`, `hdhr`, `logos`, `playback`, `backend`, `build`, `ci`, `docs`.

## Scope

- Live TV only. Prefer one deployed app, local SQLite, bounded work and explicit resource ownership.
- Preserve UUIDs, numbering, household edits and favorites; source failures must not delete channel identities.
- Auto-cull offerings reversibly with qualified independent evidence; distinguish provider outages, auth/rate limits, local faults and device-specific failures.
- Never import secrets, signed media URLs, private provider dumps or live databases into Git. The repository is public.
- No production OMV or Sofa King client changes as a side effect of local backend work.
- The server's request and response types come from `../contract`; change a wire field there, never in the server, and follow `../contract/README.md`. Keep every field of the fixtures in `contract/src/jvmTest/resources/wire`.

## Dependency policy

Use bleeding-edge server-side prereleases: the latest published Kotlin EAP and serialization prerelease, and the latest published supporting libraries. Versions come from the repository's one catalog, `../gradle/libs.versions.toml`, which the app and the contract share, so one Kotlin, kotlinx.serialization and Ktor release serves both and a test can load both on one classpath. Pin exact versions, the wrapper checksum, the dependency lockfiles and `gradle/verification-metadata.xml`. Verify official release/EAP registries before updates and compile/test the complete selected stack. Avoid dynamic versions and unversioned snapshots. docs/dependencies.md has the update steps; update its table when pins change.

## Documentation

Document current facts and executable instructions. Do not add review diaries, dated test-count summaries, design histories or speculative roadmaps. Put change rationale and validation results in commit or pull-request descriptions. Do not advertise unimplemented API capabilities or successful media/device verification; list the exact remaining limitations in the README and the completion report.
