# Development

## Build and test

Use JDK 21, the Android SDK with API 37.1 and the checked-in Gradle wrapper. Set `sdk.dir` in the ignored `local.properties` file; see [build and install](../README.md#build-and-install).

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

On the shared development host, use `/home/james/.claude/bin/gradle-queue --` in place of `./gradlew`.

Unit tests cover the API client, catalog and guide mapping, the playback session lifecycle, cache, settings, captions, remote-control key mapping, the launch and focus decisions, channel re-resolution, the preview's rest timers and network cancellation. Server interactions run against MockWebServer, so no server is needed.

On a TV, also check the cold start into the last channel, Back to the guide, the Guide, Menu and Search keys (the launcher may claim Guide on some devices), voice search, the preview's sound at 5 s and full screen at 10 s, and captions over the preview.

On a TV, check D-pad focus, rapid preview changes, preview/player/guide handoff, background/foreground transitions, audio, captions and decoder recovery.

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
