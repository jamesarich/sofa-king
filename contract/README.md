# Contract

The live TV request and response types of the server's `/api/v1`, shared by the server in [`backend/`](../backend/README.md) and the app in [`app/`](../app/README.md). A Kotlin Multiplatform build with `kotlinx.serialization` types and the `Wire` constants only: no `Json` instance and no Ktor. Both builds include it, so a change here compiles and tests both, and lands with both sides in one pull request.

Released apps and servers outlive any one commit, so the types change only in ways both sides can read:

- Add a field with a default. Give a field the app does not read a default. Never rename or remove one.
- Timestamps stay ISO-8601 strings. Intents, statuses and error codes stay strings; a client reads an unknown value as its fallback.
- `src/jvmTest/resources/wire/v1` holds real server responses. The contract's tests decode them with a client's settings, and the server's `LiveTvTest` fails when a response drops or retypes one of their fields. Never edit or delete a fixture; a new API version gets a new directory.

The server's admin, policy and status models stay in `backend/server`. Build and test from this directory with `./gradlew jvmTest assemble`, or as part of either consumer's build.

`gradle/libs.versions.toml` pins Kotlin and kotlinx.serialization on its own. The app and the server each compile this build with their own Kotlin plugin, so `settings.gradle.kts` fails every build that includes it unless `app/gradle/libs.versions.toml` and `backend/gradle/libs.versions.toml` pin the same two versions. A Kotlin or kotlinx.serialization bump changes all three catalogs in one pull request.

Its resolved dependencies are locked in `gradle.lockfile`, `buildscript-gradle.lockfile` and `settings-gradle.lockfile`, and Gradle checks every artifact against `gradle/verification-metadata.xml`. After a version change, run `./gradlew jvmTest assemble --write-locks` here and review the lockfile diff, then run `backend/scripts/update-verification-metadata.sh`, which regenerates this build's checksums with the server's.
