# Sofa King Backend

Use `docs/architecture.md` for service boundaries and `docs/plans/foundation.md` for the current milestone. Follow a lightweight native workflow: keep the plan current, implement working slices, run relevant checks, and commit verified changes. No Superpowers workflow or approval gates are required.

## Scope

- Kotlin/JVM backend for our own Sofa King Android TV client; live TV first, DVR stretch.
- Prefer one deployed app, local SQLite, bounded work and explicit resource ownership.
- Preserve UUIDs, numbering, household edits and favorites; source failures must not delete channel identities.
- Auto-cull offerings reversibly with qualified independent evidence; distinguish provider outages, auth/rate limits, local faults and device-specific failures.
- Never import secrets, signed media URLs, private provider dumps or live databases into Git.
- No production OMV or Sofa King client changes as a side effect of local backend work.

## Dependency policy

James requested bleeding-edge server-side prereleases. Prefer the latest published Kotlin EAP, Ktor EAP and serialization prerelease, and latest published supporting libraries; pin exact versions, the wrapper checksum and dependency lockfiles. Verify official release/EAP registries before updates and compile/test the complete selected stack. Avoid dynamic versions and unversioned snapshots. Record source links, verification date and any incompatibility in `docs/dependencies.md`.

## Verification

Use JDK 21 and run `./gradlew test` and `./gradlew :server:installDist` and `python3 scripts/smoke.py` before reporting the foundation ready. On this shared host, use `/home/james/.claude/bin/gradle-queue -- test :server:installDist` for Gradle builds. Test persistence against real temporary SQLite files and API behavior through Ktor's test host. Test source-health time rules with an injected clock; do not depend on wall-clock sleeps.

Do not advertise unimplemented API capabilities or successful media/device verification. Include the exact remaining limitations in the README and completion report.
