# Development

The [contributing guide](../CONTRIBUTING.md) covers commit and pull request conventions. This page covers the build, the tests, running each platform, the checks on a TV and snapshot builds.

## Build and test

Use JDK 21, the Android SDK with API 37.1 and the checked-in Gradle wrapper. Point the build at the SDK in the ignored `local.properties` file:

```sh
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
```

Run from the repository root:

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
| Web | `./gradlew :ui:wasmJsBrowserDevelopmentRun`; `:ui:wasmJsBrowserDistribution` writes `ui/build/dist/wasmJs/productionExecutable` (`js` tasks build the JS fallback) | The page on the server's origin, through a reverse proxy that forwards `/api/` and `/media/`; HLS streams |
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

## Signing

Release APKs are minified with R8 and signed with the local debug key, so they are for testing. Distributing one needs a release signing configuration.

## CI and snapshots

[CONTRIBUTING](../CONTRIBUTING.md#build-and-test) describes the CI jobs. A push to `main` that passes them runs `snapshot.yml`, which builds the APK, both web bundles and the desktop package on Linux, macOS and Windows, and replaces the `snapshot` prerelease. Run `snapshot.yml` from the Actions tab to publish without a push.

Snapshots pass `-Psofaking.versionCode` (the workflow run number) and `-Psofaking.versionSuffix` (`-dev.N+sha`); Android's `versionName` and the About row append the suffix, and the desktop package keeps the plain version. Set the `ANDROID_DEBUG_KEYSTORE` repository secret to a base64 debug keystore so every snapshot APK installs over the last:

```sh
base64 -w0 ~/.android/debug.keystore | gh secret set ANDROID_DEBUG_KEYSTORE --repo jamesarich/sofa-king
```

## Banner

Regenerate the TV banner after changing the vector mark. Requires ImageMagick and Noto Sans:

```sh
tools/render-banner.sh
```

## Dependencies

Version changes follow [dependencies](dependencies.md#changing-versions). Run the checks with `--write-locks` after a change, review the lockfile diff, and run them again without it.
