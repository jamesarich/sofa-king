
# Sofa King app

The media center's client: live TV (Live), media libraries (Library) and recordings (DVR) from a Sofa King server. Kotlin Multiplatform: `core` (data, no Compose, every target its dependencies publish), `ui` (Compose Multiplatform screens for Android, JVM desktop, iOS, js and wasmJs), `androidApp` (the Android TV app shell), `baselineprofile` (the Baseline Profile generator), `iosApp` (SwiftUI host) and `tvosApp` (the SwiftUI Apple TV app on `core`'s `SofaKingTv`, since Compose does not render on tvOS, plus its `TopShelf` extension, which reads the recent channels through the `group.dev.jamesrich.sofaking` app group). XcodeGen generates both Swift hosts from their `project.yml`; no `.xcodeproj` is committed. Read README, docs/README.md and docs/architecture.md for setup and behavior.

## Build

Use JDK 21 and an Android SDK: set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`), or write `sdk.dir` in `app/local.properties` (ignored by Git). The tasks, the Native compile that follows a change to common code, and the screenshot references are in docs/development.md#build-and-test; CI adds the production web builds and a macOS job for the Apple targets, so an Apple-only break shows only there (CONTRIBUTING.md#ci). On the shared host, prefix every task with `/home/james/.claude/bin/gradle-queue -C app --` and run it from the repository root; elsewhere run `./gradlew` from `app/`. One test class: `/home/james/.claude/bin/gradle-queue -C app -- :ui:jvmTest --tests 'dev.jamesrich.sofaking.ui.preview.PreviewDwellTest'` (`:core:jvmTest` for the data layer, `:ui:testAndroidHostTest` for the Android host tests).

- Commit only screenshot references that Linux drew, since a Mac renders some screens differently: label the PR `screenshots` (same-repository branches) or run `scripts/take-references.sh <run-id>` after a failed comparison, and look at each image before committing it.
- `androidApp` has no product flavor (`compileDebugKotlin`, `assembleDebug`); the `Default` in the screenshot task names is the test suite.
- Common tests also run on JS, where a `Float` is a double: compare a computed float with a tolerance, not with `assertEquals` against a constant the compiler folded.
- Install release builds on TVs only when requested ([signing](docs/development.md#signing)).
- A change to playback, focus or the remote also needs the TV checklist in docs/development.md, which only a person with a TV can run; say so rather than claiming it. docs/development.md#over-adb has the adb probes.
- Record the Baseline Profile (`androidApp/src/release/generated/baselineProfiles`) on a device with `:androidApp:generateBaselineProfile`, never in CI; regenerate it after a large change to startup, the guide or the player.

Version pins and update steps: CONTRIBUTING.md#dependencies, plus docs/dependencies.md for this part's table. Commit scopes: CONTRIBUTING.md#commits.

## Code map

All source is under `dev.jamesrich.sofaking`; tests mirror it in each module's `commonTest`, `jvmTest` or `androidHostTest`. These are the placement rules the source doesn't show:

- Key mapping, back, focus and timing decisions are plain functions or classes beside the composables (`guideBackAction`, `PreviewDwell`, `launchTarget` and their neighbors), unit-tested without a device; put new decisions there too.
- Anything two feature packages share lives in `ui/.../ui` (`Adaptive.kt`, `Focus.kt`, `theme/` and the other top-level files), so `guide`, `library`, `player`, `preview`, `recordings` and `settings` never depend on each other in a cycle.
- `core/.../data/ServerConnection` is the one process-wide link to the server (API client, capabilities, the one `PlaybackSessions`, the `RecordingSchedule`, the preference sync). Every ViewModel plays through it.
- `ui/.../App.kt` is the shell: the ViewModels, the player and its lifecycle, and the Navigation 3 routes. Its state and moves are unit-tested in `AppShellState.kt` and `Navigation.kt`.

## Code placement

- Put code in `commonMain`. A platform source set holds only what needs that platform's API, behind an `expect` or an interface (`LivePlayer`, `rememberVoiceSearch`, `rememberIsTelevision`, `KeepScreenOn`, `rememberPlayerWindow`, `rememberScreenReaderActive` and the key-repeat accessors are the seams); a desktop window's or browser page's full screen comes in through `App`'s `WindowFullScreen`.
- `core` stays free of Compose so a native host can use it. Its models are plain data classes; `ui/compose-stability.conf` marks them stable.
- Wire objects through constructors: `AppGraph` in `ui` holds the one settings store and the one `ServerConnection`, `initSofaKing` starts it once per process, and the shell gets each ViewModel from `viewModel { appGraph.… }`. A ViewModel never takes another ViewModel or its fields; what several share belongs in `ServerConnection`.
- State flows one way. ViewModels expose immutable `StateFlow`s, setters that return `Unit` and no coroutine handles; a short action whose result matters only while its screen is up (record, delete, search, play) may be a `suspend` function the screen awaits.
- A destination collects with `collectAsStateWithLifecycle`, as low in the tree as it is read. Work that must outlive a screen (pairing, saving) runs in `viewModelScope` and reports its outcome as state the screen acts on and acknowledges (`PairingState.outcome`, `pairingHandled`). Keep UI-only state and moves in a plain state holder (`AppShellState`, `GuideState`, `PlayerState`, `VodPlayerState`) or beside the composable.
- No `java.*`, `android.*` or JVM-only collection APIs in common code: `kotlin.time` for instants and durations, kotlinx-datetime for wall-clock formatting, kotlinx-io for files, Ktor for HTTP, Kermit for logs, `kotlin.uuid` for ids.

## UI

- Use multiplatform Material 3 Expressive components under `SofaKingTheme`. Add `tvFocus` (or use `SofaButton`) to every focusable control; guide cells and Settings rows keep scale 1. A switch setting is a `ListItem` made `toggleable` so the whole row takes focus; a choice of a few values is a `SingleChoiceSegmentedButtonRow`.
- Support the five-way D-pad and its keyboard equivalents. Use focusRestorer; request focus from effects or callbacks and wait for lazy nodes with requestFocusWhenAttached. The header's buttons never keep focus once channels are on screen, except after Back from another tab or leaving Settings (`Arrival.inHeader`). The unconfigured screen focuses the header's Settings button (`homeFocusFor`).
- Pad screens with `screenPadding` (`ui/Adaptive.kt`: 48dp horizontal and 27dp vertical overscan on TV, safe-drawing insets elsewhere); never hard-code the margins.
- Lay out by `LocalLayoutProfile`: a TV (`rememberIsTelevision`) keeps its metrics at any size; other windows adapt by the window size class from `currentWindowAdaptiveInfoV2()`. Touch and mouse controls show only after pointer input (`LocalLastInput`), including a page's own Back button (`showsBackButton`), which a TV leaves to the remote's Back key.
- Preserve the guide's 24-hour window, filter and scroll across player navigation. Back from the player focuses the channel being watched (`returnedFrom` ranks first in `initialGuideRow`) and shows All when a filter or search hides it.
- Decide the cold-start target once, in `launchTarget`, after the settings and (for Live) the lineup are in; the guide and preview must not compose before that decision ([launch](docs/architecture.md#launch)).
- Guide and Menu are one shortcut: in the player they open the guide at Now (`Arrival(atNow)`), in the guide they jump to Now. Search opens guide search through `rememberVoiceSearch`, falling back to the text field. Map remote keys in `playerExit` and `guideShortcut`, not in ad-hoc `when` branches.
- Decide Back in the guide in `guideBackAction`, never in a second `BackHandler`; take the remote's Back key in the guide's preview key handler (key-down held, climb on key-up), since Compose turns an unhandled Back key-down into a focus exit that can eat the press. There is no press-again step. The ladder is in [usage](docs/usage.md).
- Navigate with `Navigation`'s moves (`push`, `pop`, `open`, `closeSettings`), never by holding a parent page in a `Route`: the entry below is the parent. One-shot arrival data goes in `Arrival`, and a `Route`'s `id` names its saved state, so it must not change while the page is open.
- Keep models immutable. Read time from rememberMinuteNow, not the clock during composition; rest timers read `uptimeMs`.
- Use theme colors, shared motion and Compose resource strings in US English, without Android escapes (`\'`). UI terminology is "program".

## Playback and data

How the player, sessions and caches work is in [docs/architecture.md](docs/architecture.md#playback); these are the rules to keep.

- One `LivePlayer` from `rememberLivePlayer`; preview and full screen borrow it and never release it. Screens read its `status`, `errors` and `cues` flows.
- Screens call `tune` and `setIntent` on `PlaybackSessions`, never the session API directly. One session at a time.
- The preview opens a `preview` session and OK promotes it to `watch` with `setIntent`; do not reopen or reload on the handoff. The full screen sets the shared player's volume to 1 when it opens; the preview writes it only while the guide is the page on top.
- Keep `PreviewDwell`'s timing decisions in the class, not in effects. The auto full screen never fires unless the host is STARTED.
- Items carry the channel id and the descriptor's MIME type (`mediaItemFor`); Media3 never sniffs a session URL.
- Renew well inside the preview lease. Release on channel change, Settings and ON_STOP; reopen on ON_START. Reopen on `replaced` and on a 410 from the relay by re-reading the session, not by retrying the old URL.
- Run session opens under `NonCancellable` and release what a canceled tune opened. Never leak a session: capacity is small and the oldest preview is evicted.
- Gate tune preparation on STARTED, using the app's lifecycle (`appLifecycleOwner`), not a page's `LocalLifecycleOwner`, which also stops when the page is left.
- Preserve the zap debounce (`ZAP_SETTLE_MS`) and the stream handoff.
- Reconnect failed or ended streams with the existing backoff; add no watchdog for stalled-but-open streams. A `CHANNEL_NOT_FOUND` error reloads the lineup and follows the channel by number, then name; never retry a dead id.
- Hold the system media session only through `LivePlayer.openMediaSession` (`NewestMediaSession`); video has no background service. Draw only the picture while `LocalInPictureInPicture` is true. Ask for audio focus only while audible, so the muted preview never stops other apps' sound.
- Preserve CEA-608/708 selection, caption geometry and per-channel timing on Android. Update renderer-thread settings from SideEffect through `@Volatile` fields in `CaptionSync`. The preview draws the same `Captions` composable, scaled, gated on the CC setting.
- When wrapping Media3 Java interfaces, forward every required overload explicitly; do not use Kotlin by delegation (`LivePlayerWrapper` is a `ForwardingSimpleBasePlayer`).
- Keep sockets and files off the main thread (`ioDispatcher`). Preserve cancellation and close owned calls, listeners and resources.
- Keep DataStore corruption recovery, its Android file path and last-good lineup behavior; add lineup cache protobuf fields with new numbers and never renumber them. Never log or print the API token; `AppSettings.toString` hides it and Settings masks all but its last four characters.
- API requests go through `SofaKingApi` with the bearer token; media requests carry none. Transport errors surface as `IOException` on every engine. Guide queries stay within 200 channels and 24 hours.
- The server's request and response types come from `../contract` (aliased in `SofaKingDto.kt` beside `wireJson`, which ignores unknown fields); change a wire field there, never in the app (CONTRIBUTING.md#wire-changes). Branch on `Wire` constants, not string literals.

## Documentation

Document current facts, setup, controls and troubleshooting, by the rules in CONTRIBUTING.md#documentation. Put change rationale and validation results in commit or pull-request descriptions.
