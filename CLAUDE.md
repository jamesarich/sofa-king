# Sofa King

Android TV / Google TV live-TV client for the home Dispatcharr server. Single `:app` module, Kotlin, Compose for TV, Media3.

## Data

- Dispatcharr base URL and channel profile come from `BuildConfig` (`-PdispatcharrUrl`, `-PdispatcharrProfile`); defaults are `http://192.168.1.3:9191` and `livingroom`. Settings can replace both; `ServerAddress` is what Settings accepts.
- Playlist: `/output/m3u/<profile>`. Guide: `/output/epg/<profile>?days=1` (XMLTV; the default window is ~16 MB, one day ~6.6 MB). Parse streaming and keep only lineup channels.
- The last good lineup is cached in `filesDir/lineup-cache.bin` (`LineupCache`, keyed by server and profile) and shown before the network answers: ~0.2-0.4 s on a Chromecast with Google TV. A network refresh takes ~2.5 s for ~750-2000 channels and ~14-24k programs; the first frame of a fresh guide is ~1 s on the main thread. A manual Refresh keeps the in-memory lineup and never re-reads the cache.
- Load in two phases: emit the channel list as soon as the playlist parses, then the guide.
- XMLTV `channel` ids are Dispatcharr channel numbers. Some channels have no programs at all; the UI shows "No listing".
- Streams are `/proxy/ts/stream/<uuid>`: continuous MPEG-TS that can start mid-GOP.
- `app/src/test/resources/` holds a 20-channel snapshot of the real profile. Cloud sessions can't reach the LAN, so work against these fixtures there.

## Build and test

- `./gradlew :app:testDebugUnitTest :app:assembleRelease`. Needs `local.properties` with `sdk.dir` (git-ignored).
- Parser tests run on the JVM with kxml2 standing in for Android's `XmlPullParser`.
- Install on the TV: developer mode (About > Android TV OS build x7), Wireless debugging, `adb pair` then `adb connect`, `adb install -r app/build/outputs/apk/release/app-release.apk`.
- Put release builds on the TV (`:app:assembleRelease`, R8, debug-signed). A debug build composes ~8x slower on a Chromecast with Google TV.
- Emulator: `system-images;android-36;google-tv;x86_64`.
- The TV banner is a rendered PNG (`tools/render-banner.sh`, ImageMagick + Noto Sans); a layer-list cannot carry the app name. Rerun the script after changing the mark.
- To check Compose skipping, add `composeCompiler { reportsDestination = layout.buildDirectory.dir("compose_reports") }` locally and read `app-composables.txt`; do not commit it.

## Rules

- Compose for TV: `androidx.tv.material3` components only, never mobile Material 3 alongside it. Lists and grids use foundation `LazyColumn`/`LazyRow`/`LazyVerticalGrid`; the `TvLazy*` layouts are removed.
- Focus: `focusRestorer` on every lazy container (`focusGroup()` first on plain `Row`/`Column`), `requestFocus()` only from `LaunchedEffect` or callbacks. A request on a node that is not attached yet returns false, it does not throw: use `requestFocusWhenAttached` (ui/Focus.kt), which loops on the Boolean overload, for anything inside a lazy list. Everything must work with a 5-way D-pad; Back returns toward the channel list.
- Overscan: 48dp horizontal, 27dp vertical padding on full-screen content. The app header carries the top margin above the guide; the guide adds spacing, not a second margin, so four rows fit at 1080p (see the budget in `GuideMetrics`).
- Guide: the window is 24 h from the half hour the screen opened and holds while "now" is inside it (`GuideTimeline.windowStartFor`). Up and Down go through `onGridKey` (`pendingRow` + `requestJump`) so a long cell never pulls focus hours ahead; cells have no focus scale. Filter, search, cursor, scroll and the focused channel are `rememberSaveable` and the guide branch sits in a `SaveableStateHolder`, so Back from the player returns to the same cell; a fresh guide starts on `AppSettings.lastChannel`.
- Models (`Channel`, `Programme`, `Lineup`, `GuideCell`) are `@Immutable`. Per-cell work goes into `GuideRowEnv`, one per guide; a cell formats its accessibility text inside `semantics {}` only. Read "now" from `rememberMinuteNow()`, never `Instant.now()` in composition.
- Playback: one `ExoPlayer` per player screen from `buildLivePlayer`, which the guide preview shares (no `CaptionSync`, video only, muted). Zap with `stop()`, `setMediaItem`, `prepare()`; the stop resets the renderers so the next decoder rescales. TS extractor flags `FLAG_ALLOW_NON_IDR_KEYFRAMES | FLAG_DETECT_ACCESS_UNITS`. On `ERROR_CODE_BEHIND_LIVE_WINDOW`, `recoverIfBehindLive` seeks to the default position and prepares again. The player follows its channel by guide id and number across a lineup refresh (`indexAfterRefresh`).
- Lifecycle: ON_STOP stops the player (the TV has one hardware decoder and the stream is a Dispatcharr slot); ON_START prepares the same item. Keep the screen on only while `isPlaying`; never for autoplay. The audible player handles audio focus; the preview does not.
- Captions: CEA-608 and 708 come from the TS SEI (`setTsSubtitleFormats`). Live captions trail speech, so `CaptionSync` renders text the channel's caption advance ahead (`AppSettings.captionAdvanceFor`, milliseconds, default 0, set per channel from the player) through a `ForwardingRenderer` around the text renderer, and `CaptionSyncLoadControl` holds playback until that much is buffered. `CaptionSync` fields are written from a `SideEffect`; the look-ahead clamp loop runs only while a lead is in effect, so captions off costs nothing.
- Wrapping a Media3 Java interface: never use Kotlin `by` delegation. It skips Java default methods, and `LoadControl`'s defaults throw "not implemented"; forward every PlayerId/Parameters overload explicitly (see `CaptionSyncTest`).
- A `MediaSession` lives as long as the player; no background playback service.
- `PlayerView` in `AndroidView` with `onRelease { player = null }`; `media3-ui-compose` `ContentFrame` is `@UnstableApi`, so pin Media3 if switching.
- Settings: `DataStore` with a `ReplaceFileCorruptionHandler` and an `IOException` catch on the flow; a bad file reads as defaults rather than failing `stateIn`.
- Colors come from `ui/theme`; overlays over video use `SofaOverlayScrim` (plum at 88%, dusk text above 11:1 over any picture). Every visible string is a resource, US English, "program" not "programme".
- The Google TV Live tab and TV Input Framework are not open to third-party apps; the guide lives in-app.
- Play requirements if this ever ships: App Bundle, targetSdk 35 or higher, 64-bit with 16 KB pages, 320x180 banner containing the app name.

## Prior art

- AerioTV-Android (GPL-3.0) is a Compose + Media3 client with a Dispatcharr API backend and a TV guide grid: https://github.com/jonzey231/AerioTV-Android
- No official Compose EPG sample exists; JetStream has no guide.
