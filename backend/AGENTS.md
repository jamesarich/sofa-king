# Sofa King Backend

Use `README.md` for setup, `docs/api.md` for the wire interface and `docs/architecture.md` for implemented service boundaries. Implement working slices, run relevant checks and commit verified changes.

## Scope

- Kotlin/JVM server for the Sofa King app in `../app`; live TV only.
- Prefer one deployed app, local SQLite, bounded work and explicit resource ownership.
- Preserve UUIDs, numbering, household edits and favorites; source failures must not delete channel identities.
- Auto-cull offerings reversibly with qualified independent evidence; distinguish provider outages, auth/rate limits, local faults and device-specific failures.
- Never import secrets, signed media URLs, private provider dumps or live databases into Git.
- No production OMV or Sofa King client changes as a side effect of local backend work.

## Dependency policy

Use bleeding-edge server-side prereleases. Prefer the latest published Kotlin EAP and serialization prerelease, and latest published supporting libraries; Kotlin, kotlinx.serialization and Ktor match the versions in `../app`, so one Ktor release serves both and a test can load both on one classpath; pin exact versions, the wrapper checksum and dependency lockfiles. Verify official release/EAP registries before updates and compile/test the complete selected stack. Avoid dynamic versions and unversioned snapshots. Update the dependency reference when pins change.

## Documentation

Write current facts and executable instructions. Do not add review diaries, dated test-count summaries, design histories or speculative roadmaps. Put change rationale and validation results in commit or pull-request descriptions.

## Verification

Use JDK 21 and, from `backend/`, run `./gradlew test` and `./gradlew :server:installDist` and both `python3 scripts/smoke.py` and `python3 scripts/live-smoke.py` before reporting live TV ready. On this shared host, use `/home/james/.claude/bin/gradle-queue -C backend -- test :server:installDist` from the repository root for Gradle builds. Test persistence against real temporary SQLite files and API behavior through Ktor's test host. Test source-health time rules with an injected clock; do not depend on wall-clock sleeps.

Do not advertise unimplemented API capabilities or successful media/device verification. Include the exact remaining limitations in the README and completion report.
