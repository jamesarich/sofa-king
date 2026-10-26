---
name: cloud-build
description: Build, lint and test Sofa King in a cloud container before pushing, so CI confirms a change instead of compiling it; covers the Android SDK, Maven Central 429s and what only CI can check.
---

# Build and check in a cloud container

Run the checks for what you changed before every push. A push that fails to compile costs a full CI run (10 to 30 minutes, two macOS slots for the app) and a red check on the pull request; batch fixes into one push.

## Prepare (once per container)

The environment's setup script installs these; check, and fill in what is missing:

- Android SDK: `ls /opt/android-sdk/platforms` shows `android-37.1`, and `ANDROID_HOME=/opt/android-sdk` is set (or `app/local.properties` has `sdk.dir=/opt/android-sdk`; the file is ignored by Git). Otherwise install `cmdline-tools` from `dl.google.com`, then `sdkmanager "platforms;android-37.1" "build-tools;37.0.0" "platform-tools"`.
- `ktlint` 1.8.0 and `/opt/detekt-cli.jar` (detekt 2.0.0-alpha.6) from their GitHub releases, `ffmpeg` and `shellcheck`.
- Maven Central answers HTTP 429 to Gradle's parallel downloads from the shared egress. When the setup script installed `~/.gradle/init.d/central-mirror.init.gradle.kts`, Gradle resolves Central through Google's mirror and builds work. Without it, do not write or pass a mirror init script yourself; use the lint CLIs below and the narrowest Gradle task, and say in the pull request which checks only CI ran.

Run Gradle with `--max-workers=2` and one build at a time; after the first full resolution, `--offline` reuses the cache.

## What to run

Server change, from `backend/`:

```sh
./gradlew --max-workers=2 spotlessCheck detekt :server:test --tests '<the test classes you touched>'
./gradlew --max-workers=2 test :server:installDist   # before the pull request leaves draft
```

App change, from `app/`, the tasks of the code you touched (CI's Linux job runs all of them):

```sh
./gradlew --max-workers=2 spotlessCheck detekt :core:jvmTest :ui:jvmTest --tests '<class>'
./gradlew --max-workers=2 :core:compileTestKotlinLinuxX64 :ui:compileTestKotlinIosSimulatorArm64   # common code
./gradlew --max-workers=2 :androidApp:testDebugScreenshotTestDefaultTestSuite                      # a visible change
```

Run `ui:jvmTest` in batches of about ten classes; the whole suite runs out of memory in a container. A changed screen needs its screenshot references rewritten (`:androidApp:updateDebugScreenshotTestDefaultTestSuite`) and committed with it.

When Gradle cannot resolve, lint without it, from `app/` or `backend/` so the root `.editorconfig` applies, on the files you changed:

```sh
ktlint <changed .kt files>             # --format to fix
java -jar /opt/detekt-cli.jar --input <changed dirs> --config ../gradle/detekt.yml --build-upon-default-config
```

The detekt CLI lacks the Compose rules, so the Gradle `detekt` task still has the last word.

## What only CI checks

- Swift, the iOS and tvOS hosts, XCUITest walks and Apple simulator tests: macOS only. A Kotlin rename changes its Objective-C facade (`<File>Kt`), so grep `app/iosApp` and `app/tvosApp` for the old name.
- The production web bundles (`:ui:wasmJsBrowserDistribution :ui:jsBrowserDistribution`) and Wasm browser tests: slow, and Kotlin's karma fork downloads from `codeload.github.com`, which the container cannot reach.
- The API fuzz (`python3 scripts/api-fuzz.py`) and the smoke scripts need a built distribution; run them when you change a route or the OpenAPI document.

When CI goes red anyway, use the `ci-failure` skill before pushing again.
