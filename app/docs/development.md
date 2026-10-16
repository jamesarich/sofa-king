# Development

The [contributing guide](../../CONTRIBUTING.md) covers the commit and pull request conventions and CI. This page covers the app's build, its tests, its code conventions, running each platform, the checks on a TV and snapshot builds.

## Build and test

Use JDK 21, the Android SDK with API 37.1 and the checked-in Gradle wrapper. The app's build lives in `app/`; run every command on this page from there. Point the build at the SDK in the ignored `local.properties` file:

```sh
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
```

Then build and test:

```sh
./gradlew :core:allTests :ui:jvmTest :ui:testAndroidHostTest :ui:jsNodeTest :androidApp:lintDebug :androidApp:assembleRelease
```

`:core:allTests` runs the data layer's tests on the JVM, Android, Linux, js and wasm (on Node); Apple targets run theirs only on a Mac. The UI's common tests run on the JVM, as Android host tests and on JS; Compose's wasm test binary needs a browser, so the UI has no wasm test run. Before a change to common code, also compile the Native tests, which reject what the JVM accepts (commas or parentheses in backtick test names, JVM-only APIs):

```sh
./gradlew :core:compileTestKotlinLinuxX64 :ui:compileTestKotlinIosSimulatorArm64
```

Unit tests cover the API client, catalog and guide mapping, the playback session lifecycle, cache, settings, captions, remote-control key mapping, the launch and focus decisions, channel re-resolution, the preview's rest timers and network cancellation. Server interactions run against Ktor's `MockEngine` in common code on every target, and against MockWebServer on the JVM where a real socket matters, so no server is needed. `RenderSmokeTest` draws the guide, settings and the player's info bar headless at TV size and writes them to `ui/build/screenshots`.

## Code

Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) and the constraints in [AGENTS.md](../AGENTS.md). Put code in `commonMain` unless it needs a platform API, keep `core` free of Compose, preserve coroutine cancellation, and close the sessions, players and HTTP calls you own. [Architecture](architecture.md) describes how the code is organized.

## Run each platform

| Platform | Command | Needs |
| --- | --- | --- |
| Android TV, Google TV, phones | `./gradlew :androidApp:assembleRelease`, then sideload `androidApp/build/outputs/apk/release/androidApp-release.apk` | `adb` |
| Desktop | `./gradlew :ui:run`; `:ui:packageDistributionForCurrentOS` builds a `.deb`, `.dmg` or `.msi` | VLC 3 installed for playback |
| Web | `./gradlew :ui:wasmJsBrowserDevelopmentRun`; `:ui:wasmJsBrowserDistribution` writes `ui/build/dist/wasmJs/productionExecutable` (`js` tasks build the JS fallback) | The page's origin allowed by the server, as [getting started](getting-started.md#web) describes; HLS streams |
| iOS | On a Mac: `cd iosApp && xcodegen && open SofaKing.xcodeproj`, then run the SofaKing scheme | Xcode, XcodeGen; HLS streams |
| Apple TV | On a Mac: `cd tvosApp && xcodegen && open SofaKingTV.xcodeproj`, then run the SofaKingTV scheme | Xcode, XcodeGen; HLS streams |

The iOS framework links only on macOS. Every other target, including the iOS klibs, builds on Linux.

## Test on a TV

The tests cannot see focus, the remote's keys or a real decoder. Check these on a real Android TV or Google TV device before a change that touches playback, focus or the remote:

- [ ] Cold start opens the last watched channel, and Back shows the guide focused on it.
- [ ] The Guide, Menu and Search keys work (the launcher may claim Guide on some devices), and voice search finds a channel.
- [ ] D-pad focus moves through the guide without losing its place, including rapid focus changes over the preview, and the stream carries over between preview, player and guide.
- [ ] Focus is visible on every button, chip, switch and field, including the Recent chip and a Later today line under a search result.
- [ ] Info or a held OK on a guide cell opens the program details beside the grid with Watch focused; Favorite toggles the star, Back and Close return focus to the cell.
- [ ] Fast Forward moves the guide a day ahead with the same channel focused, Rewind and Now return, and the day's programs fill in.
- [ ] The preview is muted, unmutes at 5 seconds and opens full screen at 10 seconds, and captions show over it.
- [ ] Background and foreground transitions resume the same channel.
- [ ] Audio, captions and decoder recovery work on the full-screen player.
- [ ] In the player, Left or Right on the info bar opens the channel list, Up from its top row reaches the chips and Left/Right move along them, and OK on a row under All tunes a channel outside the guide filter.
- [ ] CC then Left/Right moves the channel's caption timing both ways and the toast follows; Info held opens the stream card, and Up/Down and OK pick an audio track on a channel with several.

On an Apple TV (the simulator does not show the Top Shelf of a sideloaded app reliably):

- [ ] Holding the touch surface on a channel opens its program details, and Watch plays it.
- [ ] The player's transport bar menu toggles the favorite and switches channels from Recent, Favorites and All channels.
- [ ] After watching a few channels, the Top Shelf shows them newest first, and selecting one opens the app on it; the app and the extension must be signed with the `group.dev.jamesrich.sofaking` app group for the list to reach the extension.

## Baseline Profile

The release APK ships a Baseline Profile, so Android compiles the code a cold start, the guide and a channel change run ahead of time instead of interpreting it on the first launches. `:baselineprofile` records it with Macrobenchmark's `BaselineProfileRule` in two runs, the cold start alone for the startup profile that lays out the first dex file and the guide-and-zap journey for the rest, and the `androidx.baselineprofile` plugin writes both files under `androidApp/src/release/generated/baselineProfiles`. CI does not regenerate them.

Regenerate it after a large change to startup, the guide or the player, with one device connected over `adb` that already has the app installed and its server set up, since the journey needs a lineup to draw:

```sh
./gradlew :androidApp:generateBaselineProfile
```

The device needs Android 13 or later, or root. The run installs the generator's build over the existing app, which must therefore be signed with the same key, and `android.injected.androidTest.leaveApksInstalledAfterRun` in `gradle.properties` keeps it installed afterwards, with its settings. Commit the regenerated profile files.

## Signing

Release APKs are minified with R8 and signed with the local debug key, so they are for testing. Distributing one needs a release signing configuration.

## CI and snapshots

[CONTRIBUTING](../../CONTRIBUTING.md#ci) describes the CI jobs. A push to `main` that passes them runs `snapshot.yml`, which builds the APK, both web bundles and the desktop package on Linux, macOS and Windows, and replaces the `snapshot` prerelease. Run `snapshot.yml` from the Actions tab to publish without a push.

The app version is `sofaking` in [the version catalog](../../gradle/libs.versions.toml). Snapshots build every package with `-Psofaking.versionCode` (the workflow run number) and `-Psofaking.versionSuffix` (`-dev.RUN+COMMIT`, the run number and the short commit). Android's `versionName` and the About row of every snapshot build append the suffix. Only the catalog version is compiled in; the suffix is written to `files/version-suffix.txt` among the UI module's Compose resources and read at runtime, so a snapshot reuses the compile outputs the CI run just cached. Every desktop package's own version is the plain version with a leading `0.` mapped to `1.`, so 0.1.0 packages as 1.1.0.

Set the `ANDROID_DEBUG_KEYSTORE` repository secret to a base64 debug keystore so every snapshot APK installs over the last:

```sh
base64 -w0 ~/.android/debug.keystore | gh secret set ANDROID_DEBUG_KEYSTORE --repo jamesarich/sofa-king
```

## Banner

Regenerate the TV banner after changing the vector mark. Requires ImageMagick and Noto Sans:

```sh
tools/render-banner.sh
```

## Dependencies

Version changes follow [dependencies](dependencies.md#changing-versions). Run the checks after a change.
