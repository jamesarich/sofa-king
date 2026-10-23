# Contributing

Contributions are Sofa King welcome. This guide covers how the repository is laid out, the conventions the app, the server and the contract share, and the CI that checks them. Each part's own build, tests and code rules are in its development page:

- The app: [development](app/docs/development.md) has the build and test commands, running each platform and the checks on a TV. [app/AGENTS.md](app/AGENTS.md) lists its code constraints.
- The server: [development](backend/docs/development.md) has the build and test commands, the smoke scripts and adding a native provider. [backend/AGENTS.md](backend/AGENTS.md) lists its code constraints.
- The contract: [its README](contract/README.md) has the rules for changing a wire type.

## Repository layout

The repository holds three Gradle builds, each with its own wrapper. Build each from its own directory:

| Directory | Build | Checks |
| --- | --- | --- |
| [`app/`](app/README.md) | The Kotlin Multiplatform app | [Build and test](app/docs/development.md#build-and-test) |
| [`backend/`](backend/README.md) | The Ktor server and its Docker image | [Build and test](backend/docs/development.md#build-and-test) |
| [`contract/`](contract/README.md) | The wire types both include | `./gradlew jvmTest assemble` |

They share these files at the root:

- `gradle/libs.versions.toml`, the one version catalog, so a Kotlin or kotlinx.serialization bump moves every build at once.
- `gradle/repositories.settings.gradle.kts` and `gradle/build-cache.settings.gradle.kts`, the repositories and the remote build cache every build's settings apply.
- `build-logic/`, the convention plugins: `sofaking.kotlin` (the JDK 21 toolchain and the web toolchain repositories) and `sofaking.android` (the SDK levels).
- `settings.gradle.kts`, which includes all three builds for one IDE import only, with the `gradle.properties` and the `.run/` configurations that import uses; CI never builds from the root. The app's [development](app/docs/development.md#android-studio) page covers opening it in Android Studio.
- [`renovate.json`](renovate.json), which proposes the dependency, wrapper, base image and workflow action updates of every build.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, in the imperative mood, for example `fix(player): back off on the lineup reload` or `feat(providers): resolve the guide id for a channel`. The scope names the area:

- App: `app` (the shell, navigation and the tabs), `guide`, `player`, `preview`, `captions`, `settings`, `library`, `data`, `web`, `ios`, `tvos`, `desktop`.
- Server: the package or area, such as `providers`, `policy`, `hdhr`, `logos`, `playback`, `admin`, `server` or `backend`.
- Anywhere: `build` and `ci` for the builds and workflows, `deps` for Renovate's dependency updates, and `docs` for documentation-only changes.

## Pull requests

- Keep one change per pull request. Describe why it is needed first; the diff already shows what changed.
- Add or update tests with any behavior change. Name tests for the behavior they check.
- Keep documentation describing the current behavior, in the same pull request, including any changed route, setting, control or variable. The server's [configuration table](backend/docs/live-tv.md#server-settings) is the one place its environment variables are listed. Put the reasoning behind a change in the commit or the pull request.
- List the checks you ran, including the [checks on a TV](app/docs/development.md#test-on-a-tv) for a change to the app's playback, focus or remote, and add before and after screenshots for a visible change.
- Leave tokens, cookies, device identifiers, server addresses and private URLs out of code, fixtures, logs, screenshots and descriptions. The repository is public.
- Pull requests are squash-merged once CI is green at the head.

## Wire changes

A request or response type the app and the server share lives in `contract/`, never in either part. A change to it lands with both sides in one pull request, follows the rules in the [contract's README](contract/README.md), and keeps every fixture in `contract/src/jvmTest/resources/wire`: never edit or delete one. Released apps run against newer servers, so the server must keep answering what those fixtures hold.

## Dependencies

Versions are pinned exactly in the shared catalog, with no lockfiles or verification metadata, so an update pull request changes only the pin. Renovate opens those pull requests and merges minor, patch, pin and digest updates at least three days old once every check passes; Kotlin, the Android Gradle plugin and Compose Multiplatform move together in one pull request that a person merges. Each part's dependencies page lists its versions and the update steps: [app](app/docs/dependencies.md), [server](backend/docs/dependencies.md), and the [contract's README](contract/README.md).

## CI

| Workflow | Runs for | What it does |
| --- | --- | --- |
| [`ci.yml`](.github/workflows/ci.yml) | Pull requests and pushes to `main` that change `app/` or `contract/` (not their Markdown or `app/docs/`), the shared `gradle/` directory or `build-logic/`, `ci.yml`, `snapshot.yml`, `publish-snapshot.yml` or `.github/version.sh`; also a manual run | Tests and builds the app, runs the [Apple TV remote tests](app/docs/development.md#apple-tv-remote-tests) and the [web key fuzz](app/docs/development.md#web-key-fuzz), and publishes the snapshot from `main` |
| [`backend.yml`](.github/workflows/backend.yml) | Pull requests and pushes to `main` that change `backend/` or `contract/` (not their Markdown or `backend/docs/`), the shared `gradle/` directory or `build-logic/`, `backend.yml` or `.github/version.sh`; also a manual run | Tests and builds the server, and publishes its image from `main` |
| [`screenshots.yml`](.github/workflows/screenshots.yml) | Pull requests that change it, `app/scripts/screenshots.py`, `app/scripts/showcase.py` or `app/scripts/fixture_server.py`, and a manual run | Takes the documentation's screenshots on the web, the Linux desktop, Android phone, tablet and TV emulators and iPhone, iPad and Apple TV simulators against the showcase lineup, as [the app's development page](app/docs/development.md#screenshots) describes |
| [`tv-monkey.yml`](.github/workflows/tv-monkey.yml) | Nightly, pull requests that change it, `app/scripts/tv-monkey.py` or `app/scripts/fixture_server.py`, and a manual run with a key count and a seed | Presses random remote keys on the debug app in a headless Android TV emulator against fixture channels, as [the app's development page](app/docs/development.md#tv-emulator-monkey) describes |
| [`desktop-monkey.yml`](.github/workflows/desktop-monkey.yml) | Nightly, pull requests that change it, `app/scripts/desktop-monkey.py` or `app/scripts/fixture_server.py`, and a manual run with a key count and a seed | Presses random keys on the desktop app in a virtual X display, with libVLC playing fixture channels, as [the app's development page](app/docs/development.md#desktop-monkey) describes |
| [`dependency-submission.yml`](.github/workflows/dependency-submission.yml) | Pushes to `main` that change any build; also a manual run | Sends each build's resolved dependencies to GitHub, so Dependabot alerts cover them |

`ci.yml` runs up to four jobs:

- `check` runs the app's tests, lint, the Native test compile, the release APK, the Android screenshot tests and both production web builds on Ubuntu, then presses random keys on both web builds in Chrome against fixture channels. It uploads the test reports when it fails and the `RenderSmokeTest` screenshots every time.
- `apple` runs the data layer's tests on iOS, macOS, tvOS and watchOS simulators and the UI's iOS tests, links the iOS and tvOS frameworks, and builds the two SwiftUI hosts in `app/iosApp` and `app/tvosApp` with XcodeGen and `xcodebuild`, on macOS.
- `snapshot` runs for a push to `main`, alongside the two checks. It calls `snapshot.yml`, which builds every platform but iOS and Apple TV as [CI and snapshots](app/docs/development.md#ci-and-snapshots) describes. `snapshot.yml` also runs on its own from the Actions tab, where it publishes by itself.
- `publish` runs once the checks and the packages are all green. It calls `publish-snapshot.yml`, which replaces the [`snapshot` prerelease](https://github.com/jamesarich/sofa-king/releases/tag/snapshot) with the packages the run uploaded, signed as a flat apt repository when the `APT_SIGNING_KEY` secret is set.

Every Gradle job restores the Gradle user home that the same job last saved from `main` (pull requests only read it), and the app jobs keep `~/.konan`, the Kotlin/Native distribution, per Kotlin version. Pushes to `main` also write the remote build cache in `gradle/build-cache.settings.gradle.kts`, which every other build reads. The configuration cache is not kept between runs: `setup-gradle` stores it only for a Develocity trial, so every run configures the build from scratch, 15 to 45 seconds per job and about 70 on Windows.

`backend.yml` runs three jobs:

- `test` runs `./gradlew test :server:installDist` on Ubuntu 24.04 with its FFmpeg 6.1, the version the image ships, and Comskip. FFmpeg's output differs between major versions, so a test that passes against a newer local FFmpeg can still fail there. Each test fails after a minute unless it sets a longer `@Timeout`. The job then runs the contract's `jvmTest`, which checks the [wire fixtures](contract/README.md) older apps rely on (the server's `test` does not reach the included build), `shellcheck` over `backend/deploy`, the unit tests in `backend/scripts`, and both [smoke scripts](backend/docs/development.md#build-and-test) against the installed distribution, which need no network, then fuzzes every documented route with Schemathesis.
- `image` builds the image from the tested distribution for `linux/amd64` and `linux/arm64`, each on a runner of its own architecture, then starts it and checks `/readyz`, `/healthz` and a clean stop. A pull request builds both without pushing them.
- `publish` runs for `main`, on a push or a manual run, once both images are built. It tags them together as `ghcr.io/jamesarich/sofa-king:main` and `:sha-<commit>`, with a BuildKit SBOM and max-mode build provenance, which [deploy](backend/docs/deploy.md#inspect-the-published-image) shows how to inspect. Hosts with [automatic updates](backend/docs/deploy.md#update-automatically) install that image.

## Security

Report vulnerabilities privately, as [SECURITY.md](SECURITY.md) describes.
