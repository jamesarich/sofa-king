# Contributing

Sofa King is an Android TV and Google TV app in Kotlin with Compose for TV and Media3. [Architecture](docs/ARCHITECTURE.md) describes how the code is organized, and [development](docs/DEVELOPMENT.md) covers the toolchain.

## Build and test

Run the checks in [build and test](docs/DEVELOPMENT.md#build-and-test). Unit tests run against MockWebServer, so no Sofa King server is needed. A change that touches playback, focus or the remote also needs the [checks on a TV](docs/DEVELOPMENT.md#test-on-a-tv).

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, in the imperative mood, for example `fix(player): back off on the lineup reload`. Common types are `feat`, `fix`, `docs`, `refactor`, `test` and `build`.

## Pull requests

- Keep one change per pull request.
- Say why the change is needed in the description, and list what it changes.
- List the checks you ran, including the TV checks when they apply.
- Update the docs when behavior changes. The docs describe what the app does now.

## Security

Report vulnerabilities privately, as described in [SECURITY.md](SECURITY.md).
