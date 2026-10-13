# Dependencies

| Component | Version |
| --- | --- |
| Kotlin | 2.5.0-Beta1 |
| Ktor | 3.6.0 |
| kotlinx.serialization | 1.12.0-RC |
| kotlinx.coroutines | 1.11.0 |
| Exposed | 1.5.1 |
| SQLite JDBC | 3.53.4.0 |
| Logback | 1.6.5 |
| kotlinx.html | 0.12.0 |
| htmx | 2.0.11 |
| Gradle | 9.9.0-milestone-2 |
| Foojay toolchain resolver | 1.0.0 |
| JDK toolchain | 21 |

Versions are defined in [the version catalog](../gradle/libs.versions.toml) and [Gradle wrapper settings](../gradle/wrapper/gradle-wrapper.properties). Resolved dependencies are locked. Every library comes from Maven Central.

The server targets JVM 21. The `api-contract` and `client` modules compile to JVM 17 bytecode with the Kotlin 2.5 beta compiler, so consumers need a compatible Kotlin compiler.

## Vendored htmx

The [admin portal](admin.md) serves htmx from `server/src/main/resources/admin/htmx.min.js`, the `dist/htmx.min.js` of the npm package `htmx.org` 2.0.11, unchanged. Its SHA-256 is `d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717`. To update it, take `dist/htmx.min.js` from the new release's npm tarball, check the tarball against the registry's `integrity` value, and record the new version and SHA-256 here.

## Changing versions

[Renovate](../renovate.json) proposes updates for the version catalog, the Gradle wrapper, the Dockerfile base images, Compose and the workflow actions, pinning images and actions by digest. It follows Kotlin, kotlinx.serialization and kotlinx.coroutines prereleases, as the dependency policy in `AGENTS.md` asks; Ktor follows releases, matching the app. Its Gradle manager rewrites the lockfiles and the verification metadata in the same pull request and runs a weekly lock file maintenance. By hand:

1. Update the exact versions in [the version catalog](../gradle/libs.versions.toml).
2. Run the build with `--write-locks` and review the lockfile changes.
3. Run `scripts/update-verification-metadata.sh` and review the new checksums in `gradle/verification-metadata.xml`. Gradle checks every resolved artifact against that file and fails the build on a mismatch or an unlisted artifact. The script refreshes dependencies and reruns every task, so it records artifacts a warm Gradle cache skips, such as BOM `.module` files, and it prints the changed lines. Set `GRADLEW` to run Gradle through a wrapper command of your own. The `kotlinx-coroutines-bom` 1.8.0 entry is resolved only by CI's Gradle setup; the script fails if it goes missing.
4. Run both smoke scripts, as described in [development](development.md#build-and-test).
5. For a wrapper update, pin the official distribution checksum in `gradle-wrapper.properties`.

Published versions are listed on the official [Kotlin EAP](https://kotlinlang.org/docs/whatsnew-eap.html), [Ktor](https://github.com/ktorio/ktor/releases) and [Gradle distribution](https://services.gradle.org/distributions/) pages.
