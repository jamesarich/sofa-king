# Development

The [contributing guide](../../CONTRIBUTING.md) covers the commit and pull request conventions and CI. This page covers the app's build, its tests, Android Studio, its code conventions, running each platform, the checks on a TV and snapshot builds.

## Build and test

Use JDK 21, the Android SDK with API 37.1 and the checked-in Gradle wrapper. The app's build lives in `app/`; run every command on this page from there. Point the build at the SDK in the ignored `local.properties` file:

```sh
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
```

Then build and test:

```sh
./gradlew :core:allTests :ui:jvmTest :ui:testAndroidHostTest :ui:jsNodeTest :androidApp:lintDebug :androidApp:assembleRelease :androidApp:testDebugScreenshotTestDefaultTestSuite
```

`:core:allTests` runs the data layer's tests on the JVM, Android, Linux, js and wasm (on Node); Apple targets run theirs only on a Mac. The UI's common tests run on the JVM, as Android host tests and on JS; Compose's wasm test binary needs a browser, so the UI has no wasm test run. Before a change to common code, also compile the Native tests, which reject what the JVM accepts (commas or parentheses in backtick test names, JVM-only APIs):

```sh
./gradlew :core:compileTestKotlinLinuxX64 :core:compileKotlinLinuxArm64 :ui:compileTestKotlinIosSimulatorArm64
```

Unit tests cover the API client, catalog and guide mapping, the server connection's bootstrap backoff and server changes, the playback session lifecycle, cache, settings, the Apple TV's settings storage and its move out of Caches (`tvosTest`, which only macOS runs), captions, remote-control key mapping, the launch and focus decisions, channel re-resolution, the preview's rest timers and network cancellation. Server interactions run against Ktor's `MockEngine` in common code on every target, and against MockWebServer on the JVM where a real socket matters, so no server is needed. Screen tests in `ui`'s `jvmTest` drive the composables with Compose's ui-test (`runSkikoComposeUiTest`), pressing keys and finding nodes by semantics on a virtual clock. `RenderSmokeTest` draws the guide, settings, the settings lock page, the player's info bar and its channel list headless at TV size and writes them to `ui/build/screenshots`.

`AppKeyFuzzTest` and `AppJourneyTest` run the whole app, paired through a pairing link to `FakeServer`, a server on loopback with a lineup, a day of guide, a movie and a show library and a few recordings, whose streams go nowhere. `AppDriver` presses the remote's keys and reads what they left focused. Keys go through the scene as a window sends them, so an open popup, menu or sheet takes them first, and an Escape, or a Back, that nothing took is a back event; a held key repeats. Each test starts the app in the desktop layout or, with the `sofaking.tv` system property, the TV layout, and leaves libVLC out without looking for it, so the player shows its failure card.

`AppJourneyTest` walks the focus items of the [checks on a TV](#test-on-a-tv) key by key: the guide's Back ladder, Info and a held OK on a cell and on the chips, the Library tab and a card's page, a card's menu, the empty DVR tab, Menu into a guide whose filter has no channels, Settings, and Down from the guide's chips on a desktop. Each step names where focus must land; a failure's screen and semantics tree go to `ui/build/journeys`.

`AppKeyFuzzTest` presses 300 random remote keys, weighted toward the D-pad, once in each layout. It fails when a key throws, focus is lost for more than 3 seconds, or the app holds more than one playback session, live or library, and names the seed and the last keys pressed; the screen and its semantics tree go to `ui/build/fuzz/desktop` or `ui/build/fuzz/tv`, beside a trace of every key and what it left focused. Replay a failure or run a longer soak with the seed and the number of keys, or press a fixed list of keys by the names the trace uses:

```sh
SOFAKING_FUZZ_SEED=7 SOFAKING_FUZZ_STEPS=2000 ./gradlew :ui:jvmTest --tests 'dev.jamesrich.sofaking.AppKeyFuzzTest' --rerun
SOFAKING_FUZZ_KEYS=Down,Back,Down,Up ./gradlew :ui:jvmTest --tests 'dev.jamesrich.sofaking.AppKeyFuzzTest.random remote*' --rerun
```

The screenshot tests are Google's [Compose Preview Screenshot Testing](https://developer.android.com/studio/preview/compose-screenshot-testing-with-testsuites), run as the `androidApp` module's `screenshotTest` test suite. `androidApp/src/screenshotTest` holds `@PreviewTest` previews of the guide, a program's details, the player's info bar, the Library tab, a show's page and Settings, on a 1080p TV and a phone; the screens and their sample data are in `ui`'s `androidMain` (`dev.jamesrich.sofaking.previews`), on a clock stopped at one evening in UTC. Android Studio shows them as previews. `:androidApp:testDebugScreenshotTestDefaultTestSuite` renders them with the Android layout library on the JVM and fails on a pixel that differs from the reference images in `androidApp/src/screenshotTestDefaultDebug/reference`; its report, with the differences, is in `androidApp/build/reports/tests/testDebugScreenshotTestDefaultTestSuite`. After an intended change to how a screen looks, write the references again and commit them with the change:

```sh
./gradlew :androidApp:updateDebugScreenshotTestDefaultTestSuite
```

To look at the TV layout without a TV, run the desktop app with `./gradlew :ui:run -Psofaking.tv=true`; the keyboard's arrows, Enter and Escape are the remote's.

### TV emulator monkey

[`tv-monkey.yml`](../../.github/workflows/tv-monkey.yml) runs [`scripts/tv-monkey.py`](../scripts/tv-monkey.py) every night in a headless Android TV 36 emulator on a GitHub runner. The script starts the server's installed distribution through [`scripts/fixture_server.py`](../scripts/fixture_server.py), with eight fixture channels (one HLS, seven endless MPEG-TS streams of a clip FFmpeg makes, sent at the clip's pace), six hours of guide and a short fixture show, pairs the debug app through a `sofaking://pair` link to `10.0.2.2`, waits for the guide's preview to open a session, runs the [Maestro](https://maestro.dev) flows in [`maestro/`](../maestro) (below), then presses 1,500 random remote keys a quarter second apart, weighted as `AppKeyFuzzTest` weighs them, through `monkey` key scripts of 25 keys. Back on the Live tab leaves the app, as it should, so the app is brought back to the front before each script; the summary counts how often. It fails when a flow fails, when the app crashes or stops responding, or when a playback session is still open once the app is behind the home screen. A debuggable build turns StrictMode on with logging only, so the job's summary counts the violations and `strictmode.txt` lists them; the artifact also holds the logcat, the server log, the key scripts and the last screen. Replay a run from the Actions tab with its key count and seed, or locally against a booted emulator, with the server's distribution and the debug APK built:

```sh
SOFAKING_MONKEY_SEED=1234 python3 scripts/tv-monkey.py --apk androidApp/build/outputs/apk/debug/androidApp-debug.apk --flows maestro --out build/tv-monkey
```

For the monkey, the fixture server also adds a library holding a short show of two 20-second episodes. The flows run in name order on the freshly paired app, each a short script of remote keys and what should then be on screen: `guide.yaml` leaves a player the guide's rest (Settings > Open full screen) may already have opened, waits for the rest to open the next channel full screen and comes back to the guide with Back; `library_player_keys.yaml` checks that Play/Pause and Rewind leave the library player's bar down, with Paused and the step badge showing; `library_up_next.yaml` plays the first episode to its end and takes Up next to the second. A flow named `library_` starts with the first episode playing, which the script opens through a `sofaking://vod` link. Each flow's Maestro report, output and a screenshot of a failure are in the artifact. `--flows` needs the [Maestro CLI](https://docs.maestro.dev) on the `PATH`; the workflow pins its release. Match flows by the text on screen or by a test tag (`id:`, which the Android app exposes as a resource id), and wait with `extendedWaitUntil` rather than a fixed pause, since the emulator's speed varies.

For a check by hand in an emulator or simulator, run `scripts/fixture_server.py --out build/fixture` and connect the app to the address and token it writes to `server.env` (from an Android emulator, use `10.0.2.2` for the host). With `--shows` it also serves a TV shows library of four six-minute episodes that share a theme, about 10 to 55 seconds in, and end credits, the last 30 seconds, each with English subtitles beside it and a summary that gives it away, and waits until the server has found the intros and credits and made the scrub thumbnails: for Skip intro, Skip credits, Up next, the subtitle settings and timing, Hide spoilers and scrubbing. That needs an FFmpeg built with Chromaprint, which the script checks for. With `--movies` it serves a Movies library of 84 titles from # and A to Z, each with two of three genres, the first (Amber Road) with a trailer and a featurette, for the letter jump, More like this and Extras. `--quiet` silences the channels' tone, for a simulator that plays through the Mac's speakers.

### Phone touch tests

[`phone-tests.yml`](../../.github/workflows/phone-tests.yml) runs the debug app's instrumented tests in [`androidApp/src/androidTest`](../androidApp/src/androidTest) every night in a headless Android 36 phone emulator, against the same fixture server, which the emulator reaches at `10.0.2.2`. Each test pairs the app through a `sofaking://pair` link, drives it with UI Automator and checks what it did through the server's API. `pictureInPicture` plays Sofa Sports, goes to the Home screen, which must shrink the player into picture-in-picture still playing, and brings it back full screen. `predictiveBack` lets go of a back swipe at the screen's edge, which must leave the player as it was, then carries one through, which must return to the guide and close the channel. `rotation` turns the phone while a channel plays and checks that the app holds one session. `walkWithRandomTouches` makes 300 weighted random taps, swipes, back swipes, long presses, turns and trips to the Home screen. After each test the app closes, which must release every session; a crash fails the run. Android Test Orchestrator runs each test in its own instrumentation with the app's data cleared, so a walk that changes settings cannot break the tests after it. The artifact holds the test report, the logcat (with the walk's seed) and the server log. Replay a walk from the Actions tab with its step count and seed, or locally against a booted phone emulator with the fixture server running:

```sh
python3 scripts/fixture_server.py --out build/phone-tests &
./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.sofakingServer=http://10.0.2.2:PORT \
  -Pandroid.testInstrumentationRunnerArguments.sofakingToken=TOKEN \
  -Pandroid.testInstrumentationRunnerArguments.sofakingWalkSeed=1234
```

with the port and token from `build/phone-tests/server.env`. Without the server's address the tests skip.

### Desktop monkey

[`desktop-monkey.yml`](../../.github/workflows/desktop-monkey.yml) runs [`scripts/desktop-monkey.py`](../scripts/desktop-monkey.py) every night on a GitHub runner: the desktop app's distribution (`:ui:createDistributable`) in an Xvfb display, paired by a settings file written before it starts, against the same fixture server, with libVLC playing the channels into ALSA's null device. It presses 1,000 random keys 150 ms apart through xdotool, weighted as `AppKeyFuzzTest` weighs the remote's, with a held Return now and then. Then it closes the window the way a window manager's close button does. It fails when the app exits, logs an uncaught exception or an unhandled failure, draws two blank screens in a row, or does not quit and release its session when its window closes. The summary counts Compose's warnings about focus requests made before their target was placed. A JVM that will not quit prints its threads to `app.log`, which the artifact keeps with the server log, the keys and the last screen. Replay a run from the Actions tab with its key count and seed, or locally with Xvfb, xdotool, ImageMagick, python-xlib, libVLC, FFmpeg and both distributions built:

```sh
SOFAKING_DESKTOP_MONKEY_SEED=1234 python3 scripts/desktop-monkey.py --out build/desktop-monkey
```

### Apple TV remote tests

`tvosApp/SofaKingTVUITests` drives the Apple TV app with the Siri Remote (`XCUIRemote`) in a tvOS simulator, against the same fixture server, and checks what the remote did through the server's API. `testPlayerTransportMenu` plays Sofa Sports from the list, presses Record in the player's transport bar until the DVR holds the recording, switches to Movies through Channels › All channels, and leaves with Menu, which must release the session. `testTabs` moves along the tab bar, into each tab and back. `testWalkWithRandomKeys` presses 200 weighted random buttons (`SOFAKING_WALK_KEYS` sets how many), brings the app back when Menu leaves it, fails if the app stops, and checks that the session is released once the app is in the background. A failure's message holds what had focus and the element tree. CI's `apple-tv` job runs them after building the Apple TV host, with shorter walks on a pull request: 60 keys on the keyboard (`SOFAKING_KEYBOARD_KEYS`, 120 by default) and 100 in `testWalkWithRandomKeys`. On a Mac, with the server's distribution built and FFmpeg installed, start the fixture server and pass its address to the test runner; without it the tests skip:

```sh
python3 scripts/fixture_server.py --out build/tvos-ui &
set -a; . build/tvos-ui/server.env; set +a
cd tvosApp && xcodegen
TEST_RUNNER_SOFAKING_SERVER=$SOFAKING_SERVER TEST_RUNNER_SOFAKING_TOKEN=$SOFAKING_TOKEN TEST_RUNNER_SOFAKING_WALK_SEED=1234 \
  xcodebuild -project SofaKingTV.xcodeproj -scheme SofaKingTV -destination 'platform=tvOS Simulator,name=Apple TV' CODE_SIGNING_ALLOWED=NO test
```

### iOS touch tests

`iosApp/SofaKingUITests` drives the iOS app by touch in an iPhone simulator. The debug host takes `-serverUrl`, `-apiToken` and `-muted YES` as launch arguments, so a test saves its server and mutes the player without typing. `testPlayChannel` plays Sofa Sports from the guide against the fixture server and checks that the session opens, and that it is released once the app is behind the home screen; `testWalkWithRandomTouches` makes 150 weighted random touches against the same server (taps anywhere and on buttons, swipes across the middle of the screen, the back swipe from the left edge and long presses), seeded so `SOFAKING_WALK_SEED` replays a walk, brings the app back when a touch leaves it, fails if the app stops, and checks that the session is released behind the home screen. CI's `apple` job runs both after building the iOS host, with a 60-touch walk on a pull request (`SOFAKING_WALK_STEPS`, 150 by default). `testBreakScreen` watches a real channel on the server the simulator is already paired with, for `SOFAKING_BREAK_MINUTES` (10 by default), and logs each time the break screen comes and goes with a screenshot at each edge; it runs only when `SOFAKING_BREAK_CHANNEL` names the channel:

```sh
cd iosApp && xcodegen
TEST_RUNNER_SOFAKING_BREAK_CHANNEL='CSI' xcodebuild -project SofaKing.xcodeproj -scheme SofaKing \
  -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO -only-testing:SofaKingUITests/TouchTests/testBreakScreen test
```

### Web key fuzz

[`scripts/web-fuzz.py`](../scripts/web-fuzz.py) serves a production web build to a headless browser through [Playwright for Python](https://playwright.dev/python/), with the page paired to the same fixture server, and presses 400 random keys 150 ms apart, weighted as `AppKeyFuzzTest` weighs the remote's. It fails when the page keeps the focus on its body (the app then takes no keys until a click), on an uncaught error, a console error, a failed request (but the 404 for a new device's preferences and the 410 for a released session's media), a crashed page, or two blank screens in a row; the summary also counts the sessions left open after the page closed. The script answers the update check's request to api.github.com itself, with a snapshot no newer than any build, so the run never reaches the live API. The Linux CI job runs it on both web builds in Google Chrome, which plays the fixture's H.264 where Playwright's own Chromium does not, and uploads the console, server log, keys and last screen when it fails. Locally, with the server's distribution, FFmpeg, Playwright and a web build:

```sh
SOFAKING_WEB_FUZZ_SEED=1234 python3 scripts/web-fuzz.py --channel chrome --dist ui/build/dist/wasmJs/productionExecutable --out build/web-fuzz
```

## Android Studio

Open the repository root, not `app/`. The root `settings.gradle.kts` imports the app, the server and the contract as one composite build named `sofa-king-monorepo`, and the root `gradle.properties` holds the Gradle settings the IDE's sync uses. Use an Android Studio release that supports the catalog's `agp` version, with its bundled Kotlin Multiplatform plugin enabled, and the SDK in `app/local.properties` as above. A preview `agp` (an alpha or beta) syncs only in the Android Studio Canary or Beta built with it; a stable Studio stops at the last stable plugin, and a nightly expects a plugin preview that Google Maven may not have published yet. Either refuses the sync with "The project is using an incompatible version" (or "incompatible preview version") of the Android Gradle plugin, under Gradle Sync issues in the Build window. To see which plugin a Studio build accepts, read `buildVersion` in `com/android/version.properties` inside the app's `plugins/android/lib/libagp-version.jar`.

- **Gradle JDK.** The Gradle daemon runs on JDK 21, which `gradle/gradle-daemon-jvm.properties` asks for; Gradle downloads one if the machine has none. If Settings → Build, Execution, Deployment → Build Tools → Gradle offers a Gradle JDK, pick a JDK 21 (Studio's own `jbr-21` will do).
- **Run.** After the first sync, Studio adds an `androidApp` configuration; run it on a TV, a phone or an emulator. The `.run/` folder adds **Desktop**, which runs `:ui:run`, and **Web**, which runs `:ui:wasmJsBrowserDevelopmentRun`. iOS and Apple TV run from Xcode, as the next section says.
- **Previews.** Compose previews in `androidApp` need its unit tests enabled with Android resources; `androidApp/build.gradle.kts` keeps both, and the sync fails without them.

When a sync fails, read the error in the Build window's Sync tab; Studio's `idea.log` keeps only "Unknown cause". Most sync failures also show from the command line, because a sync registers every task, so run `./gradlew tasks --all` from the repository root. `org.gradle.tooling.parallel` stays off in every `gradle.properties`. With it on, Studio builds each project's model on its own thread, and the Kotlin plugin's web setup creates tasks in every project from whichever thread gets there first, so the sync failed with a `ConcurrentModificationException` while creating `:app:core:jsTest`, `:app:ui:jvmTest` or `:app:rootPackageJson`. If the Project view lists modules with a `~1` suffix, or the sync reports `Resolved source set not found` for `androidMain`, Studio still holds modules from an older import: quit Studio, delete the project's folder under `~/Library/Caches/Google/AndroidStudio<version>/projects/`, reopen the repository root and sync again.

## Code

Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) and the constraints in [AGENTS.md](../AGENTS.md). Put code in `commonMain` unless it needs a platform API, keep `core` free of Compose, preserve coroutine cancellation, and close the sessions, players and HTTP calls you own. [Architecture](architecture.md) describes how the code is organized. Every Kotlin compile fails on a compiler warning (`sofaking.warningsAsErrors` in `gradle.properties`), so fix a warning or opt in to the API it names rather than leaving it. Run `./gradlew spotlessApply detekt` before you commit; CI fails on code ktlint would change and on any detekt finding, as [formatting and static analysis](../../CONTRIBUTING.md#formatting-and-static-analysis) describes.

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

The journeys and the fuzz walk focus in the TV layout on the JVM, but not Android's own key dispatch, how focus looks, a remote's extra keys or a real decoder. Check these on a real Android TV or Google TV device before a change that touches playback, focus or the remote:

- [ ] Cold start opens the last watched channel, and Back shows the guide focused on it. Leave the app from the Library tab and start it again: it opens on Library with nothing playing.
- [ ] The Guide, Menu and Search keys work (the launcher may claim Guide on some devices), and voice search finds a channel.
- [ ] D-pad focus moves through the guide without losing its place, including rapid focus changes over the preview, and the stream carries over between preview, player and guide.
- [ ] Focus is visible on every button, chip, switch and field, including the Recent chip and a Later today line under a search result.
- [ ] Info or a held OK on a guide cell opens the program details beside the grid with Watch focused, and releasing the held OK does not play the channel; Favorite toggles the star, Back and Close return focus to the cell.
- [ ] Fast Forward moves the guide a day ahead with the same channel focused, Rewind and Now return, and the day's programs fill in.
- [ ] The preview is muted, unmutes at 5 seconds and opens full screen at 10 seconds, and captions show over it. With music playing from another app, the muted preview leaves it playing and the preview's sound stops it.
- [ ] Background and foreground transitions resume the same channel.
- [ ] Audio, captions and decoder recovery work on the full-screen player. Previous and Next on a remote that has them step the channel number down and up.
- [ ] In the player, OK on the info bar opens the channel list, and OK on a row under All tunes a channel outside the guide filter; Left or Right on the bar moves into its buttons and names the focused one, and Up or Back returns to the picture.
- [ ] With the remote's CC, Play/Pause and Info keys unused: the bar's Captions button toggles captions with the timing toast up, Pause pauses, Stream info opens the stream card with the playing audio track focused, Record records the airing program on a server with recordings, the star changes the Favorites chip's rows, and Guide opens the guide; OK on a card without tracks closes it.
- [ ] In a library item or a recording, Down on the bar moves into its buttons: Skip break inside a commercial break, Audio and subtitles, Captions, Start over past the first 30 seconds and Next episode when one follows; Up or Back returns to the picture and the bar hides after 5 seconds idle. With the bar down, Right and Fast Forward show the seek badge ("+30 s to 12:34") and leave the bar down, and Play/Pause pauses with the center disc and Paused at the top left but no bar; OK brings the bar up.
- [ ] Zoom on either bar, and the Zoom key, step Fit, Fill and Stretch with a note naming each; a 4:3 channel fills the screen on Fill and the zoom holds across a zap and into a library item. Audio track opens the stream card or Audio and subtitles, Record records on live, Stop leaves, and a gamepad's L1/R1 zap on live and seek in a library item.
- [ ] CC then Left/Right moves the channel's caption timing both ways and the toast follows; Info held opens the stream card, and Up/Down and OK pick an audio track on a channel with several.
- [ ] Settings → Open on start set to Guide opens the guide on a cold start, Library opens the Library tab, and A channel with one chosen opens that channel; Save & reload is off until the server fields change.
- [ ] Settings → Manage channels: a group switched off empties its chip in the guide and its channels leave the channel list; the arrow opens the group's channels and one switched back on returns alone; hidden channels leave Channel Up/Down only once Skip hidden channels is on; the move buttons reorder the Favorites filter.
- [ ] Settings opens on its first setting with no Back button drawn and Down walks General, Live TV, Recordings, Library, Captions, Playback, Server; the header shows only the tabs and Settings. Settings → Server → Reload shows Reloading while the lineup loads. Settings → Recordings → Skip commercials set to Automatically jumps a recording's breaks.
- [ ] Settings → Time format 24-hour and Guide rows Compact change the guide at once; Open system caption settings opens the TV's captioning page.
- [ ] Settings → Lock → Set a PIN, Back, open Settings: the lock page focuses the PIN field; a wrong PIN says so and the fifth starts a 30 s wait; the right PIN opens Settings; Forgot the PIN with the server's API token removes it.
- [ ] Settings → Match frame rate on: a 29.97 or 25 fps channel switches the TV's refresh rate (the panel may blank for a moment) and the launcher's rate returns on Back, with playback still running after the switch (logcat shows "ignored a media-session pause" on a box that sends one); Decoder Software and Buffer Smoother still play the channel.
- [ ] Up from the guide's chips reaches the Live and Library tabs and Settings; OK on Library opens it with the tab still focused, Down enters the rows, Up from the top row or a grid's chips returns to the open tab, and Back returns to Live.
- [ ] Back from the guide's grid reaches the chips, then the Live tab, and the next Back exits the app at once. Settings opened from Library or DVR returns there on Back. On Library and DVR, Back from the page focuses the open tab in the header, and a second Back opens Live with focus on the Live tab and no preview playing; a third exits. Down from there focuses the guide on the last channel and starts the preview. The Guide key on a Library page opens the guide at Now. Back from a recording focuses that recording, Back to a tab scrolls its page to the top, and a cold start into Library or an empty DVR tab focuses the tab.
- [ ] On the Library tab, Down reaches Search and the library chips; Search takes typed and spoken queries (the Search key starts it by voice), OK on a result opens its page and Back returns to the results, then to the Search chip.
- [ ] In the Library, OK on a card opens its page with Play or Resume focused, Play on the remote plays a movie straight from its card, a library's grid sorts and filters from its chips and loads more as it scrolls, and Back from a page or a grid focuses the card or library left.
- [ ] A show's page opens with its title in view and on the season with the next episode; Down from the buttons lands on that season's chip, the chips switch the episode rows, and OK on an episode plays it, resuming where it stopped.
- [ ] Playing an episode whose intro the server found (the Jobs page's Intro detection lists the shows), the bar comes up with Skip intro focused as the intro starts, from the beginning or a resume inside it; OK jumps past it, and going back into it leaves Skip intro on the bar unfocused.
- [ ] After stopping an episode part way, the home screen's Watch Next row (a Google TV or Shield launcher; the emulator's launcher has none) shows it with its artwork and progress; selecting it plays it from there, with the app closed and with it open on another tab, and Back returns to the Library tab. Finishing it moves the row on to the next episode.
- [ ] Sleep timer on the live bar steps through Sleep in 15, 30, 60 and 90 min, Sleep after this program and Sleep timer (off); set to 15 minutes, the player returns to the guide on time with no preview playing. In a library episode, Sleep after this one leaves for the page as the episode ends, with no Up next. Let three episodes play into each other untouched: the third Up next card asks Still watching? and returns to the page after 30 seconds. On an Apple TV, the transport bar's Sleep timer menu does the same.
- [ ] In the library player, Left and Right scrub and apply once released, holding them jumps further, OK applies at once, Back cancels a scrub and then returns to the page with the episode focused; Home and back resumes where it was, and a far jump in a converted file restarts there.

On an Apple TV (the simulator does not show the Top Shelf of a sideloaded app reliably):

- [ ] The tab bar shows Live, Library (only for a server with libraries), DVR (only for a server that records) and Settings; a Top Shelf channel opens on Live from any tab. Leave the app from Library and start it again: it opens on Library.
- [ ] In the Library, a card opens its page, play/pause on the remote plays a movie or episode straight from its card, and a library's grid sorts, filters and loads more as it scrolls.
- [ ] A show's page opens on the season with the next episode; selecting an episode plays it, resuming where it stopped, and its context menu starts it over or marks it watched.
- [ ] The library player resumes where the item stopped, scrubs in the transport bar, returns to the page at the end, and after Home and back picks up where it was.
- [ ] Holding the touch surface on a channel opens its program details, and Watch plays it.
- [ ] The player's transport bar menu records the program on now on a server that records (Cancel recording once set), toggles the favorite and switches channels from Recent, Favorites and All channels.
- [ ] In a recording with Skip commercials set to With a button, Skip break shows over the picture inside a commercial break and jumps to its end.
- [ ] In an episode whose intro the server found, Skip intro shows over the picture inside the intro and jumps to its end.
- [ ] After watching a few channels and stopping a library item partway, the Top Shelf shows Continue watching with its progress and then the channels newest first; selecting the item plays it where it stopped, from a cold start too, and selecting a channel opens the app on it; the app and the extension must be signed with the `group.dev.jamesrich.sofaking` app group for the list to reach the extension.

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

[CONTRIBUTING](../../CONTRIBUTING.md#ci) describes the CI jobs. A push to `main` also runs `snapshot.yml` beside them, which builds the APK, both web bundles and the desktop package on Linux, macOS and Windows; once every check and package is green, `publish-snapshot.yml` replaces the `snapshot` prerelease, with notes that git-cliff renders from the `feat`, `fix` and `perf` commits since the previous one. With the `APT_SIGNING_KEY` secret (an armored GPG secret key without a passphrase) it also makes the release a flat apt repository: `Packages`, a `Release` signed as `InRelease` and `Release.gpg`, and the public key as `sofa-king-archive-keyring.gpg`, beside the `.deb`. Run `snapshot.yml` from the Actions tab to publish without a push.

The app version is `sofaking` in [the version catalog](../../gradle/libs.versions.toml). Snapshots build every package with `-Psofaking.versionCode` (the build number) and `-Psofaking.versionSuffix` (`-dev.BUILD+COMMIT`, the build number and the short commit), which [`.github/version.sh`](../../.github/version.sh) prints. The build number is the count of commits behind the build, so it grows with every merge to `main`, and the server image built from the same commit carries the same version. A rerun on a commit already published takes one more than the published build, so it still installs over it. Android's `versionName` and the About row of every snapshot build append the suffix. Only the catalog version is compiled in; the suffix is written to `files/version-suffix.txt` among the UI module's Compose resources and read at runtime, so a snapshot reuses the compile outputs the CI run just cached. The desktop packages carry the catalog version too: a snapshot's `.deb` is 0.1.0 plus the suffix and its MSI is 0.1.BUILD, so each installs over the last. jpackage refuses a macOS version whose first number is 0, so the macOS app is built as 1.1.0 and its `Info.plist` repeats `CFBundleShortVersionString` as 0.1.0; macOS reads the later key, so Finder and About This Mac show 0.1.0, with 1.1.BUILD as the build version.

Set the `ANDROID_DEBUG_KEYSTORE` repository secret to a base64 debug keystore so every snapshot APK installs over the last:

```sh
base64 -w0 ~/.android/debug.keystore | gh secret set ANDROID_DEBUG_KEYSTORE --repo jamesarich/sofa-king
```

## Screenshots

The screenshots in the [README](../../README.md#screenshots) come from [`scripts/screenshots.py`](../scripts/screenshots.py), which starts the server's installed distribution with the showcase lineup from [`scripts/showcase.py`](../scripts/showcase.py): twelve made-up channels playing as live HLS, a day of made-up programs, and a Movies and a TV Shows library, all cut from the Blender Foundation's open movies (CC BY). Nothing in them comes from a real server or provider. The first run downloads the movies and cuts them into `~/.cache/sofa-king-showcase` (about 300 MB, around ten minutes); later runs reuse it until the lineup or the cutting changes.

[`screenshots.yml`](../../.github/workflows/screenshots.yml) takes them on every platform a runner can show without a screen, and uploads one artifact per runner: the web app in Chrome and the Linux desktop app on a virtual display, the Android phone, tablet and TV apps in emulators, and the iPhone, iPad and Apple TV apps in simulators. Run it from the Actions tab after a visible change, then copy the images the README shows into `docs/images` at the repository root. Each platform tours the guide with its preview, a channel full screen, the Library tab, a show's page and a movie's page as far as it can be driven from a script: the simulators take no keys or taps from one, so iPhone, iPad and Apple TV stop at the Library tab, and Apple TV, which asks before a link opens the app, has no channel full screen. The iOS app runs in landscape on simulators that boot upright, so the script turns the iPhone's pictures and crops the iPad's to the app. Locally, with the server's distribution, FFmpeg and what each platform needs (the script's header lists it):

```sh
python3 scripts/screenshots.py web --channel chrome --app ui/build/dist/wasmJs/productionExecutable --out build/screenshots
python3 scripts/screenshots.py desktop --app "ui/build/compose/binaries/main/app/Sofa King/bin/Sofa King" --out build/screenshots
python3 scripts/screenshots.py android-phone --app androidApp/build/outputs/apk/debug/androidApp-debug.apk --out build/screenshots
```

The desktop run uses a display of its own (Xvfb), and Android and Apple runs use an emulator or simulator already booted, so run those headless (`emulator -no-window -noaudio`) rather than in front of you.

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
