# Contributing

Contributions are Sofa King welcome, from everyone who follows the [code of conduct](CODE_OF_CONDUCT.md). This guide covers the repository's layout, the conventions the app, the server and the contract share, and the CI that checks them. Each part's build, tests and code rules are in its development page and its `AGENTS.md`:

| Part | Build and tests | Code constraints |
| --- | --- | --- |
| App | [development](app/docs/development.md) | [app/AGENTS.md](app/AGENTS.md) |
| Server | [development](backend/docs/development.md), with the smoke scripts and adding a native provider | [backend/AGENTS.md](backend/AGENTS.md) |
| Contract | [its README](contract/README.md), with the rules for changing a wire type | |

## Repository layout

Three Gradle builds, each with its own wrapper; build each from its own directory:

| Directory | Build | Checks |
| --- | --- | --- |
| [`app/`](app/README.md) | The Kotlin Multiplatform app | [Build and test](app/docs/development.md#build-and-test) |
| [`backend/`](backend/README.md) | The Ktor server and its Docker image | [Build and test](backend/docs/development.md#build-and-test) |
| [`contract/`](contract/README.md) | The wire types both include | `./gradlew jvmTest assemble` |

They share these files at the root:

- `gradle/libs.versions.toml`, the one version catalog, so a Kotlin or kotlinx.serialization bump moves every build at once. It holds `sofaking`, the one product version, which `.github/version.sh` extends with the build number that both the app snapshot and the server image carry.
- `gradle/repositories.settings.gradle.kts` and `gradle/build-cache.settings.gradle.kts`, the repositories and the remote build cache every build applies. The cache is read-only without the `SOFAKING_CACHE_*` credentials.
- `build-logic/`, the convention plugins: `sofaking.kotlin` (the JDK 21 toolchain and the web toolchain repositories), `sofaking.android` (the SDK levels) and `sofaking.lint` (formatting and static analysis).
- `.editorconfig`, the code style the IDE and ktlint share.
- `settings.gradle.kts`, which includes all three builds for one IDE import only, with the `gradle.properties` and the `.run/` configurations that import uses. CI never builds from the root; the app's [development](app/docs/development.md#android-studio) page covers opening it in Android Studio.
- [`renovate.json`](renovate.json), which proposes the dependency, wrapper, base image and workflow action updates of every build.

`.gitignore` ignores every directory named `build`, so no package or source directory may carry that name.

## Formatting and static analysis

Kotlin follows IntelliJ IDEA's Kotlin style, which the root `.editorconfig` sets for the IDE and for [ktlint](https://pinterest.github.io/ktlint/), with lines up to 120 characters and no wildcard imports. The `sofaking.lint` convention applies [Spotless](https://github.com/diffplug/spotless) with ktlint to every project's Kotlin sources and Gradle scripts, and [detekt](https://detekt.dev/) to its Kotlin sources, with the [Compose rules](https://mrmans0n.github.io/compose-rules/) where the project uses Compose. `gradle/detekt.yml` and `gradle/detekt-compose.yml` hold what differs from the defaults, each change with its reason. From `app/`, `backend/` or `contract/`:

```sh
./gradlew spotlessApply   # format
./gradlew spotlessCheck   # fail on unformatted code or a finding ktlint cannot fix, such as a long line
./gradlew detekt          # static analysis
```

CI runs `spotlessCheck` and `detekt` in every build. Fix a finding in the code rather than suppressing it; where a rule is wrong for one declaration, `@Suppress` that one rule there, with a comment saying why. There is no baseline, and no build turns a rule off.

Catch the exceptions a call can throw, such as `IOException` or `SerializationException`, never a bare `catch (e: Exception)`. Where a failure of any kind is logged or shown and the work goes on, such as a background loop's next pass or a screen's load, use `runCatchingCancellable` (the server's `Results.kt`, the app's `data/Failures.kt`): it rethrows `CancellationException`, so canceling still cancels, and never catches an `Error`. To clean up after a failure and let it go on, use `runCatching { … }.onFailure { … }.getOrThrow()`, or `finally`.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, in the imperative mood, for example `fix(player): back off on the lineup reload` or `feat(providers): resolve the guide id for a channel`. The scope names the area:

- App: `app` (the shell, navigation and the tabs), `guide`, `player`, `preview`, `captions`, `settings`, `library`, `data`, `web`, `ios`, `tvos`, `desktop`.
- Server: the package or area, such as `providers`, `policy`, `hdhr`, `logos`, `playback`, `admin`, `server` or `backend`.
- Anywhere: `build` and `ci` for the builds and workflows, `deps` for Renovate's dependency updates, and `docs` for documentation-only changes.

The squash-merge title is the release note: the `snapshot` release lists every `feat`, `fix` and `perf` title since the previous snapshot, rendered by [git-cliff](https://git-cliff.org) with `.github/cliff.toml`, so write the summary for someone using the app or the server. The app's update check shows the same lines, without the scope and the pull request, as What's new. Other types stay out of the notes. A breaking change (`feat!:`, or a `BREAKING CHANGE:` footer) goes under Upgrade notes whatever its type; say there what someone updating has to do.

A release is a `v*` tag on `main` whose version matches `sofaking` in the version catalog: bump the catalog in a pull request, merge it, then push the tag (`git tag -a v0.2.0 -m "Highlights"`). The tag's message heads the release notes. A Flyway migration bumps at least the minor version, since a host cannot move back past it, and gets an upgrade note.

## Pull requests

- Keep one change per pull request. Describe why it is needed first; the diff already shows what changed. Put the reasoning behind a change in the commit or the pull request, not in the docs.
- Add or update tests with any behavior change. Name tests for the behavior they check.
- Update the [documentation](#documentation) in the same pull request, including any changed route, setting, control or variable.
- List the checks you ran, including the [checks on a TV](app/docs/development.md#test-on-a-tv) for a change to the app's playback, focus or remote, and add before and after screenshots for a visible change.
- Leave tokens, cookies, device identifiers, server addresses and private URLs out of code, fixtures, logs, screenshots and descriptions. The repository is public.
- Write US English in prose and in names, such as color, behavior and license; the root [AGENTS.md](AGENTS.md) lists the spellings and the third-party names that keep theirs.
- Squash-merge once CI is green at the head.

## Documentation

The Markdown pages are also the [documentation site](https://jamesarich.github.io/sofa-king/), built with MkDocs and Material for MkDocs from `mkdocs.yml`. The pages stay where they are and read the same on GitHub: [`docs/site/stage.py`](docs/site/stage.py) copies every tracked page and docs image (agent instructions and `.github/` aside) into `build/docs-src`, with the server's OpenAPI document rendered by Scalar as the API reference, and [`docs/site/hooks.py`](docs/site/hooks.py) points links to scripts and other non-page files at GitHub. To build it locally, strictly as CI does:

```sh
pip install -r docs/site/requirements.txt
python3 docs/site/stage.py && mkdocs build --strict
```

The root keeps `README.md`, this file, `SECURITY.md`, `SUPPORT.md`, `CODE_OF_CONDUCT.md` and `docs/` (the index and shared glossary, [comparison](docs/comparison.md), [migrating](docs/migrating.md) and `site/`). `app/` and `backend/` each keep a `README.md` and a `docs/` folder whose index lists its pages; both have `getting-started`, `troubleshooting`, `architecture`, `security`, `development` and `dependencies`. Put a new page in the part it describes, link it from that index, and add it to the `nav` of `mkdocs.yml`.

- Write current facts in the present tense, with steps in the imperative.
- One fact lives on one page; the others link to it. The server's [configuration table](backend/docs/live-tv.md#server-settings) is the one place its environment variables are listed.
- Split a sentence over about 40 words. Three or more controls, options or fields make a table.
- Per-platform exceptions go in a trailing paragraph or a table column.
- Implementation detail goes in the architecture pages, not the usage or API pages.
- A flow, state machine or timing that takes more than a paragraph gets a Mermaid diagram: no colors or styling, every edge labeled.
- No review diaries, dated benchmark or test-count summaries, design histories or roadmaps.

## Wire changes

A request or response type the app and the server share lives in `contract/`, never in either part. A change to it lands with both sides in one pull request, follows the rules in the [contract's README](contract/README.md), and keeps every fixture in `contract/src/jvmTest/resources/wire`: never edit or delete one. Released apps run against newer servers, so the server must keep answering what those fixtures hold.

## Dependencies

Versions are pinned exactly in the shared catalog, with no lockfiles or verification metadata, so an update changes only the pin. Renovate opens the pull requests for the catalog, the Gradle wrappers, the Dockerfile base images and the workflow actions, pinning images and actions by digest. It follows Kotlin, kotlinx.serialization and kotlinx.coroutines prereleases and lists pending updates on its Dependency Dashboard issue. It merges minor, patch, pin and digest updates at least three days old once every check on the latest commit passes. Kotlin, the Android Gradle plugin and Compose Multiplatform move together in one pull request, and major updates wait too, for a person; the JDK and the Ubuntu runner image wait for approval on the dashboard. The catalog also pins the ktlint version Spotless runs (`ktlint`) and detekt, on its 2.0 prerelease line (plugin id `dev.detekt`); Renovate updates them like any other pin.

By hand:

1. Update the exact versions in the catalog.
2. Run the part's checks: [app](app/docs/development.md#build-and-test), [server](backend/docs/development.md#build-and-test) with both smoke scripts, and `jvmTest` in `contract/`.
3. For a change to the app's playback, captions or remote, run the [checks on a TV](app/docs/development.md#test-on-a-tv).
4. For a wrapper update, pin the official distribution checksum in `gradle-wrapper.properties`.
5. A Kotlin or kotlinx.serialization bump moves all three builds: run each part's checks and follow the [contract's README](contract/README.md).
6. For a wrapper, JDK or SDK change, update the version table in the part's dependencies page.

Each part's dependencies page lists the versions the catalog does not hold: [app](app/docs/dependencies.md), [server](backend/docs/dependencies.md).

## CI

| Workflow | Triggers | What it does |
| --- | --- | --- |
| [`ci.yml`](.github/workflows/ci.yml) | Pull request, push to `main`, manual | Tests and builds the app, runs the [Apple TV remote tests](app/docs/development.md#apple-tv-remote-tests), the [iOS touch tests](app/docs/development.md#ios-touch-tests) and the [web key fuzz](app/docs/development.md#web-key-fuzz), and publishes the snapshot from `main` |
| [`backend.yml`](.github/workflows/backend.yml) | Pull request, push to `main`, `v*` tag, manual | Tests and builds the server, and publishes its image from `main` and from each tag |
| [`docs.yml`](.github/workflows/docs.yml) | Pull request, push to `main`, manual | Checks that every image a page draws is a tracked file (`docs/site/check_images.py`), builds the site strictly, so a link to a missing page or anchor fails, and publishes it to GitHub Pages from `main` |
| [`release.yml`](.github/workflows/release.yml) | `v*` tag | Builds the packages through `snapshot.yml`, then `publish-release.yml` publishes them as the `vX.Y.Z` release, which GitHub marks the latest; `backend.yml` publishes the image for the same tag |
| [`screenshots.yml`](.github/workflows/screenshots.yml) | Mondays, pull request, manual | Takes the documentation's screenshots on every platform a runner can show and the admin portal, and opens a pull request with the ones that changed ([Screenshots](app/docs/development.md#screenshots)) |
| [`tv-monkey.yml`](.github/workflows/tv-monkey.yml) | Nightly, pull request, manual (key count and seed) | The Maestro flows, then random remote keys, on the debug app in a headless Android TV emulator ([TV emulator monkey](app/docs/development.md#tv-emulator-monkey)) |
| [`desktop-monkey.yml`](.github/workflows/desktop-monkey.yml) | Nightly, pull request, manual (key count and seed) | Random keys on the desktop app in a virtual X display, with libVLC playing fixture channels ([Desktop monkey](app/docs/development.md#desktop-monkey)) |
| [`phone-tests.yml`](.github/workflows/phone-tests.yml) | Nightly, pull request, manual (step count and seed) | The debug app's touch tests in a headless Android phone emulator ([Phone touch tests](app/docs/development.md#phone-touch-tests)) |
| [`codeql.yml`](.github/workflows/codeql.yml) | Mondays, pull request, manual | Scans the workflows with CodeQL; findings appear under the Security tab. The Kotlin waits for CodeQL to support the catalog's Kotlin version |
| [`scorecard.yml`](.github/workflows/scorecard.yml) | Push to `main`, Tuesdays, manual | Rates the repository with the OpenSSF Scorecard and publishes the result for the README's badge |
| [`dependency-submission.yml`](.github/workflows/dependency-submission.yml) | Push to `main`, manual | Sends each build's resolved dependencies to GitHub, so Dependabot alerts cover them |

What runs on a change (a pull request or a push to `main` unless noted; manual runs aside):

| You change | Runs |
| --- | --- |
| `app/` code, not Markdown, `app/docs/`, `app/maestro/` or the `tv-monkey.py`, `desktop-monkey.py`, `screenshots.py` and `showcase.py` scripts | `ci.yml` |
| `contract/`, not Markdown | `ci.yml`, `backend.yml` |
| `backend/`, not Markdown or `backend/docs/` | `backend.yml` |
| The app's `core` or `ui` module or its build files | `ci.yml`, and `backend.yml` on a push to `main` only |
| `gradle/`, `build-logic/`, `.editorconfig` | `ci.yml`, `backend.yml` |
| A `*.gradle.kts` or `gradle.properties` file, `gradle/`, `build-logic/` | `dependency-submission.yml`, on a push to `main` only |
| Markdown, `docs/`, `mkdocs.yml`, the server's OpenAPI document | `docs.yml` |
| `app/maestro/`, `app/scripts/tv-monkey.py` | `tv-monkey.yml` |
| `app/scripts/desktop-monkey.py` | `desktop-monkey.yml` |
| `app/androidApp/src/androidTest/`, `app/androidApp/build.gradle.kts` | `phone-tests.yml`, `ci.yml` |
| `app/scripts/screenshots.py`, `showcase.py` | `screenshots.yml` |
| `app/scripts/doc_images.py`, `doc-images.json` | `screenshots.yml`, `ci.yml` |
| `app/scripts/fixture_server.py` | `ci.yml`, `tv-monkey.yml`, `desktop-monkey.yml`, `phone-tests.yml`, `screenshots.yml` |
| `.github/actions/apt-install/` | `ci.yml`, `backend.yml`, `screenshots.yml` |
| `.github/version.sh` | `ci.yml`, `backend.yml` |
| A workflow file | That workflow; `ci.yml` also runs for `snapshot.yml` and `publish-snapshot.yml` |

A push to `main` that changes the app publishes the rolling `snapshot` prerelease; one that changes the server publishes `ghcr.io/jamesarich/sofa-king:main`, which the home server installs within about 5 minutes. A `v*` tag matching the catalog's `sofaking` publishes the `vX.Y.Z` release and the image as `:X.Y.Z`, `:X.Y` and `:latest`. Every published package carries a build attestation, and the image a cosign signature too.

`ci.yml` runs up to five jobs:

- `check` runs the formatting check and detekt, the app's tests, Android lint, the Native test compile, the release APK, the Android screenshot tests and both production web builds on Ubuntu, then presses random keys on both web builds in Chrome against fixture channels. It uploads the test reports when it fails and the `RenderSmokeTest` screenshots every time.
- `apple` runs the data layer's tests on iOS, macOS and tvOS simulators and, outside pull requests, on watchOS too and the UI's iOS tests (a pull request compiles both), links the iOS framework, builds the SwiftUI host in `app/iosApp` with XcodeGen and `xcodebuild`, and runs the [iOS touch tests](app/docs/development.md#ios-touch-tests), on macOS. A pull request runs the iOS and Apple TV random walks shorter than `main` does, to save macOS runner time.
- `apple-tv` links the tvOS framework, builds the SwiftUI host in `app/tvosApp` and runs the [Apple TV remote tests](app/docs/development.md#apple-tv-remote-tests) on a second macOS runner, beside `apple`.
- `snapshot` runs for a push to `main`. It calls `snapshot.yml`, which builds every platform but iOS and Apple TV as [CI and snapshots](app/docs/development.md#ci-and-snapshots) describes, and which also runs on its own from the Actions tab, where it publishes by itself.
- `publish` runs once the checks and the packages are all green. It calls `publish-snapshot.yml`, which replaces the [`snapshot` prerelease](https://github.com/jamesarich/sofa-king/releases/tag/snapshot) with the packages the run uploaded, signed as a flat apt repository when the `APT_SIGNING_KEY` secret is set. Its notes add a section of the user-facing commits since the release it replaces above that release's sections, keeping the last 20 snapshots.

`backend.yml` runs four jobs, and serves the web app built from the same commit at `/app/`, which is why a push to `main` that changes the app's `core` or `ui` runs it too:

- `test` runs `./gradlew spotlessCheck detekt test :server:installDist` and the contract's `spotlessCheck detekt jvmTest` on Ubuntu 24.04 with its FFmpeg 6.1, the version the image ships, and Comskip. FFmpeg's output differs between major versions, so a test that passes against a newer local FFmpeg can still fail there. Each test fails after a minute unless it sets a longer `@Timeout`. The contract's `jvmTest` checks the [wire fixtures](contract/README.md) older apps rely on, which the server's `test` does not reach. The job then runs `shellcheck` over `backend/deploy`, the unit tests in `backend/scripts`, and both [smoke scripts](backend/docs/development.md#build-and-test) against the installed distribution, which need no network, then fuzzes every documented route with Schemathesis.
- `web` builds the app's production Wasm bundle with the image's version, beside `test`, and gzips its files; the image serves it at `/app/` ([serve the web app](backend/docs/deploy.md#serve-the-web-app)).
- `image` builds the image from the tested distribution and the web bundle for `linux/amd64` and `linux/arm64`, each on a runner of its own architecture, then starts it and checks `/readyz`, `/healthz`, the web app's page and its precompressed WebAssembly, and a clean stop. A pull request builds both without pushing them.
- `publish` runs for `main`, on a push or a manual run, and for a `v*` tag, once both images are built. It tags them together as `ghcr.io/jamesarich/sofa-king:main` (for a tag, `:X.Y.Z`, `:X.Y` and `:latest` instead) and `:sha-<commit>`, with a BuildKit SBOM and max-mode build provenance, which [deploy](backend/docs/deploy.md#inspect-the-published-image) shows how to inspect. Hosts with [automatic updates](backend/docs/deploy.md#update-automatically) install that image.

Ubuntu packages (FFmpeg, Comskip, libVLC) come through `.github/actions/apt-install`, which keeps the downloaded `.deb` files in the Actions cache per runner image: the runners' Ubuntu mirror sometimes takes half an hour for FFmpeg.

Every Gradle job restores the Gradle user home that the same job last saved from `main` (pull requests only read it), and the app jobs keep `~/.konan`, the Kotlin/Native distribution, per Kotlin version. Pushes to `main` also write the remote build cache. The configuration cache is not kept between runs: `setup-gradle` stores it only for a Develocity trial, so every run configures the build from scratch, 15 to 45 seconds per job and about 70 on Windows.

## Security

Report vulnerabilities privately, as [SECURITY.md](SECURITY.md) describes.
