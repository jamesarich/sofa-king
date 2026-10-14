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

The app, the server and the [contract](../../contract/README.md) share that one catalog, so a Kotlin or kotlinx.serialization bump moves all three in one pull request, with each build's lockfiles. The contract keeps its own lockfiles and verification metadata, as its README describes.

Resolved dependencies are locked per project in `gradle.lockfile`, the root build's plugin classpath in `buildscript-gradle.lockfile`, and the settings script's in `settings-gradle.lockfile`. The Kotlin/JS and Kotlin/Wasm Yarn lockfiles are in `kotlin-js-store`. The wrapper has a pinned distribution checksum.

The app version is `sofaking.version` in `gradle.properties`. Android's `versionName`, the desktop package and the About row read it; snapshot builds add a suffix, as [CI and snapshots](development.md#ci-and-snapshots) describes.

## Changing versions

[Renovate](../../renovate.json) proposes updates for the version catalog, the Gradle wrappers, the workflow actions and the server's base images, with Kotlin, the Android Gradle plugin and Compose Multiplatform in one pull request. It follows Kotlin, kotlinx.serialization and kotlinx.coroutines prereleases, and lists every pending update on its Dependency Dashboard issue. The [lockfiles workflow](../../CONTRIBUTING.md#ci) then commits the new lockfiles to the pull request and runs CI. Check the result as in steps 3 to 7 below before merging.

By hand:

1. Update the exact versions in [the version catalog](../../gradle/libs.versions.toml).
2. Run `gradle/update-locks.sh` from the repository root, on macOS: it rewrites the lockfiles of build-logic, the contract, the app and the server, including the Apple and web configurations and the Yarn lockfiles. On Linux the Apple configurations keep their old lock state. `GRADLEW` overrides the Gradle command.
3. Review the lockfile changes, then run the [build and test](development.md#build-and-test) checks.
4. For a change to playback, captions or the remote, run the [checks on a TV](development.md#test-on-a-tv).
5. For a wrapper update, pin the official distribution checksum in `gradle-wrapper.properties`.
6. For a Kotlin or kotlinx.serialization bump, change the same pins in `contract/` and `backend/`, and follow the [contract's](../../contract/README.md) and the [server's](../../backend/docs/dependencies.md#changing-versions) update steps.
7. Update the table above.

Published versions are listed on the [Kotlin](https://kotlinlang.org/docs/releases.html), [Android Gradle plugin](https://developer.android.com/build/releases/gradle-plugin), [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/releases) and [Gradle distribution](https://services.gradle.org/distributions/) pages.
