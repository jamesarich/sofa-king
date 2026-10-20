# Dependencies

Every library and plugin version is an exact pin in [the version catalog](../../gradle/libs.versions.toml), which this table does not repeat. The versions the catalog does not hold:

| Component | Version |
| --- | --- |
| Gradle | 9.9.0-milestone-2 |
| Foojay toolchain resolver | 1.0.0 |
| JDK toolchain | 21 |
| Android compile SDK | 37.1 |

The wrapper's version is in [the Gradle wrapper settings](../gradle/wrapper/gradle-wrapper.properties) and the SDK levels in `build-logic`. Libraries come from Google's Maven repository and Maven Central. Plugins resolve from those two and the Gradle Plugin Portal, as `pluginManagement` in `settings.gradle.kts` declares. The Node, Yarn and Binaryen toolchains for the web targets resolve through the repositories in [the shared repositories script](../../gradle/repositories.settings.gradle.kts), which every build applies.

The core Material icon set (`material-icons-core`) is published apart from the rest of Compose Multiplatform and stopped at 1.7.3, so its pin does not move with the Compose Multiplatform pin; Gradle raises its Compose dependencies to the catalog's versions. The extended set stays out: the Kotlin/JS production build took over twenty minutes to minify it, so the icons outside the core set live in `ui/.../theme/SofaIcons.kt`.

Kotlin, the Android Gradle plugin and Compose Multiplatform are preview pins that move together. The combination is outside the published compatibility matrices, so a bump of one usually needs the others. The Android app uses the plugin's built-in Kotlin. The JVM and Android targets compile for Java 21 with the JDK 21 toolchain, and `gradle/gradle-daemon-jvm.properties` runs the Gradle daemon on JDK 21 too.

The app, the server and the [contract](../../contract/README.md) share that one catalog, so a Kotlin or kotlinx.serialization bump moves all three in one pull request, with no other files to regenerate.

Every version in the catalog is exact, and the wrapper has a pinned distribution checksum. Neither Gradle's resolved versions nor the Kotlin/JS and Kotlin/Wasm Yarn lockfiles are kept: Gradle resolves the pinned versions the same way on every machine, and Kotlin writes the Yarn lockfiles to the ignored `kotlin-js-store` on each build.

The app version is `sofaking` in the catalog's `[versions]`, shared with the contract and the server. Android's `versionName`, the desktop package, the Swift hosts' `MARKETING_VERSION` (through `tools/version-xcconfig.sh`, which XcodeGen runs) and the About row read it; snapshot builds add a suffix, as [CI and snapshots](development.md#ci-and-snapshots) describes.

## Changing versions

[Renovate](../../renovate.json) proposes updates for the version catalog, the Gradle wrappers, the workflow actions and the server's base images, with Kotlin, the Android Gradle plugin and Compose Multiplatform in one pull request. It follows Kotlin, kotlinx.serialization and kotlinx.coroutines prereleases, and lists every pending update on its Dependency Dashboard issue. Renovate merges a minor, patch or digest update at least three days old once every check on the pull request's latest commit passes. Kotlin, the Android Gradle plugin, Compose Multiplatform and major updates wait for a person: check them as in steps 2 to 6 below before merging. The JDK and the Ubuntu runner image wait for approval on the dashboard.

By hand:

1. Update the exact versions in [the version catalog](../../gradle/libs.versions.toml).
2. Run the [build and test](development.md#build-and-test) checks.
3. For a change to playback, captions or the remote, run the [checks on a TV](development.md#test-on-a-tv).
4. For a wrapper update, pin the official distribution checksum in `gradle-wrapper.properties`.
5. For a Kotlin or kotlinx.serialization bump, which the one catalog moves for `contract/` and `backend/` too, follow the [contract's](../../contract/README.md) and the [server's](../../backend/docs/dependencies.md#changing-versions) update steps as well.
6. For a wrapper, JDK or SDK change, update the table above.

Published versions are listed on the [Kotlin](https://kotlinlang.org/docs/releases.html), [Android Gradle plugin](https://developer.android.com/build/releases/gradle-plugin), [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/releases) and [Gradle distribution](https://services.gradle.org/distributions/) pages.
