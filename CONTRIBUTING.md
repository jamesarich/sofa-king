# Contributing

This guide covers the conventions the app in `app/`, the server in `backend/` and the wire types in `contract/` share, and the CI that checks them. Each part has its own build and tests:

- The app: [development](app/docs/development.md) has the build and test commands, running each platform and the checks on a TV. [app/AGENTS.md](app/AGENTS.md) lists its code constraints.
- The server: [its contributing guide](backend/CONTRIBUTING.md) has the build, the tests, the smoke scripts and adding a provider.
- The contract: [its README](contract/README.md) has the rules for changing a wire type.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, in the imperative mood, for example `fix(player): back off on the lineup reload`. The scope names the area:

- App: `guide`, `player`, `preview`, `captions`, `settings`, `data`, `web`, `ios`, `desktop`.
- Server: the scopes in [its guide](backend/CONTRIBUTING.md#commits-and-pull-requests).
- Either part: `build` and `ci` for the build and workflows, and `docs` for documentation-only changes.

## Pull requests

- Keep one change per pull request. Describe why it is needed first; the diff already shows what changed.
- Add or update tests with any behavior change. Name tests for the behavior they check.
- Keep documentation describing the current behavior, in the same pull request. Put the reasoning behind a change in the commit or the pull request.
- List the checks you ran, including the [checks on a TV](app/docs/development.md#test-on-a-tv) for a change to the app's playback, focus or remote, and add before and after screenshots for a visible change.
- Pull requests are squash-merged once CI is green at the head.

## Wire changes

A request or response type the app and the server share lives in `contract/`, never in either part. A change to it lands with both sides in one pull request, follows the rules in the [contract's README](contract/README.md), and keeps every fixture in `contract/src/jvmTest/resources/wire`: never edit or delete one. Released apps run against newer servers, so the server must keep answering what those fixtures hold.

## CI

| Workflow | Runs for | What it does |
| --- | --- | --- |
| [`ci.yml`](.github/workflows/ci.yml) | Pull requests and pushes to `main` that change `app/` or `contract/` (not their Markdown or `app/docs/`), the shared `gradle/` directory or `build-logic/`, `ci.yml` or `snapshot.yml`; also a manual run | Tests and builds the app |
| [`backend.yml`](.github/workflows/backend.yml) | Pull requests and pushes to `main` that change `backend/` or `contract/` (not their Markdown or `backend/docs/`), the shared `gradle/` directory or `build-logic/`, or `backend.yml`; also a manual run | Tests and builds the server, and publishes its image from `main` |

`ci.yml` runs up to three jobs:

- `check` runs the app's tests, lint, the Native test compile, the release APK and the wasm web build on Ubuntu. It uploads the test reports when it fails and the `RenderSmokeTest` screenshots every time.
- `apple` runs on every event except a pull request, so on pushes to `main` and manual runs. It runs the data layer's tests on iOS, macOS, tvOS and watchOS simulators, the UI's iOS tests, and links the iOS framework on macOS. A pull request compiles the iOS tests on Linux in `check` instead.
- `snapshot` runs for a push to `main` once both pass. It calls `snapshot.yml`, which builds every platform but iOS and replaces the [`snapshot` prerelease](https://github.com/jamesarich/sofa-king/releases/tag/snapshot), as [CI and snapshots](app/docs/development.md#ci-and-snapshots) describes. `snapshot.yml` also runs on its own from the Actions tab.

A push to `main`, or a manual run on `main`, that passes `backend.yml` publishes the server image as `ghcr.io/jamesarich/sofa-king-backend:main`; [deploy](backend/docs/deploy.md#the-published-image) describes it.

## Security

Report vulnerabilities privately, as [SECURITY.md](SECURITY.md) describes.
