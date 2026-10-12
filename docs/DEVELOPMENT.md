# Development

## Build and test

Use JDK 21, the Android SDK with API 37.1 and the checked-in Gradle wrapper. Set `sdk.dir` in the ignored `local.properties` file; see [install](../README.md#install).

```sh
./gradlew :core:allTests :ui:jvmTest :ui:testAndroidHostTest :ui:jsNodeTest :androidApp:lintDebug :androidApp:assembleRelease
```

`:core:allTests` runs the data layer's tests on the JVM, Android, Linux, js and wasm (on Node); Apple targets run theirs only on a Mac. The UI's common tests run on the JVM, as Android host tests and on JS; Compose's wasm test binary needs a browser, so the UI has no wasm test run. Before a change to common code, also compile the Native tests, which reject what the JVM accepts (commas or parentheses in backtick test names, JVM-only APIs):

```sh
./gradlew :core:compileTestKotlinLinuxX64 :ui:compileTestKotlinIosSimulatorArm64
```

Unit tests cover the API client, catalog and guide mapping, the playback session lifecycle, cache, settings, captions, remote-control key mapping, the launch and focus decisions, channel re-resolution, the preview's rest timers and network cancellation. Server interactions run against MockWebServer on the JVM, so no server is needed. `RenderSmokeTest` draws the guide, settings and the player's info bar headless at TV size and writes them to `ui/build/screenshots`.

## Run each platform

| Platform | Command | Needs |
| --- | --- | --- |
| Android TV, Google TV, phones | `./gradlew :androidApp:assembleRelease`, then sideload `androidApp/build/outputs/apk/release/androidApp-release.apk` | `adb` |
| Desktop | `./gradlew :ui:run`; `:ui:packageDistributionForCurrentOS` builds a `.deb`, `.dmg` or `.msi` | VLC 3 installed for playback |
| Web | `./gradlew :ui:wasmJsBrowserDevelopmentRun`; `:ui:wasmJsBrowserDistribution` writes `ui/build/dist/wasmJs/productionExecutable` (`js` tasks build the JS fallback) | A Sofa King server that answers the page's origin (CORS) and HLS streams |
| iOS | On a Mac: `cd iosApp && xcodegen && open SofaKing.xcodeproj`, then run the SofaKing scheme | Xcode, XcodeGen; HLS streams |

The iOS framework links only on macOS. Every other target, including the iOS klibs, builds on Linux.

## Test on a TV

Check these on a real Android TV or Google TV device before a change that touches playback, focus or the remote:

- [ ] Cold start opens the last watched channel, and Back shows the guide focused on it.
- [ ] The Guide, Menu and Search keys work (the launcher may claim Guide on some devices), and voice search finds a channel.
- [ ] D-pad focus moves through the guide without losing its place, including rapid focus changes over the preview, and the stream carries over between preview, player and guide.
- [ ] Focus is visible on every button, chip, switch and field, and long-press OK on a guide cell toggles the favorite.
- [ ] The preview is muted, unmutes at 5 seconds and opens full screen at 10 seconds, and captions show over it.
- [ ] Background and foreground transitions resume the same channel.
- [ ] Audio, captions and decoder recovery work on the full-screen player.

## Dependencies

Exact versions are in [the version catalog](../gradle/libs.versions.toml). The app version is `sofaking.version` in `gradle.properties`; Android's `versionName`, the desktop package and the About row read it. AGP supplies built-in Kotlin for the Android app; the Kotlin, AGP and Compose Multiplatform versions are preview pins that move together. Resolved dependencies are locked per module, the Kotlin/JS yarn lockfiles live in `kotlin-js-store`, and the wrapper has a pinned checksum.

For dependency updates, change the version catalog, run the checks above with `--write-locks` (and `kotlinUpgradeYarnLock kotlinWasmUpgradeYarnLock` for web dependencies), and review the lockfile diff. Run the same checks without that flag before committing.

Release APKs use the local debug key. Distribution requires a release signing configuration.

## Banner

Regenerate the TV banner after changing the vector mark. Requires ImageMagick and Noto Sans:

```sh
tools/render-banner.sh
```

## Conventions

Use [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) and the constraints in [CLAUDE.md](../CLAUDE.md). Keep current behavior in documentation and change rationale in commits or pull requests.
