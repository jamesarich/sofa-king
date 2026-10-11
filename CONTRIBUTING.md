# Contributing

Sofa King is an Android TV and Google TV app in Kotlin with Compose for TV and Media3. [Architecture](docs/ARCHITECTURE.md) describes how the code is organized, and [development](docs/DEVELOPMENT.md) covers the toolchain.

## Build and test

Set up the SDK as described in [Install](README.md#install), then run:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

Unit tests run against MockWebServer, so no Sofa King server is needed.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, in the imperative mood, for example `fix(player): back off on the lineup reload`. Common types are `feat`, `fix`, `docs`, `refactor`, `test` and `build`.

## Pull requests

- Keep one change per pull request.
- Say why the change is needed in the description, and list what it changes.
- Include the unit tests and the release build in what you ran.
- Update the docs when behavior changes. The docs describe what the app does now.

## Test on a TV

Check these on a real Android TV or Google TV device before a change that touches playback, focus or the remote:

- [ ] Cold start opens the last watched channel, and Back shows the guide.
- [ ] The Guide, Menu and Search keys work (the launcher may claim Guide on some devices), and voice search finds a channel.
- [ ] D-pad focus moves through the guide without losing its place, including rapid focus changes over the preview.
- [ ] The preview is muted, unmutes at 5 seconds and opens full screen at 10 seconds, and captions show over it.
- [ ] Background and foreground transitions resume the same channel.
- [ ] Audio, captions and decoder recovery work on the full-screen player.

[DEVELOPMENT.md](docs/DEVELOPMENT.md) has the same checks with more detail.

## Security

Report vulnerabilities privately, as described in [SECURITY.md](SECURITY.md).
