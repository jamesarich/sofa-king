# Contributing

This guide covers how the repository is laid out, the conventions the app, the server and the contract share, and the CI that checks them. Each part's own build, tests and code rules are in its development page:

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
- `settings.gradle.kts`, which includes all three builds for one IDE import only; CI never builds from the root.
- [`renovate.json`](renovate.json), which proposes the dependency, wrapper, base image and workflow action updates of every build.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, in the imperative mood, for example `fix(player): back off on the lineup reload` or `feat(providers): resolve the guide id for a channel`. The scope names the area:

- App: `guide`, `player`, `preview`, `captions`, `settings`, `data`, `web`, `ios`, `tvos`, `desktop`.
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
| [`ci.yml`](.github/workflows/ci.yml) | Pull requests and pushes to `main` that change `app/` or `contract/` (not their Markdown or `app/docs/`), the shared `gradle/` directory or `build-logic/`, `ci.yml` or `snapshot.yml`; also a manual run | Tests and builds the app, and publishes the snapshot from `main` |
| [`backend.yml`](.github/workflows/backend.yml) | Pull requests and pushes to `main` that change `backend/` or `contract/` (not their Markdown or `backend/docs/`), the shared `gradle/` directory or `build-logic/`, or `backend.yml`; also a manual run | Tests and builds the server, and publishes its image from `main` |

`ci.yml` runs up to three jobs:

- `check` runs the app's tests, lint, the Native test compile, the release APK and the wasm web build on Ubuntu. It uploads the test reports when it fails and the `RenderSmokeTest` screenshots every time.
- `apple` runs the data layer's tests on iOS, macOS, tvOS and watchOS simulators and the UI's iOS tests, links the iOS and tvOS frameworks, and builds the two SwiftUI hosts in `app/iosApp` and `app/tvosApp` with XcodeGen and `xcodebuild`, on macOS.
- `snapshot` runs for a push to `main` once both pass. It calls `snapshot.yml`, which builds every platform but iOS and Apple TV and replaces the [`snapshot` prerelease](https://github.com/jamesarich/sofa-king/releases/tag/snapshot), as [CI and snapshots](app/docs/development.md#ci-and-snapshots) describes. `snapshot.yml` also runs on its own from the Actions tab.

Every Gradle job restores the Gradle user home that the same job last saved from `main` (pull requests only read it), and the app jobs keep `~/.konan`, the Kotlin/Native distribution, per Kotlin version. Pushes to `main` also write the remote build cache in `gradle/build-cache.settings.gradle.kts`, which every other build reads. The configuration cache travels with the Gradle user home only when the `GRADLE_ENCRYPTION_KEY` repository secret holds a key from `openssl rand -base64 16`; without it every run configures the build from scratch, about half a minute per job.

`backend.yml` runs three jobs:

- `test` runs `./gradlew test :server:installDist` on Ubuntu 24.04 with its FFmpeg 6.1, the version the image ships. FFmpeg's output differs between major versions, so a test that passes against a newer local FFmpeg can still fail there. It then runs `shellcheck` over `backend/deploy` and the unit tests in `backend/scripts`.
- `image` builds the image from the tested distribution for `linux/amd64` and `linux/arm64`, each on a runner of its own architecture. A pull request builds both without pushing them.
- `publish` runs for `main`, on a push or a manual run, once both images are built. It tags them together as `ghcr.io/jamesarich/sofa-king:main` and `:sha-<commit>`, with a BuildKit SBOM and max-mode build provenance, which [deploy](backend/docs/deploy.md#inspect-the-published-image) shows how to inspect. Hosts with [automatic updates](backend/docs/deploy.md#update-automatically) install that image.

## Security

Report vulnerabilities privately, as [SECURITY.md](SECURITY.md) describes.
