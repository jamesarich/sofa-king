# Development

## Build and test

Use JDK 21, the Android SDK with API 37.1 and the checked-in Gradle wrapper. Set `sdk.dir` in the ignored `local.properties` file; see [install](../README.md#install).

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Unit tests cover the API client, catalog and guide mapping, the playback session lifecycle, cache, settings, captions, remote-control key mapping, the launch and focus decisions, channel re-resolution, the preview's rest timers and network cancellation. Server interactions run against MockWebServer, so no server is needed.

## Test on a TV

Check these on a real Android TV or Google TV device before a change that touches playback, focus or the remote:

- [ ] Cold start opens the last watched channel, and Back shows the guide focused on it.
- [ ] The Guide, Menu and Search keys work (the launcher may claim Guide on some devices), and voice search finds a channel.
- [ ] D-pad focus moves through the guide without losing its place, including rapid focus changes over the preview, and the stream carries over between preview, player and guide.
- [ ] The preview is muted, unmutes at 5 seconds and opens full screen at 10 seconds, and captions show over it.
- [ ] Background and foreground transitions resume the same channel.
- [ ] Audio, captions and decoder recovery work on the full-screen player.

## Dependencies

Exact versions are in [the version catalog](../gradle/libs.versions.toml). AGP supplies built-in Kotlin; the Compose compiler plugin has a matching preview pin. Resolved dependencies are locked and the wrapper has a pinned checksum.

For dependency updates, change the version catalog, run the checks above with `--write-locks`, and review the lockfile diff. Run the same checks without that flag before committing.

Release APKs use the local debug key. Distribution requires a release signing configuration.

## Banner

Regenerate the TV banner after changing the vector mark. Requires ImageMagick and Noto Sans:

```sh
tools/render-banner.sh
```

## Conventions

Use [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) and the TV constraints in [CLAUDE.md](../CLAUDE.md). Keep current behavior in documentation and change rationale in commits or pull requests.
