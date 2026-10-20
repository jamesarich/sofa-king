# Development

The [contributing guide](../../CONTRIBUTING.md) covers the commit and pull request conventions and CI. This page covers the app's build, its tests, Android Studio, its code conventions, running each platform, the checks on a TV and snapshot builds.

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
./gradlew :core:compileTestKotlinLinuxX64 :core:compileKotlinLinuxArm64 :ui:compileTestKotlinIosSimulatorArm64
```

Unit tests cover the API client, catalog and guide mapping, the server connection's bootstrap backoff and server changes, the playback session lifecycle, cache, settings, the Apple TV's settings storage and its move out of Caches (`tvosTest`, which only macOS runs), captions, remote-control key mapping, the launch and focus decisions, channel re-resolution, the preview's rest timers and network cancellation. Server interactions run against Ktor's `MockEngine` in common code on every target, and against MockWebServer on the JVM where a real socket matters, so no server is needed. Screen tests in `ui`'s `jvmTest` drive the composables with Compose's ui-test (`runSkikoComposeUiTest`), pressing keys and finding nodes by semantics on a virtual clock. `RenderSmokeTest` draws the guide, settings, the settings lock page, the player's info bar and its channel list headless at TV size and writes them to `ui/build/screenshots`.

## Android Studio

Open the repository root, not `app/`. The root `settings.gradle.kts` imports the app, the server and the contract as one composite build named `sofa-king-monorepo`, and the root `gradle.properties` holds the Gradle settings the IDE's sync uses. Use an Android Studio release that supports the catalog's `agp` version, with its bundled Kotlin Multiplatform plugin enabled, and the SDK in `app/local.properties` as above.

- **Gradle JDK.** The Gradle daemon runs on JDK 21, which `gradle/gradle-daemon-jvm.properties` asks for; Gradle downloads one if the machine has none. If Settings → Build, Execution, Deployment → Build Tools → Gradle offers a Gradle JDK, pick a JDK 21 (Studio's own `jbr-21` will do).
- **Run.** After the first sync, Studio adds an `androidApp` configuration; run it on a TV, a phone or an emulator. The `.run/` folder adds **Desktop**, which runs `:ui:run`, and **Web**, which runs `:ui:wasmJsBrowserDevelopmentRun`. iOS and Apple TV run from Xcode, as the next section says.
- **Previews.** Compose previews in `androidApp` need its unit tests enabled with Android resources; `androidApp/build.gradle.kts` keeps both, and the sync fails without them.

When a sync fails, read the error in the Build window's Sync tab; Studio's `idea.log` keeps only "Unknown cause". Most sync failures also show from the command line, because a sync registers every task, so run `./gradlew tasks --all` from the repository root. A sync creates every task of every project, and two things keep that from tripping over the Kotlin plugin, which creates more tasks from inside some tasks' configuration: the `sofaking.kotlin` convention creates each project's tasks up front when the `idea.sync.active` system property is set, and `org.gradle.tooling.parallel` stays off in every `gradle.properties`. Without them the sync failed with a `ConcurrentModificationException` while creating `:app:rootPackageJson` or `:app:ui:jvmTest`. If the Project view lists modules with a `~1` suffix, Studio still holds modules from an older import; choose File → Invalidate Caches, restart and sync again.

## Code

Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) and the constraints in [AGENTS.md](../AGENTS.md). Put code in `commonMain` unless it needs a platform API, keep `core` free of Compose, preserve coroutine cancellation, and close the sessions, players and HTTP calls you own. [Architecture](architecture.md) describes how the code is organized. Every Kotlin compile fails on a compiler warning (`sofaking.warningsAsErrors` in `gradle.properties`), so fix a warning or opt in to the API it names rather than leaving it.

## Run each platform

| Platform | Command | Needs |
| --- | --- | --- |
| Android TV, Google TV, phones | `./gradlew :androidApp:assembleRelease`, then sideload `androidApp/build/outputs/apk/release/androidApp-release.apk` | `adb` |
| Desktop | `./gradlew :ui:run`; `:ui:packageDistributionForCurrentOS` builds a `.deb`, `.dmg` or `.msi` under `ui/build/compose/binaries/main/` | VLC 3 installed for playback |
| Web | `./gradlew :ui:wasmJsBrowserDevelopmentRun`; `:ui:wasmJsBrowserDistribution` writes `ui/build/dist/wasmJs/productionExecutable` (`js` tasks build the JS fallback) | The page's origin allowed by the server, as [getting started](getting-started.md#web) describes; HLS streams |
| iOS | On a Mac: `cd iosApp && xcodegen && open SofaKing.xcodeproj`, then run the SofaKing scheme | Xcode, XcodeGen; HLS streams |
| Apple TV | On a Mac: `cd tvosApp && xcodegen && open SofaKingTV.xcodeproj`, then run the SofaKingTV scheme | Xcode, XcodeGen; HLS streams |

The iOS framework links only on macOS. Every other target, including the iOS klibs, builds on Linux.

Both Swift hosts take their version from the catalog's `sofaking` through `tools/version-xcconfig.sh`, declare the Entertainment App Store category and no non-exempt encryption in `project.yml`, and have no asset catalog yet, so they run with no app icon. A release build needs `Assets.xcassets` with the iOS `AppIcon` (1024x1024) and, on tvOS, the App Icon & Top Shelf Image stack (icon layers at 400x240 and 1280x768, Top Shelf images at 1920x720 and 2320x720, a 1920x1080 launch image), then `ASSETCATALOG_COMPILER_APPICON_NAME` in each `project.yml`.

## Test on a TV

The tests cannot see focus, the remote's keys or a real decoder. Check these on a real Android TV or Google TV device before a change that touches playback, focus or the remote:

- [ ] Cold start opens the last watched channel, and Back shows the guide focused on it. Leave the app from the Library tab and start it again: it opens on Library with nothing playing.
- [ ] The Guide, Menu and Search keys work (the launcher may claim Guide on some devices), and voice search finds a channel.
- [ ] D-pad focus moves through the guide without losing its place, including rapid focus changes over the preview, and the stream carries over between preview, player and guide.
- [ ] Focus is visible on every button, chip, switch and field, including the Recent chip and a Later today line under a search result.
- [ ] Info or a held OK on a guide cell opens the program details beside the grid with Watch focused, and releasing the held OK does not play the channel; Favorite toggles the star, Back and Close return focus to the cell.
- [ ] Fast Forward moves the guide a day ahead with the same channel focused, Rewind and Now return, and the day's programs fill in.
- [ ] The preview is muted, unmutes at 5 seconds and opens full screen at 10 seconds, and captions show over it. With music playing from another app, the muted preview leaves it playing and the preview's sound stops it.
- [ ] Background and foreground transitions resume the same channel.
- [ ] Audio, captions and decoder recovery work on the full-screen player. Previous and Next on a remote that has them step the channel number down and up.
- [ ] In the player, Left or Right on the info bar opens the channel list, Up from its top row reaches the chips and Left/Right move along them to the star, Captions, Pause and Stream info buttons, and OK on a row under All tunes a channel outside the guide filter.
- [ ] With the remote's CC, Play/Pause and Info keys unused: the list's Captions button toggles captions and closes the list with the timing toast up, Pause pauses, the star changes the Favorites chip's rows, and Stream info opens the stream card with the playing audio track focused; OK on a card without tracks closes it.
- [ ] CC then Left/Right moves the channel's caption timing both ways and the toast follows; Info held opens the stream card, and Up/Down and OK pick an audio track on a channel with several.
- [ ] Settings → Open on start set to Guide opens the guide on a cold start, Library opens the Library tab, and A channel with one chosen opens that channel; Save & reload is off until the server fields change.
- [ ] Settings → Manage channels: a group switched off empties its chip in the guide and its channels leave the channel list; the arrow opens the group's channels and one switched back on returns alone; hidden channels leave Channel Up/Down only once Skip hidden channels is on; the move buttons reorder the Favorites filter.
- [ ] Settings opens on its first setting with no Back button drawn and Down walks General, Live TV, Recordings, Captions, Playback, Server; the header shows only the tabs and Settings. Settings → Server → Reload shows Reloading while the lineup loads. Settings → Recordings → Skip commercials jumps a recording's breaks.
- [ ] Settings → Time format 24-hour and Guide rows Compact change the guide at once; Open system caption settings opens the TV's captioning page.
- [ ] Settings → Lock → Set a PIN, Back, open Settings: the lock page focuses the PIN field; a wrong PIN says so and the fifth starts a 30 s wait; the right PIN opens Settings; Forgot the PIN with the server's API token removes it.
- [ ] Settings → Match frame rate on: a 29.97 or 25 fps channel switches the TV's refresh rate (the panel may blank for a moment) and the launcher's rate returns on Back; Decoder Software and Buffer Smoother still play the channel.
- [ ] Up from the guide's chips reaches the Live and Library tabs and Settings; OK on Library opens it with the tab still focused, Down enters the rows, Up from the top row or a grid's chips returns to the open tab, and Back returns to Live.
- [ ] Back from the guide's grid reaches the chips, then the Live tab, and the next Back exits the app at once. Settings opened from Library or Recordings returns there on Back. On Library and Recordings, Back from the page focuses the open tab in the header, and a second Back opens Live with focus on the Live tab and no preview playing; a third exits. Down from there focuses the guide on the last channel and starts the preview. The Guide key on a Library page opens the guide at Now. Back from a recording focuses that recording, Back to a tab scrolls its page to the top, and a cold start into Library or an empty Recordings focuses the tab.
- [ ] On the Library tab, Down reaches Search and the library chips; Search takes typed and spoken queries (the Search key starts it by voice), OK on a result opens its page and Back returns to the results, then to the Search chip.
- [ ] In the Library, OK on a card opens its page with Play or Resume focused, Play on the remote plays a movie straight from its card, a library's grid sorts and filters from its chips and loads more as it scrolls, and Back from a page or a grid focuses the card or library left.
- [ ] A show's page opens with its title in view and on the season with the next episode; Down from the buttons lands on that season's chip, the chips switch the episode rows, and OK on an episode plays it, resuming where it stopped.
- [ ] In the library player, Left and Right scrub and apply once released, holding them jumps further, OK applies at once, Back cancels a scrub and then returns to the page with the episode focused; Home and back resumes where it was, and a far jump in a converted file restarts there.

On an Apple TV (the simulator does not show the Top Shelf of a sideloaded app reliably):

- [ ] The tab bar shows Live, Library (only for a server with libraries), Recordings (only for a server that records) and Settings; a Top Shelf channel opens on Live from any tab. Leave the app from Library and start it again: it opens on Library.
- [ ] In the Library, a card opens its page, play/pause on the remote plays a movie or episode straight from its card, and a library's grid sorts, filters and loads more as it scrolls.
- [ ] A show's page opens on the season with the next episode; selecting an episode plays it, resuming where it stopped, and its context menu starts it over or marks it watched.
- [ ] The library player resumes where the item stopped, scrubs in the transport bar, returns to the page at the end, and after Home and back picks up where it was.
- [ ] Holding the touch surface on a channel opens its program details, and Watch plays it.
- [ ] The player's transport bar menu toggles the favorite and switches channels from Recent, Favorites and All channels.
- [ ] After watching a few channels, the Top Shelf shows them newest first, and selecting one opens the app on it; the app and the extension must be signed with the `group.dev.jamesrich.sofaking` app group for the list to reach the extension.

### Over adb

Focus, keys and playback can be probed from a computer connected to the TV with `adb`:

- Send remote keys with `adb shell input keyevent`, by name: `KEYCODE_DPAD_DOWN`, `KEYCODE_DPAD_CENTER`, `KEYCODE_BACK`, `KEYCODE_GUIDE`.
- Find what has focus in `adb shell uiautomator dump` (the node with `focused="true"`), or with Google's `android` command-line tool, `android layout --flat`, which prints the screen's nodes as JSON with their `FOCUSED` state and center coordinates.
- Read the app's log with `adb logcat -s SofaKing`; the CLI has no logcat command.
- `adb exec-out screencap -p` captures the interface, but on some TVs the video is black in it, so judge playback from the log and the media session's playback state in `adb shell dumpsys media_session`, and sound from `adb shell dumpsys audio`.

`android layout` installs a helper app, `com.android.cli.interact.instrumentation`, and leaves it running, which kills `uiautomator dump`. Stop it before using `uiautomator` again:

```sh
adb shell am force-stop com.android.cli.interact.instrumentation
```

On an emulator image with Play Protect, the helper's install waits for verification and `android layout` hangs with no timeout. Turn off the verification of `adb` installs first:

```sh
adb shell settings put global verifier_verify_adb_installs 0
```

The CLI's `android docs search` and `android docs fetch` answer from the Android developer documentation, including its TV pages. Its `input key` takes only numeric key codes, so `adb shell input keyevent` stays the way to send keys.

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

[CONTRIBUTING](../../CONTRIBUTING.md#ci) describes the CI jobs. A push to `main` also runs `snapshot.yml` beside them, which builds the APK, both web bundles and the desktop package on Linux, macOS and Windows; once every check and package is green, `publish-snapshot.yml` replaces the `snapshot` prerelease. Run `snapshot.yml` from the Actions tab to publish without a push.

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

## Icons

Regenerate the desktop and web icons after changing the vector mark. Requires ImageMagick 7:

```sh
tools/render-icons.sh
```

It writes `ui/icons/sofa-king.png`, `.ico` and `.icns` for the Linux, Windows and macOS packages, and `favicon.svg`, `icon-192.png`, `icon-512.png` and `apple-touch-icon.png` under `ui/src/webMain/resources` for the web page and its manifest. The packaging tasks use jpackage's default icon while a file is missing.

## Dependencies

Version changes follow [dependencies](dependencies.md#changing-versions). Run the checks after a change.
