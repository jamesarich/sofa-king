# Contributing

Thanks for working on Sofa King Backend. This guide covers the build, the test suite, commit and pull request conventions, and how to add a native provider. The [docs index](docs/README.md) lists the rest of the documentation.

## Build and test

You need JDK 21 and FFmpeg. Run commands from the repository root:

```sh
./gradlew test
./gradlew :server:installDist
python3 scripts/smoke.py
python3 scripts/live-smoke.py
```

The tests generate real HLS fixtures with FFmpeg, so FFmpeg must be on the path. The smoke scripts run the installed distribution against disposable servers and temporary directories, so run `:server:installDist` first. See [development](docs/development.md) for what each script covers.

The [Image workflow](.github/workflows/image.yml) runs on every pull request and on every push to `main`, in two jobs:

- `test` runs `./gradlew test :server:installDist` on Ubuntu 24.04 with its FFmpeg 6.1, the version the image ships. FFmpeg's output differs between major versions, so a test that passes against a newer local FFmpeg can still fail there. On a pull request it also builds the image for `linux/amd64` without pushing it.
- `publish` runs only for `main`, after `test` passes. It builds the image for `linux/amd64` and `linux/arm64` from the tested distribution and pushes it to `ghcr.io/jamesarich/sofa-king-backend` as `:main` and `:sha-<commit>`. The push carries a BuildKit SBOM and max-mode build provenance, which [deploy](docs/deploy.md#inspect-the-published-image) shows how to inspect. Hosts with [automatic updates](docs/deploy.md#update-automatically) install that image.

[Renovate](renovate.json) opens the dependency, base image and workflow action updates as pull requests, which run the same `test` job.

Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html). Keep network and media work outside database transactions, preserve coroutine cancellation, and close the HTTP clients, jobs and processes you own.

## Commits and pull requests

- Use [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, for example `feat(providers): resolve the guide id for a channel` or `fix(hdhr): report a busy tuner on a remux failure`. Use the package or area as the scope (`providers`, `policy`, `hdhr`, `logos`, `playback`) and `docs` for documentation-only changes.
- Describe why the change is needed in the pull request. The diff already shows what changed.
- Add or update tests with any behavior change. Name tests for the behavior they check.
- Keep documentation describing the current behavior. Put the reasoning behind a change in the commit or the pull request.
- Update the documentation that describes a changed route, variable or behavior in the same pull request. The [configuration table](docs/live-tv.md#server-settings) is the one place the environment variables are listed.

## Dependencies

Versions are pinned in `gradle/libs.versions.toml`, resolved versions are locked, and Gradle checks every artifact against the checksums in `gradle/verification-metadata.xml`. A dependency change updates all three; `scripts/update-verification-metadata.sh` regenerates the checksums from a clean resolve. The [dependencies](docs/dependencies.md) page lists the current versions and the update steps.

## Add a native provider

A native provider is a client for one free streaming service, built into the server. The [providers](docs/providers.md) page describes the existing clients and how they are configured.

1. Implement `ProviderClient` in `server/src/main/kotlin/dev/jamesrich/sofaking/backend/providers/`. The interface has `service`, `channels()`, `resolve(channelId)` and an optional `guide(wanted)`. Use the `ProviderContext` the registry passes in for HTTP, options, the shared cache and durable state (`provider_state`). Throw `ProviderFailure` with `transient = true` when the service is at fault and `transient = false` when the channel is gone from it. Keep tokens and URLs out of exception messages.
2. Register the service. Add its name and whether it has a guide of its own to the `NativeServices` map in `ProviderClient.kt`, and add a branch to `ProviderRegistry.client`. If the service publishes an XMLTV guide that the server should use by default, add it to `NativeServices.defaultGuideUrl`.
3. Add fixtures. Save the sanitized service responses under `server/src/test/resources/providers/`, named for the service, for example `example-channels.json`. Remove tokens, cookies, device identifiers and anything personal before committing a fixture.
4. Add a test. Write a `ProviderFixture`-based test beside the others in `server/src/test/kotlin/dev/jamesrich/sofaking/backend/providers/`, for example `ExampleClientTest.kt`. Point the client's base URLs at the fixture through its options, load the fixtures with `ProviderFixture.resource(...)`, and assert the channel list, a resolve, and the guide if the service has one. Include a case for a failing service and one for a channel that is gone.
5. Run `./gradlew test`, then check the service against its live endpoints:

   ```sh
   python3 scripts/provider-smoke.py --service example
   ```

6. Document the service in [providers](docs/providers.md): add it to the service list and the configuration table, and write a section covering its endpoints, its guide and its fragility.

Native clients are unofficial. Keep the request shape close to the service's own client, and keep the fixtures small.
