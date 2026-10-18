# Dependencies

Every library version is an exact pin in [the version catalog](../../gradle/libs.versions.toml), which this table does not repeat. The versions the catalog does not hold:

| Component | Version |
| --- | --- |
| htmx | 2.0.11 |
| Gradle | 9.9.0-milestone-2 |
| Foojay toolchain resolver | 1.0.0 |
| JDK toolchain | 21 |

The wrapper's version is in [the Gradle wrapper settings](../gradle/wrapper/gradle-wrapper.properties). Every version is exact and the wrapper has a pinned distribution checksum; resolved versions are not locked. Every library comes from Maven Central.

The image's programs come from its Ubuntu 24.04 (noble) base as distribution packages, at the versions its archive holds: FFmpeg 6.1, Comskip, `sqlite3`, `curl`, `tini`, and the VA-API drivers (Mesa, and on amd64 Intel's media driver and Quick Sync runtime). Renovate updates the base image by digest, which brings their security updates.

The server targets JVM 21. The [contract](../../contract/README.md) it shares with the app compiles to JVM 17 bytecode for the server and Android, and to every other target the app builds; it pins the same Kotlin and kotlinx.serialization versions as the server.

## Vendored htmx

The [admin portal](admin.md) serves htmx from `server/src/main/resources/admin/htmx.min.js`, the `dist/htmx.min.js` of the npm package `htmx.org` 2.0.11, unchanged. Its SHA-256 is `d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717`. To update it, take `dist/htmx.min.js` from the new release's npm tarball, check the tarball against the registry's `integrity` value, and record the new version and SHA-256 here.

## Changing versions

[Renovate](../../renovate.json) proposes updates for the version catalog, the Gradle wrappers, the Dockerfile base images and the workflow actions, pinning images and actions by digest. It follows Kotlin, kotlinx.serialization and kotlinx.coroutines prereleases, as the dependency policy in `AGENTS.md` asks; Ktor follows releases, matching the app. Renovate merges minor, patch and digest updates at least three days old once every check passes; Kotlin, the Android Gradle plugin, Compose Multiplatform and major updates wait for a person. Run the smoke scripts before merging a major bump to Ktor or the base image. By hand:

1. Update the exact versions in [the version catalog](../../gradle/libs.versions.toml).
2. Run the [build and test](development.md#build-and-test) checks and both smoke scripts, as described in [development](development.md#build-and-test).
3. For a wrapper update, pin the official distribution checksum in `gradle-wrapper.properties`.

Published versions are listed on the official [Kotlin EAP](https://kotlinlang.org/docs/whatsnew-eap.html), [Ktor](https://github.com/ktorio/ktor/releases) and [Gradle distribution](https://services.gradle.org/distributions/) pages.
