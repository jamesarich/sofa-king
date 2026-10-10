# Sofa King Backend

Read the architecture in `docs/superpowers/specs/2026-10-10-backend-design.md` and the active implementation plan before editing.

## Scope

- Kotlin/JVM backend for our own Sofa King Android TV client; live TV first, DVR stretch.
- Prefer one deployed app, local SQLite, bounded work and explicit resource ownership.
- Preserve UUIDs, numbering, household edits and favorites; source failures must not delete channel identities.
- Auto-cull offerings reversibly with qualified independent evidence; distinguish provider outages, auth/rate limits, local faults and device-specific failures.
- Never import secrets, signed media URLs, private provider dumps or live databases into Git.
- No production OMV or Sofa King client changes as a side effect of local backend work.

## Verification

Once the Gradle build exists, use JDK 21 and run `./gradlew test` and `./gradlew :server:installDist` before reporting the foundation ready. Test persistence against real temporary SQLite files and API behavior through Ktor's test host. Test source-health time rules with an injected clock; do not depend on wall-clock sleeps.

Do not advertise unimplemented API capabilities or successful media/device verification. Include the exact remaining limitations in the README and completion report.
