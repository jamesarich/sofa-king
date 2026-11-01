# Dependencies

Every library and plugin version is an exact pin in [the version catalog](../../gradle/libs.versions.toml), which this table does not repeat. The versions the catalog does not hold:

| Component | Version |
| --- | --- |
| Gradle | 9.9.0-rc-1 |
| Foojay toolchain resolver | 1.0.0 |
| JDK toolchain | 21 |
| Android compile SDK | 37.1 |

The wrapper's version is in [the Gradle wrapper settings](../gradle/wrapper/gradle-wrapper.properties) and the SDK levels in `build-logic`. Libraries come from Google's Maven repository and Maven Central. Plugins resolve from those two and the Gradle Plugin Portal, as `pluginManagement` in `settings.gradle.kts` declares. The Node, Yarn and Binaryen toolchains for the web targets resolve through the repositories in [the shared repositories script](../../gradle/repositories.settings.gradle.kts), which every build applies.

The core Material icon set (`material-icons-core`) is published apart from the rest of Compose Multiplatform and stopped at 1.7.3, so its pin does not move with the Compose Multiplatform pin; Gradle raises its Compose dependencies to the catalog's versions. The extended set stays out, since minifying it slows the Kotlin/JS production build badly; the icons outside the core set live in `ui/.../theme/SofaIcons.kt`.

Kotlin, the Android Gradle plugin and Compose Multiplatform are preview pins that move together. The combination is outside the published compatibility matrices, so a bump of one usually needs the others. The Android app uses the plugin's built-in Kotlin. The JVM and Android targets compile for Java 21 with the JDK 21 toolchain, and `gradle/gradle-daemon-jvm.properties` runs the Gradle daemon on JDK 21 too.

The screenshot tests run the Compose Preview Screenshot Testing engine (`screenshot` in the catalog) through the Android Gradle plugin's test suites, both experimental; the `android.experimental.enableScreenshotTest` and `android.experimental.testSuiteSupport` properties in `gradle.properties` turn them on. A bump of the engine, the plugin or Compose can change the renders: run `:androidApp:updateDebugScreenshotTestDefaultTestSuite` and review the changed reference images in the pull request.

The app, the server and the [contract](../../contract/README.md) share that one catalog, so a Kotlin or kotlinx.serialization bump moves all three in one pull request, with no other files to regenerate.

The open-source licenses page comes from [AboutLibraries](https://github.com/mikepenz/AboutLibraries) (`aboutlibraries` in the catalog, one version for its Gradle plugin and its Compose UI). The plugin's `exportLibraryDefinitions` task in `ui` lists every library each target resolves, tagged with the targets that use it, and downloads the license texts from SPDX, so its first run needs the network; the build cache keeps the result. The build syncs the list into the common resources as `files/aboutlibraries.json`, so a dependency change updates the page with nothing to regenerate by hand.

Google TV's Continue watching row comes from the Engage SDK (`engage-tv`), Google's closed-source library, which brings Play services' Tasks and basement, WorkManager and Joda-Time. It writes the Watch Next row itself on an Android TV launcher that reads it, so it overrides `tvprovider`'s minimum SDK. Play services pins Fragment 1.1.0, whose activity results Android lint refuses, so the app depends on a current `androidx-fragment`.

The visualizer's classic pack, `ui/src/commonMain/composeResources/files/presets.txt`, is 1,089 MilkDrop presets from the [projectM classic pack](https://github.com/projectM-visualizer/presets-projectm-classic) (`.milk` files by many authors, in folders such as bltc201, eyetune, tryptonaut and yin), stripped to the values and equations the engine uses and written in one text file. The pack carries no license text. Its maintainers' stance, which the sister Cream of the Crop pack states, is that the authors released their presets without a license and it is safe to assume they are public domain; Sofa King bundles them on that footing, and the presets stay their authors' work. An author who wants a preset out of the app opens an issue naming it, and the next release drops it. [Development](development.md#classic-visualizer-pack) says how the file is made.

The Kotlin/JS and Kotlin/Wasm Yarn lockfiles are not kept: Kotlin writes them to the ignored `kotlin-js-store` on each build.

The app version is `sofaking` in the catalog's `[versions]`, shared with the contract and the server. Android's `versionName`, the desktop package, the Swift hosts' `MARKETING_VERSION` (through `tools/version-xcconfig.sh`, which XcodeGen runs) and the About row read it; snapshot builds add a suffix, as [CI and snapshots](development.md#ci-and-snapshots) describes.

## Changing versions

The pin policy and Renovate are in [CONTRIBUTING](../../CONTRIBUTING.md#dependencies); Kotlin, the Android Gradle plugin, Compose Multiplatform and major updates wait for a person, who checks them as follows. After the catalog edit:

1. Run the [build and test](development.md#build-and-test) checks.
2. For a change to playback, captions or the remote, run the [checks on a TV](development.md#test-on-a-tv).
3. For a wrapper update, pin the official distribution checksum in `gradle-wrapper.properties`.
4. For a Kotlin or kotlinx.serialization bump, which the one catalog moves for `contract/` and `backend/` too, follow the [contract's](../../contract/README.md) and the [server's](../../backend/docs/dependencies.md#changing-versions) update steps as well.
5. For a wrapper, JDK or SDK change, update the table above.

Published versions are listed on the [Kotlin](https://kotlinlang.org/docs/releases.html), [Android Gradle plugin](https://developer.android.com/build/releases/gradle-plugin), [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/releases) and [Gradle distribution](https://services.gradle.org/distributions/) pages.
