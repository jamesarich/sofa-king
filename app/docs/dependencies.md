# Dependencies

| Component | Version |
| --- | --- |
| Kotlin | 2.5.0-Beta1 |
| Android Gradle plugin | 9.5.0-alpha09 |
| Compose Multiplatform | 1.13.0-alpha02 |
| Material 3 Adaptive | 1.3.0 |
| kotlinx.coroutines | 1.11.0 |
| kotlinx.serialization | 1.12.0-RC |
| Ktor | 3.6.0 |
| DataStore | 1.3.0-rc01 |
| Coil | 3.6.3 |
| Media3 | 1.11.1 |
| vlcj | 4.8.3 |
| Gradle | 9.9.0-milestone-2 |
| Foojay toolchain resolver | 1.0.0 |
| JDK toolchain | 21 |
| Android compile SDK | 37.1 |

Versions are defined in [the version catalog](../../gradle/libs.versions.toml) and [the Gradle wrapper settings](../gradle/wrapper/gradle-wrapper.properties). Libraries come from Google's Maven repository and Maven Central. Plugins resolve from those two and the Gradle Plugin Portal, as `pluginManagement` in `settings.gradle.kts` declares. The Node, Yarn and Binaryen toolchains for the web targets resolve through the repositories in [the shared repositories script](../../gradle/repositories.settings.gradle.kts), which every build applies.

Kotlin, the Android Gradle plugin and Compose Multiplatform are preview pins that move together. The combination is outside the published compatibility matrices, so a bump of one usually needs the others. The Android app uses the plugin's built-in Kotlin. The JVM and Android targets compile for Java 21 with the JDK 21 toolchain, and `gradle/gradle-daemon-jvm.properties` runs the Gradle daemon on JDK 21 too.

The app, the server and the [contract](../../contract/README.md) share that one catalog, so a Kotlin or kotlinx.serialization bump moves all three in one pull request, with no other files to regenerate.

Every version in the catalog is exact, and the wrapper has a pinned distribution checksum. Neither Gradle's resolved versions nor the Kotlin/JS and Kotlin/Wasm Yarn lockfiles are kept: Gradle resolves the pinned versions the same way on every machine, and Kotlin writes the Yarn lockfiles to the ignored `kotlin-js-store` on each build.

The app version is `sofaking.version` in `gradle.properties`. Android's `versionName`, the desktop package and the About row read it; snapshot builds add a suffix, as [CI and snapshots](development.md#ci-and-snapshots) describes.

## Changing versions

[Renovate](../../renovate.json) proposes updates for the version catalog, the Gradle wrappers, the workflow actions and the server's base images, with Kotlin, the Android Gradle plugin and Compose Multiplatform in one pull request. It follows Kotlin, kotlinx.serialization and kotlinx.coroutines prereleases, and lists every pending update on its Dependency Dashboard issue. Renovate merges a minor, patch or digest update at least three days old once every check on the pull request's latest commit passes. Kotlin, the Android Gradle plugin, Compose Multiplatform and major updates wait for a person: check them as in steps 2 to 6 below before merging. The JDK and the Ubuntu runner image wait for approval on the dashboard.

By hand:

1. Update the exact versions in [the version catalog](../../gradle/libs.versions.toml).
2. Run the [build and test](development.md#build-and-test) checks.
3. For a change to playback, captions or the remote, run the [checks on a TV](development.md#test-on-a-tv).
4. For a wrapper update, pin the official distribution checksum in `gradle-wrapper.properties`.
5. For a Kotlin or kotlinx.serialization bump, change the same pins in `contract/` and `backend/`, and follow the [contract's](../../contract/README.md) and the [server's](../../backend/docs/dependencies.md#changing-versions) update steps.
6. Update the table above.

Published versions are listed on the [Kotlin](https://kotlinlang.org/docs/releases.html), [Android Gradle plugin](https://developer.android.com/build/releases/gradle-plugin), [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/releases) and [Gradle distribution](https://services.gradle.org/distributions/) pages.
