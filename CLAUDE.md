# Sofa King contributor instructions

Android TV app: one `app` module, Kotlin, Compose for TV and Media3. Read README and docs/ARCHITECTURE.md for current setup and behavior.

## Build

Use JDK 21 and an Android SDK configured in local.properties. On the shared host, run:

```sh
/home/james/.claude/bin/gradle-queue -- :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Unit tests run against MockWebServer; no server is needed for offline development. Install release builds on TVs only when requested; release APKs use R8 and the local debug key.

## UI

- Use androidx.tv.material3 and foundation lazy layouts. Do not mix mobile Material 3 into TV screens.
- Support the five-way D-pad. Use focusRestorer; request focus from effects or callbacks and wait for lazy nodes with requestFocusWhenAttached. The guide's first focus is retried until a cell reports it landed; the header's buttons must never keep focus once channels are on screen. The unconfigured screen starts on Settings.
- Preserve 48dp horizontal and 27dp vertical overscan padding.
- Preserve the guide's 24-hour window, filter and scroll across player navigation. Back from the player focuses the channel being watched (`returnedFrom` ranks first in `initialGuideRow`), shows All when a filter or search hides it, and keeps the time cursor no earlier than now.
- A cold start opens the last watched channel full screen through the normal session path, decided once by `launchTarget` after the lineup and settings are in; the guide and preview must not compose before that decision. No last channel opens the guide.
- Guide and Menu are one shortcut: in the player they open the guide at Now (`Screen.Guide(atNow)`), in the guide they jump to Now. Search opens guide search through `RecognizerIntent` (`rememberVoiceSearch`), falling back to the text field when no recognizer answers. Map remote keys in `playerExit` and `guideShortcut`, not in ad-hoc `when` branches.
- Back in the guide: open search closes first, the grid hands focus to the selected chip, the chips and header exit. Decide it in `guideBackAction`, never in a second BackHandler.
- Keep models immutable. Read time from rememberMinuteNow, not Instant.now during composition.
- Use theme colors, shared motion and resource strings in US English. UI terminology is "program".

## Playback and data

- MainActivity owns one ExoPlayer. Preview and full-screen screens borrow it; neither releases it.
- Every stream is a server playback session owned by `PlaybackSessions` in the ViewModel; screens call `tune` and `setIntent`, never the session API directly. The app holds one session at a time.
- The preview opens a `preview` session; OK promotes the same session to `watch` with `setIntent`. Do not reopen or reload on the handoff.
- The preview's rest timers live in `PreviewDwell` (muted on tune, sound at 5 s, full screen at 10 s, any key restarts both); the auto full screen uses the OK handoff, runs only while a grid cell has focus with search closed, and never refires for the channel the player was just left on until focus moves. It never fires unless the activity is STARTED, and ON_START restarts the rest muted. Keep the timing decisions in the class, not in effects.
- Tag media items with the channel id as `mediaId` through `mediaItemFor`, and set the MIME type from the descriptor; never let Media3 sniff a session URL.
- Renew well inside the 45 s preview lease (15 s). Release on channel change, Settings and ON_STOP; reopen on ON_START. Reopen on `replaced` and on a 410 from the relay by re-reading the session, not by retrying the old URL.
- Session opens run under NonCancellable and a canceled tune releases what it opened. Never leak a session: capacity is small and the oldest preview is evicted.
- Gate tune preparation on STARTED. Stop on ON_STOP and Settings; retain paused-user intent on return.
- Preserve preview debounce and stream handoff. A zap waits `ZAP_SETTLE_MS` for presses to rest (the first tune does not), then stops, resolves the session, sets the media item and prepares.
- Reconnect failed/ended streams with the existing backoff. Do not add a watchdog that reconnects stalled-but-open streams. A `CHANNEL_NOT_FOUND` session error reloads the lineup and follows the channel by number, then name; never retry a dead id with the backoff.
- PlayerScreen owns MediaSession; there is no background playback service.
- Preserve CEA-608/708 selection, caption geometry and per-channel timing. Update renderer-thread settings from SideEffect through volatile fields. The preview draws the same `Captions` composable as the full screen, scaled to its box with no overscan inset, gated on the CC setting; both screens call `preferCaptionTrack` on track changes.
- When wrapping Media3 Java interfaces, forward every required overload explicitly; do not use Kotlin by delegation.
- Keep sockets and files off the main thread. Preserve cancellation and close owned calls/listeners/resources.
- Keep DataStore corruption recovery and last-good lineup behavior. Never log or print the API token; `AppSettings.toString` hides it and Settings masks all but its last four characters while the field is not being edited.
- API requests go through `SofaKingApi` with the bearer token; media requests carry none. Guide queries stay within 200 channels and 24 hours.
- Use kotlinx.serialization with `wireJson` for the server's JSON; unknown fields are ignored.

## Documentation

Document current facts, setup, controls and troubleshooting. Do not add review diaries, dated benchmark/test-count summaries, design histories or speculative roadmaps. Put change rationale and validation results in commit or pull-request descriptions.
