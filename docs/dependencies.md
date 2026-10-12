# Dependencies

| Component | Version |
| --- | --- |
| Kotlin | 2.5.0-Beta1 |
| Ktor | 3.6.0-eap-1682 |
| kotlinx.serialization | 1.12.0-RC |
| kotlinx.coroutines | 1.11.0 |
| Exposed | 1.5.1 |
| SQLite JDBC | 3.53.4.0 |
| SLF4J | 2.0.18 |
| kotlinx.html | 0.12.0 |
| htmx | 2.0.11 |
| Gradle | 9.9.0-milestone-2 |
| JDK toolchain | 21 |

Versions are defined in [the version catalog](../gradle/libs.versions.toml) and [Gradle wrapper settings](../gradle/wrapper/gradle-wrapper.properties). Resolved dependencies are locked. Ktor artifacts come from the official JetBrains EAP repository; other libraries come from Maven Central.

The server targets JVM 21. The `api-contract` and `client` modules compile to JVM 17 bytecode with the Kotlin 2.5 beta compiler, so consumers need a compatible Kotlin compiler.

## Vendored htmx

The [admin portal](admin.md) serves htmx from `server/src/main/resources/admin/htmx.min.js`, the `dist/htmx.min.js` of the npm package `htmx.org` 2.0.11, unchanged. Its SHA-256 is `d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717`. To update it, take `dist/htmx.min.js` from the new release's npm tarball, check the tarball against the registry's `integrity` value, and record the new version and SHA-256 here.

## Changing versions

1. Update the exact versions in [the version catalog](../gradle/libs.versions.toml).
2. Run the build with `--write-locks` and review the lockfile changes.
3. Run both smoke scripts, as described in [development](development.md#build-and-test).
4. For a wrapper update, pin the official distribution checksum in `gradle-wrapper.properties`.

Published versions are listed on the official [Kotlin EAP](https://kotlinlang.org/docs/whatsnew-eap.html), [Ktor EAP](https://ktor.io/eap/) and [Gradle distribution](https://services.gradle.org/distributions/) pages.
