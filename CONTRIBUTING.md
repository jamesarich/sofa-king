# Contributing

Contributions are Sofa King welcome, from everyone who follows the [code of conduct](CODE_OF_CONDUCT.md). This guide covers how the repository is laid out, the conventions the app, the server and the contract share, and the CI that checks them. Each part's own build, tests and code rules are in its development page:

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
- `build-logic/`, the convention plugins: `sofaking.kotlin` (the JDK 21 toolchain and the web toolchain repositories), `sofaking.android` (the SDK levels) and `sofaking.lint` (formatting and static analysis).
- `.editorconfig`, the code style the IDE and ktlint share.
- `settings.gradle.kts`, which includes all three builds for one IDE import only, with the `gradle.properties` and the `.run/` configurations that import uses; CI never builds from the root. The app's [development](app/docs/development.md#android-studio) page covers opening it in Android Studio.
- [`renovate.json`](renovate.json), which proposes the dependency, wrapper, base image and workflow action updates of every build.

## Formatting and static analysis

Kotlin follows IntelliJ IDEA's Kotlin style, which the root `.editorconfig` sets for the IDE and for [ktlint](https://pinterest.github.io/ktlint/), with lines up to 120 characters and no wildcard imports. The `sofaking.lint` convention applies [Spotless](https://github.com/diffplug/spotless) with ktlint to every project's Kotlin sources and Gradle scripts, and [detekt](https://detekt.dev/) to its Kotlin sources, with the [Compose rules](https://mrmans0n.github.io/compose-rules/) where the project uses Compose. `gradle/detekt.yml` holds what differs from detekt's defaults, and `gradle/detekt-compose.yml` the Compose rules' settings, each change with its reason. From `app/`, `backend/` or `contract/`:

```sh
./gradlew spotlessApply   # format
./gradlew spotlessCheck   # fail on unformatted code or a finding ktlint cannot fix, such as a long line
./gradlew detekt          # static analysis
```

CI runs `spotlessCheck` and `detekt` in every build. Fix a finding in the code rather than suppressing it; where a rule is wrong for one declaration, `@Suppress` that one rule there, with a comment saying why. There is no baseline, and no build turns a rule off.

Catch the exceptions a call can throw, such as `IOException` or `SerializationException`. Where a failure of any kind is logged or shown and the work goes on, such as a background loop's next pass or a screen's load, use `runCatchingCancellable` (the server's `Results.kt`, the app's `data/Failures.kt`): it rethrows `CancellationException`, so canceling still cancels, and never catches an `Error`. To clean up after a failure and let it go on, use `runCatching { … }.onFailure { … }.getOrThrow()`, or `finally`.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, in the imperative mood, for example `fix(player): back off on the lineup reload` or `feat(providers): resolve the guide id for a channel`. The scope names the area:

- App: `app` (the shell, navigation and the tabs), `guide`, `player`, `preview`, `captions`, `settings`, `library`, `data`, `web`, `ios`, `tvos`, `desktop`.
- Server: the package or area, such as `providers`, `policy`, `hdhr`, `logos`, `playback`, `admin`, `server` or `backend`.
- Anywhere: `build` and `ci` for the builds and workflows, `deps` for Renovate's dependency updates, and `docs` for documentation-only changes.

The squash-merge title is the release note: the `snapshot` release lists every `feat`, `fix` and `perf` title since the previous snapshot, rendered by [git-cliff](https://git-cliff.org) with `.github/cliff.toml`, so write the summary for someone using the app or the server. The app's update check shows the same lines, without the scope and the pull request, as What's new. Other types stay out of the notes. A breaking change (`feat!:`, or a `BREAKING CHANGE:` footer) goes under Upgrade notes whatever its type; say there what someone updating has to do.

A release is a `v*` tag on `main` whose version matches `sofaking` in the version catalog: bump the catalog in a pull request, merge it, then push the tag (`git tag -a v0.2.0 -m "Highlights"`). The tag's message heads the release notes. A Flyway migration bumps at least the minor version, since a host cannot move back past it, and gets an upgrade note.

## Pull requests

- Write in US English, in prose and in names; the root [AGENTS.md](AGENTS.md) lists the external names that keep their British spelling.
- Keep one change per pull request. Describe why it is needed first; the diff already shows what changed.
- Add or update tests with any behavior change. Name tests for the behavior they check.
- Keep documentation describing the current behavior, in the same pull request, including any changed route, setting, control or variable. The server's [configuration table](backend/docs/live-tv.md#server-settings) is the one place its environment variables are listed. Put the reasoning behind a change in the commit or the pull request.
- List the checks you ran, including the [checks on a TV](app/docs/development.md#test-on-a-tv) for a change to the app's playback, focus or remote, and add before and after screenshots for a visible change.
- Leave tokens, cookies, device identifiers, server addresses and private URLs out of code, fixtures, logs, screenshots and descriptions. The repository is public.
- Pull requests are squash-merged once CI is green at the head.

## Documentation site

The Markdown pages are also the [documentation site](https://jamesarich.github.io/sofa-king/), built with MkDocs and Material for MkDocs from `mkdocs.yml`. The pages stay where they are and read the same on GitHub: [`docs/site/stage.py`](docs/site/stage.py) copies every tracked page and docs image (agent instructions and `.github/` aside) into `build/docs-src`, with the server's OpenAPI document rendered by Scalar as the API reference, and [`docs/site/hooks.py`](docs/site/hooks.py) points links to scripts and other non-page files at GitHub. A new page goes in the `nav` of `mkdocs.yml` as well as its part's index. To build it locally:

```sh
pip install -r docs/site/requirements.txt
python3 docs/site/stage.py && mkdocs serve
```

## Wire changes

A request or response type the app and the server share lives in `contract/`, never in either part. A change to it lands with both sides in one pull request, follows the rules in the [contract's README](contract/README.md), and keeps every fixture in `contract/src/jvmTest/resources/wire`: never edit or delete one. Released apps run against newer servers, so the server must keep answering what those fixtures hold.

## Dependencies

Versions are pinned exactly in the shared catalog, with no lockfiles or verification metadata, so an update pull request changes only the pin. Renovate opens those pull requests and merges minor, patch, pin and digest updates at least three days old once every check passes; Kotlin, the Android Gradle plugin and Compose Multiplatform move together in one pull request that a person merges. Each part's dependencies page lists its versions and the update steps: [app](app/docs/dependencies.md), [server](backend/docs/dependencies.md), and the [contract's README](contract/README.md).

## CI

| Workflow | Runs for | What it does |
| --- | --- | --- |
| [`ci.yml`](.github/workflows/ci.yml) | Pull requests and pushes to `main` that change `app/` or `contract/` (not their Markdown or `app/docs/`), the shared `gradle/` directory, `.editorconfig` or `build-logic/`, `ci.yml`, `snapshot.yml`, `publish-snapshot.yml`, `.github/version.sh` or the `apt-install` action; also a manual run | Tests and builds the app, runs the [Apple TV remote tests](app/docs/development.md#apple-tv-remote-tests), the [iOS touch tests](app/docs/development.md#ios-touch-tests) and the [web key fuzz](app/docs/development.md#web-key-fuzz), and publishes the snapshot from `main` |
| [`backend.yml`](.github/workflows/backend.yml) | Pull requests and pushes to `main` that change `backend/` or `contract/` (not their Markdown or `backend/docs/`), the shared `gradle/` directory, `.editorconfig` or `build-logic/`, `backend.yml`, `.github/version.sh` or the `apt-install` action; every `v*` tag; also a manual run | Tests and builds the server, and publishes its image from `main` and from each tag |
| [`docs.yml`](.github/workflows/docs.yml) | Pull requests and pushes to `main` that change any Markdown page, `docs/`, `mkdocs.yml`, the server's OpenAPI document or `docs.yml`; also a manual run | Builds the [documentation site](https://jamesarich.github.io/sofa-king/) strictly, so a link to a missing page or anchor fails, and publishes it to GitHub Pages from `main` |
| [`release.yml`](.github/workflows/release.yml) | A pushed `v*` tag | Builds the snapshot's packages for that version through `snapshot.yml`, then `publish-release.yml` publishes them as the `vX.Y.Z` release, which GitHub marks the latest; `backend.yml` publishes the image for the same tag |
| [`screenshots.yml`](.github/workflows/screenshots.yml) | Pull requests that change it, `app/scripts/screenshots.py`, `app/scripts/showcase.py`, `app/scripts/fixture_server.py` or the `apt-install` action, and a manual run | Takes the documentation's screenshots on the web, the Linux desktop, Android phone, tablet and TV emulators and iPhone, iPad and Apple TV simulators against the showcase lineup, as [the app's development page](app/docs/development.md#screenshots) describes |
| [`tv-monkey.yml`](.github/workflows/tv-monkey.yml) | Nightly, pull requests that change it, `app/scripts/tv-monkey.py`, `app/scripts/fixture_server.py` or `app/maestro/`, and a manual run with a key count and a seed | Runs the Maestro flows, then presses random remote keys, on the debug app in a headless Android TV emulator against fixture channels and a short fixture show, as [the app's development page](app/docs/development.md#tv-emulator-monkey) describes |
| [`desktop-monkey.yml`](.github/workflows/desktop-monkey.yml) | Nightly, pull requests that change it, `app/scripts/desktop-monkey.py` or `app/scripts/fixture_server.py`, and a manual run with a key count and a seed | Presses random keys on the desktop app in a virtual X display, with libVLC playing fixture channels, as [the app's development page](app/docs/development.md#desktop-monkey) describes |
| [`phone-tests.yml`](.github/workflows/phone-tests.yml) | Nightly, pull requests that change it, `app/androidApp/src/androidTest/` or `app/scripts/fixture_server.py`, and a manual run with a step count and a seed | Runs the debug app's touch tests in a headless Android phone emulator against fixture channels: picture-in-picture, the predictive back gesture, rotation and random touches, as [the app's development page](app/docs/development.md#phone-touch-tests) describes |
| [`codeql.yml`](.github/workflows/codeql.yml) | Weekly, pull requests that change it; also a manual run | Scans the server's Kotlin, with the contract, and the workflows with CodeQL; findings appear under the Security tab |
| [`scorecard.yml`](.github/workflows/scorecard.yml) | Pushes to `main` and weekly; also a manual run | Rates the repository with the OpenSSF Scorecard and publishes the result for the README's badge |
| [`dependency-submission.yml`](.github/workflows/dependency-submission.yml) | Pushes to `main` that change any build; also a manual run | Sends each build's resolved dependencies to GitHub, so Dependabot alerts cover them |

`ci.yml` runs up to five jobs:

- `check` runs the formatting check, the app's tests, Android lint, the Native test compile, the release APK, the Android screenshot tests and both production web builds on Ubuntu, then presses random keys on both web builds in Chrome against fixture channels. It uploads the test reports when it fails and the `RenderSmokeTest` screenshots every time.
- `apple` runs the data layer's tests on iOS, macOS and tvOS simulators and, outside pull requests, on watchOS too and the UI's iOS tests (a pull request compiles both), links the iOS framework, builds the SwiftUI host in `app/iosApp` with XcodeGen and `xcodebuild`, and runs the [iOS touch tests](app/docs/development.md#ios-touch-tests), on macOS. A pull request runs the iOS and Apple TV random walks shorter than `main` does, to save macOS runner time.
- `apple-tv` links the tvOS framework, builds the SwiftUI host in `app/tvosApp` and runs the [Apple TV remote tests](app/docs/development.md#apple-tv-remote-tests) on a second macOS runner, so they run beside `apple` rather than after it.
- `snapshot` runs for a push to `main`, alongside the three checks. It calls `snapshot.yml`, which builds every platform but iOS and Apple TV as [CI and snapshots](app/docs/development.md#ci-and-snapshots) describes. `snapshot.yml` also runs on its own from the Actions tab, where it publishes by itself.
- `publish` runs once the checks and the packages are all green. It calls `publish-snapshot.yml`, which replaces the [`snapshot` prerelease](https://github.com/jamesarich/sofa-king/releases/tag/snapshot) with the packages the run uploaded, signed as a flat apt repository when the `APT_SIGNING_KEY` secret is set. Its notes add a section of the user-facing commits since the release it replaces above that release's sections, keeping the last 20 snapshots.

Ubuntu packages (FFmpeg, Comskip, libVLC) come through `.github/actions/apt-install`, which keeps the downloaded `.deb` files in the Actions cache per runner image: the runners' Ubuntu mirror sometimes takes half an hour for FFmpeg.

Every Gradle job restores the Gradle user home that the same job last saved from `main` (pull requests only read it), and the app jobs keep `~/.konan`, the Kotlin/Native distribution, per Kotlin version. Pushes to `main` also write the remote build cache in `gradle/build-cache.settings.gradle.kts`, which every other build reads. The configuration cache is not kept between runs: `setup-gradle` stores it only for a Develocity trial, so every run configures the build from scratch, 15 to 45 seconds per job and about 70 on Windows.

`backend.yml` runs three jobs:

- `test` runs `./gradlew spotlessCheck test :server:installDist` and the contract's `spotlessCheck jvmTest` on Ubuntu 24.04 with its FFmpeg 6.1, the version the image ships, and Comskip. FFmpeg's output differs between major versions, so a test that passes against a newer local FFmpeg can still fail there. Each test fails after a minute unless it sets a longer `@Timeout`. The contract's `jvmTest` checks the [wire fixtures](contract/README.md) older apps rely on, which the server's `test` does not reach. The job then runs `shellcheck` over `backend/deploy`, the unit tests in `backend/scripts`, and both [smoke scripts](backend/docs/development.md#build-and-test) against the installed distribution, which need no network, then fuzzes every documented route with Schemathesis.
- `image` builds the image from the tested distribution for `linux/amd64` and `linux/arm64`, each on a runner of its own architecture, then starts it and checks `/readyz`, `/healthz` and a clean stop. A pull request builds both without pushing them.
- `publish` runs for `main`, on a push or a manual run, and for a `v*` tag, once both images are built. It tags them together as `ghcr.io/jamesarich/sofa-king:main` (for a tag, `:X.Y.Z`, `:X.Y` and `:latest` instead) and `:sha-<commit>`, with a BuildKit SBOM and max-mode build provenance, which [deploy](backend/docs/deploy.md#inspect-the-published-image) shows how to inspect. Hosts with [automatic updates](backend/docs/deploy.md#update-automatically) install that image.

## Security

Report vulnerabilities privately, as [SECURITY.md](SECURITY.md) describes.
