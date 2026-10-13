# Contributing

Thanks for working on Sofa King. This guide covers the build, the tests, CI, and commit and pull request conventions. The [docs index](docs/README.md) lists the rest of the documentation, and [architecture](docs/architecture.md) describes how the code is organized.

## Build and test

You need JDK 21 and the Android SDK with API 37.1, set in `local.properties`. Run from the repository root:

```sh
./gradlew :core:allTests :ui:jvmTest :ui:testAndroidHostTest :ui:jsNodeTest :androidApp:lintDebug :androidApp:assembleRelease
./gradlew :core:compileTestKotlinLinuxX64 :ui:compileTestKotlinIosSimulatorArm64
```

The second line compiles the Native tests, which reject what the JVM accepts, such as commas or parentheses in backtick test names and JVM-only APIs; run it after any change to common code. Unit tests run against MockWebServer and fakes, so no Sofa King server is needed. [Development](docs/development.md) covers what the tests cover and how to run each platform.

A change to playback, focus or the remote also needs the [checks on a TV](docs/development.md#test-on-a-tv): the tests cannot see focus, the remote's keys or a real decoder.

The [CI workflow](.github/workflows/ci.yml) runs on every pull request and every push to `main`, in up to three jobs:

- `check` runs both lines above, plus the wasm web build, on Ubuntu. It uploads the test reports when it fails and the `RenderSmokeTest` screenshots every time.
- `apple` runs only for `main`. It runs the data layer's tests on iOS, macOS, tvOS and watchOS simulators, the UI's iOS tests, and links the iOS framework on macOS.
- `snapshot` runs only for `main`, after both pass. It builds every platform and replaces the [`snapshot` prerelease](../../releases/tag/snapshot), as [CI and snapshots](docs/development.md#ci-and-snapshots) describes.

Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) and the constraints in [CLAUDE.md](CLAUDE.md). Put code in `commonMain` unless it needs a platform API, keep `core` free of Compose, preserve coroutine cancellation, and close the sessions, players and HTTP calls you own.

## Commits and pull requests

- Use [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, in the imperative mood, for example `fix(player): back off on the lineup reload` or `feat(guide): jump to the row for a typed number`. Use the area as the scope (`guide`, `player`, `preview`, `captions`, `settings`, `data`, `web`, `ios`, `desktop`) and `docs` for documentation-only changes.
- Keep one change per pull request, and describe why it is needed. The diff already shows what changed.
- Add or update tests with any behavior change. Name tests for the behavior they check.
- List the checks you ran, including the TV checks when they apply, and add before and after screenshots for a visible change.
- Keep documentation describing the current behavior, in the same pull request. Put the reasoning behind a change in the commit or the pull request.

## Dependencies

Versions are pinned in `gradle/libs.versions.toml` and resolved versions are locked. A dependency change updates the catalog, the lockfiles and the table in [dependencies](docs/dependencies.md), which lists the update steps.

## Security

Report vulnerabilities privately, as [SECURITY.md](SECURITY.md) describes.
