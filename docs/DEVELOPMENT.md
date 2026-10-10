# Development practices

## Build and review

Use JDK 21, the Android SDK with API 37.1 and the checked-in Gradle wrapper. AGP provides built-in Kotlin; the Compose compiler plugin uses the matching Kotlin preview. Versions live in [the version catalog](../gradle/libs.versions.toml), resolved dependencies use the checked-in Gradle lockfile, and the wrapper distribution has a pinned checksum. When changing dependencies, run the checks with `--write-locks` and review the lockfile diff. Preview combinations require build and runtime validation; release notes alone do not establish compatibility.

Run these checks after changing code:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

On James's shared host, replace `./gradlew` with `/home/james/.claude/bin/gradle-queue --` to use the build queue. Release APKs use the local debug key for household testing. A distributable release needs its own signing configuration.

Unit tests cover parsers, cache, settings, caption and remote-control logic, and cancellation of a stalled network body. Lint checks Android API usage. Builds and JVM tests do not establish TV focus, lifecycle, audio or caption correctness: exercise preview → player → guide, rapid focus changes, background/foreground and caption selection on the target TV.

## Code conventions

Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) and the project-specific TV rules in [CLAUDE.md](../CLAUDE.md). Keep blocking work off the main thread, inject dispatchers where tests need control, propagate coroutine cancellation and close sockets and owned resources. Use lifecycle-aware flow collection and gate player preparation on STARTED. See [Android coroutine practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices) and [architecture recommendations](https://developer.android.com/topic/architecture/recommendations).

The app owns one ExoPlayer. Composition effects remove listeners, and MainActivity stops playback on ON_STOP and releases the player on disposal. Keep preview handoff and paused-user intent intact. Platform-dependent behavior belongs in device checks; pure decisions belong in JVM tests.

## Documentation conventions

Use the [GitHub documentation style guide](https://docs.github.com/en/contributing/style-guide-and-content-model/style-guide) for clarity, sentence-case headings, descriptive links and numbered procedures. Label fenced examples with their language, place explanations before samples and use uppercase placeholders. Use ordinary repository Markdown; GitHub Docs' Liquid templates do not apply here.

README explains setup and controls. Architecture documents implemented behavior. Plans identify future work explicitly. Update the relevant document in the same change as code. Keep dated measurements in review records, and avoid presenting test counts or timing samples as lasting guarantees.

See the [October 10 review](REVIEW-2026-10-10.md) for findings and dated validation.

## Native backend integration

The backend and Android app currently use compatible Kotlin preview pins, but the app still reads profile M3U/XMLTV endpoints. Integration must consume native capabilities and leased descriptors, migrate numeric favorite/last-channel keys through `legacyGuideId`, and reopen media when its descriptor epoch changes. Verify cancellation and preview promotion on a TV before cutting over the household.
