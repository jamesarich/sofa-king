# Sofa King contributor instructions

Android TV app: one `app` module, Kotlin, Compose for TV and Media3. Read README and docs/ARCHITECTURE.md for current setup and behavior.

## Build

Use JDK 21 and an Android SDK configured in local.properties. On the shared host, run:

```sh
/home/james/.claude/bin/gradle-queue -- :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Use the test fixtures for offline development. Install release builds on TVs only when requested; release APKs use R8 and the local debug key.

## UI

- Use androidx.tv.material3 and foundation lazy layouts. Do not mix mobile Material 3 into TV screens.
- Support the five-way D-pad. Use focusRestorer; request focus from effects or callbacks and wait for lazy nodes with requestFocusWhenAttached.
- Preserve 48dp horizontal and 27dp vertical overscan padding.
- Preserve the guide's 24-hour window, time cursor and saved position across player navigation.
- Keep models immutable. Read time from rememberMinuteNow, not Instant.now during composition.
- Use theme colors, shared motion and resource strings in US English. UI terminology is "program".

## Playback and data

- MainActivity owns one ExoPlayer. Preview and full-screen screens borrow it; neither releases it.
- Gate tune preparation on STARTED. Stop on ON_STOP and Settings; retain paused-user intent on return.
- Preserve preview debounce and stream handoff. Zap with stop, setMediaItem, prepare.
- Reconnect failed/ended streams with the existing backoff. Do not add a watchdog that reconnects stalled-but-open streams.
- PlayerScreen owns MediaSession; there is no background playback service.
- Preserve CEA-608/708 selection, caption geometry and per-channel timing. Update renderer-thread settings from SideEffect through volatile fields.
- When wrapping Media3 Java interfaces, forward every required overload explicitly; do not use Kotlin by delegation.
- Keep sockets and files off the main thread. Preserve cancellation and close owned calls/listeners/resources.
- Keep DataStore corruption recovery and last-good lineup behavior.
- Network endpoints are /output/m3u/PROFILE and /output/epg/PROFILE?days=1. The native backend API is not integrated.

## Documentation

Document current facts, setup, controls and troubleshooting. Do not add review diaries, dated benchmark/test-count summaries, design histories or speculative roadmaps. Put change rationale and validation results in commit or pull-request descriptions.
