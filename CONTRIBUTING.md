# Contributing

This guide covers how the repository is laid out, the conventions the app, the server and the contract share, and the CI that checks them. Each part's own build, tests and code rules are in its development page:

- The app: [development](app/docs/development.md) has the build and test commands, running each platform and the checks on a TV. [app/AGENTS.md](app/AGENTS.md) lists its code constraints.
- The server: [development](backend/docs/development.md) has the build and test commands, the smoke scripts and adding a native provider. [backend/AGENTS.md](backend/AGENTS.md) lists its code constraints.
- The contract: [its README](contract/README.md) has the rules for changing a wire type.

## Repository layout

The repository holds three Gradle builds, each with its own wrapper and lockfiles. Build each from its own directory:

| Directory | Build | Checks |
| --- | --- | --- |
| [`app/`](app/README.md) | The Kotlin Multiplatform app | [Build and test](app/docs/development.md#build-and-test) |
| [`backend/`](backend/README.md) | The Ktor server and its Docker image | [Build and test](backend/docs/development.md#build-and-test) |
| [`contract/`](contract/README.md) | The wire types both include | `./gradlew jvmTest assemble` |

They share these files at the root:

- `gradle/libs.versions.toml`, the one version catalog, so a Kotlin or kotlinx.serialization bump moves every build at once.
- `gradle/repositories.settings.gradle.kts` and `gradle/build-cache.settings.gradle.kts`, the repositories and the remote build cache every build's settings apply.
- `build-logic/`, the convention plugins: `sofaking.kotlin` (dependency locking, the JDK 21 toolchain and the web toolchain repositories) and `sofaking.android` (the SDK levels).
- `settings.gradle.kts`, which includes all three builds for one IDE import only; CI never builds from the root.
- `gradle/update-locks.sh`, which rewrites every build's lockfiles after a version change.
- [`renovate.json`](renovate.json), which proposes the dependency, wrapper, base image and workflow action updates of every build.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, in the imperative mood, for example `fix(player): back off on the lineup reload` or `feat(providers): resolve the guide id for a channel`. The scope names the area:

- App: `guide`, `player`, `preview`, `captions`, `settings`, `data`, `web`, `ios`, `desktop`.
- Server: the package or area, such as `providers`, `policy`, `hdhr`, `logos`, `playback` or `backend`.
- Anywhere: `build` and `ci` for the builds and workflows, and `docs` for documentation-only changes.

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

Versions are pinned in the shared catalog and resolved versions are locked in every build. The server and the contract also check every artifact against their `gradle/verification-metadata.xml`. Renovate opens update pull requests, and the `lockfiles.yml` workflow rewrites their lockfiles and verification metadata before CI checks them. Each part's dependencies page lists its versions and the update steps: [app](app/docs/dependencies.md), [server](backend/docs/dependencies.md), and the [contract's README](contract/README.md).

## CI

| Workflow | Runs for | What it does |
| --- | --- | --- |
| [`ci.yml`](.github/workflows/ci.yml) | Pull requests and pushes to `main` that change `app/` or `contract/` (not their Markdown or `app/docs/`), the shared `gradle/` directory or `build-logic/`, `ci.yml` or `snapshot.yml`; also a manual run | Tests and builds the app, and publishes the snapshot from `main` |
| [`backend.yml`](.github/workflows/backend.yml) | Pull requests and pushes to `main` that change `backend/` or `contract/` (not their Markdown or `backend/docs/`), the shared `gradle/` directory or `build-logic/`, or `backend.yml`; also a manual run | Tests and builds the server, and publishes its image from `main` |
| [`lockfiles.yml`](.github/workflows/lockfiles.yml) | Renovate pull requests that change the catalog, a Gradle wrapper or a settings file; also a manual run on any branch but `main` | Rewrites the lockfiles and verification metadata, commits them to the branch and runs `ci.yml` and `backend.yml` on that commit |

`ci.yml` runs up to three jobs:

- `check` runs the app's tests, lint, the Native test compile, the release APK and the wasm web build on Ubuntu. It uploads the test reports when it fails and the `RenderSmokeTest` screenshots every time.
- `apple` runs on every event except a pull request, so on pushes to `main` and manual runs. It runs the data layer's tests on iOS, macOS, tvOS and watchOS simulators, the UI's iOS tests, and links the iOS framework on macOS. A pull request compiles the iOS tests on Linux in `check` instead.
- `snapshot` runs for a push to `main` once both pass. It calls `snapshot.yml`, which builds every platform but iOS and replaces the [`snapshot` prerelease](https://github.com/jamesarich/sofa-king/releases/tag/snapshot), as [CI and snapshots](app/docs/development.md#ci-and-snapshots) describes. `snapshot.yml` also runs on its own from the Actions tab.

`lockfiles.yml` runs `gradle/update-locks.sh` on macOS, then `backend/scripts/update-verification-metadata.sh` on Ubuntu, both with a read-only token. A third job commits their changes as `build(deps): refresh the lockfiles and verification metadata` and starts CI and Backend on it, because a push made with the workflow token starts no workflows. `renovate.json` lists that commit's author in `gitIgnoredAuthors`, so Renovate keeps rebasing the branch, and each rebase runs the workflow again.

`backend.yml` runs three jobs:

- `test` runs `./gradlew test :server:installDist` on Ubuntu 24.04 with its FFmpeg 6.1, the version the image ships. FFmpeg's output differs between major versions, so a test that passes against a newer local FFmpeg can still fail there.
- `image` builds the image from the tested distribution for `linux/amd64` and `linux/arm64`, each on a runner of its own architecture. A pull request builds both without pushing them.
- `publish` runs for `main`, on a push or a manual run, once both images are built. It tags them together as `ghcr.io/jamesarich/sofa-king-backend:main` and `:sha-<commit>`, with a BuildKit SBOM and max-mode build provenance, which [deploy](backend/docs/deploy.md#inspect-the-published-image) shows how to inspect. Hosts with [automatic updates](backend/docs/deploy.md#update-automatically) install that image.

## Security

Report vulnerabilities privately, as [SECURITY.md](SECURITY.md) describes.
