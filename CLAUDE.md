# Sofa King

Android TV / Google TV live-TV client for the home Dispatcharr server. Single `:app` module, Kotlin, Compose for TV, Media3.

## Data

- Dispatcharr base URL and channel profile come from `BuildConfig` (`-PdispatcharrUrl`, `-PdispatcharrProfile`); defaults are `http://192.168.1.3:9191` and `livingroom`.
- Playlist: `/output/m3u/<profile>`. Guide: `/output/epg/<profile>?days=1` (XMLTV; the default window is ~16 MB, one day ~6.6 MB). Parse streaming and keep only lineup channels.
- Load in two phases: emit the channel list as soon as the playlist parses, then the guide. On a Chromecast with Google TV that is ~2 s to channels and ~10 s to a full guide for ~750 channels.
- XMLTV `channel` ids are Dispatcharr channel numbers. Some channels have no programmes at all; the UI shows "No listing".
- Streams are `/proxy/ts/stream/<uuid>`: continuous MPEG-TS that can start mid-GOP.
- `app/src/test/resources/` holds a 20-channel snapshot of the real profile. Cloud sessions can't reach the LAN, so work against these fixtures there.

## Build and test

- `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Needs `local.properties` with `sdk.dir` (git-ignored).
- Parser tests run on the JVM with kxml2 standing in for Android's `XmlPullParser`.
- Install on the TV: developer mode (About > Android TV OS build x7), Wireless debugging, `adb pair` then `adb connect`, `adb install -r app/build/outputs/apk/release/app-release.apk`.
- Put release builds on the TV (`:app:assembleRelease`, R8, debug-signed). A debug build's first guide frame takes ~8 s on a Chromecast with Google TV; release takes ~1 s and opens from the cache in ~0.6 s.
- Emulator: `system-images;android-36;google-tv;x86_64`.

## Rules

- Compose for TV: `androidx.tv.material3` components only, never mobile Material 3 alongside it. Lists and grids use foundation `LazyColumn`/`LazyRow`/`LazyVerticalGrid`; the `TvLazy*` layouts are removed.
- Focus: `focusRestorer` on every lazy container (`focusGroup()` first on plain `Row`/`Column`), `requestFocus()` only from `LaunchedEffect` or callbacks. Everything must work with a 5-way D-pad; Back returns toward the channel list.
- Overscan: 48dp horizontal, 27dp vertical padding on full-screen content.
- Playback: one `ExoPlayer` per player screen, zap with `setMediaItem` + `prepare`. TS extractor flags `FLAG_ALLOW_NON_IDR_KEYFRAMES | FLAG_DETECT_ACCESS_UNITS`. On `ERROR_CODE_BEHIND_LIVE_WINDOW`, `seekToDefaultPosition()` then `prepare()`.
- Keep the screen on only while `isPlaying`; never for autoplay.
- Captions: CEA-608 and 708 come from the TS SEI (`setTsSubtitleFormats`). Live captions trail speech, so `CaptionSync` renders text the channel's caption advance ahead (`AppSettings.captionAdvanceFor`, milliseconds, default 0, set per channel from the player) (a `ForwardingRenderer` around the text renderer) and `CaptionSyncLoadControl` holds playback until that much is buffered. Both are zero cost while captions are off.
- Wrapping a Media3 Java interface: never use Kotlin `by` delegation. It skips Java default methods, and `LoadControl`'s defaults throw "not implemented"; forward every PlayerId/Parameters overload explicitly (see `CaptionSyncTest`).
- A `MediaSession` lives as long as the player; no background playback service.
- `PlayerView` in `AndroidView` for now; `media3-ui-compose` `ContentFrame` is `@UnstableApi`, so pin Media3 if switching.
- The Google TV Live tab and TV Input Framework are not open to third-party apps; the guide lives in-app.
- Play requirements if this ever ships: App Bundle, targetSdk 35 or higher, 64-bit with 16 KB pages, 320x180 banner containing the app name.

## Prior art

- AerioTV-Android (GPL-3.0) is a Compose + Media3 client with a Dispatcharr API backend and a TV guide grid: https://github.com/jonzey231/AerioTV-Android
- No official Compose EPG sample exists; JetStream has no guide.
