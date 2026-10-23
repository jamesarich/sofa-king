# Development

The [contributing guide](../../CONTRIBUTING.md) covers the commit and pull request conventions and CI. This page covers running the server from a build, its tests, its code conventions, the scripts that exercise a running server and adding a native provider.

## Run without Docker

You need JDK 21 and FFmpeg. Run the commands from `backend/`:

1. Build the server:

   ```sh
   ./gradlew :server:installDist
   ```

2. Create `data/sources.json`. The server reads it from its data directory, `data`, at start. Native providers need no account. Inside any string, `${NAME}` is replaced from the environment, so a tokenized URL stays out of the file:

   ```sh
   mkdir -p data
   ```

   ```json
   {
     "providers": [
       { "id": "whale", "type": "native", "service": "whale" },
       { "id": "tubi", "type": "native", "service": "tubi" },
       { "id": "pluto", "type": "native", "service": "pluto" }
     ],
     "guides": []
   }
   ```

   [`deploy/sources.example.json`](../deploy/sources.example.json) lists the native provider of every free streaming service, the broadcasters' news feeds (`direct`) and an M3U provider example. The PBS and M3U entries need your values.

3. Start the server. It listens on `127.0.0.1:8080` by default:

   ```sh
   server/build/install/server/bin/server
   ```

   On loopback the API needs no token. To serve other machines, set `SOFA_HOST=0.0.0.0`; the HDHomeRun routes then admit no address until the portal's Plex page or `SOFA_HDHR_ALLOW` allows one. The first start then generates an API token, an admin token and an output token and keeps them in `data/secrets.json`. No token is logged. `SOFA_API_TOKEN` and `SOFA_ADMIN_TOKEN` set your own values instead, of at least 32 characters. The routes each token opens are in [security](security.md#credentials-and-the-routes-they-open).

4. Check the imports. On a LAN bind, `/api/v1/admin/*` takes only the admin token and refuses the API token. The first two lines read both tokens from `data/secrets.json`. On loopback with no tokens, skip them and leave out the headers:

   ```sh
   SOFA_API_TOKEN=$(jq -r .apiToken data/secrets.json)
   SOFA_ADMIN_TOKEN=$(jq -r .adminToken data/secrets.json)
   curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://127.0.0.1:8080/api/v1/providers
   curl --fail -H "Authorization: Bearer $SOFA_ADMIN_TOKEN" http://127.0.0.1:8080/api/v1/admin/guides
   ```

5. Build the lineup. A new server serves no channels until a [lineup policy](lineup-policy.md) builds them. Write a policy that takes every channel of the three services, Whale first, to `data/policy.json`:

   ```sh
   echo '{"providers": [{"id": "whale"}, {"id": "tubi"}, {"id": "pluto"}], "groups": {"default": "Entertainment"}}' > data/policy.json
   ```

   Stop the server and start it again. It logs which sources and policy files it picked. Stations come on as the probes verify one of their sources:

   ```sh
   server/build/install/server/bin/server
   curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://127.0.0.1:8080/api/v1/channels
   ```

   For a full lineup of every free service, grouped, with fallbacks across services and your local channels, follow [build a starter lineup](starter-lineup.md).

Keep `data/` across restarts. It holds the database, the logo cache, the generated tokens and the two files you wrote. `SOFA_SOURCES` and `SOFA_POLICY` point at files elsewhere. Every setting is in [live TV configuration](live-tv.md#server-settings), and [operations](operations.md) covers backups, upgrades and token rotation.

## Build and test

Use JDK 21 and FFmpeg on the path. Run every command from `backend/`:

```sh
./gradlew test :server:installDist
python3 scripts/smoke.py
python3 scripts/live-smoke.py
python3 scripts/api-fuzz.py
```

The unit tests cover persistence and migrations, playlist and guide imports (including gzip guides), the native provider clients, the lineup and the lineup policy, and source health. They also cover playback sessions, the relay, the admin API and portal, the player outputs, the logo cache, the HDHomeRun routes, the FFmpeg remux and client cleanup, the DVR's schedule, recorder, file layout and cleanup, library file names, `.nfo` files, scans, TMDB lookups and the library API, and library playback sessions with their modes and recordings. Tests that depend on a response's lifetime or on an FFmpeg client run the server on a real Netty socket (`RealServer`), because the Ktor test host hands a streamed body to a background job. `MediaFixture` generates H.264 and AAC HLS with FFmpeg, so FFmpeg is needed to run the suite. One test runs Comskip on a generated recording when `comskip` is on the path (`apt install comskip` on Debian and Ubuntu) and skips otherwise.

The tests run on JUnit Jupiter through `kotlin.test`. Each test fails after 60 seconds unless it carries a longer `@Timeout` (`server/src/test/resources/junit-platform.properties`), and a debugger session is never timed out. Wait for background work with `pollUntil` or `pollFor` (`Polling.kt`) rather than a fixed sleep. To check that a timed loop does nothing more, run it on the test scheduler with `runTest` and advance its virtual time.

The smoke scripts start disposable servers in temporary directories. `smoke.py` verifies the installed application over HTTP, including SIGTERM and restart. `live-smoke.py` generates H.264 and AAC fixtures and builds and edits a lineup through the admin API. It then checks concurrent playback and cancellation, loads the player output with FFmpeg as the player, and checks the lineup across a restart. Neither reaches the network, since `live-smoke.py` serves its fixtures over loopback, and CI runs both on every server change. `api-fuzz.py` runs [Schemathesis](https://schemathesis.readthedocs.io/) (install it with `pipx install schemathesis`) against the OpenAPI document: generated requests on every route, failing on a 5xx, a response that breaks its documented schema or content type, or a server that stops answering. The live playback routes may answer their documented 503 `SOURCE_UNAVAILABLE`, since a channel the fuzzer adds has no usable source. It leaves out the three routes that save configuration and restart the server. `SOFA_FUZZ_EXAMPLES` sets the examples per route (100 by default); CI runs it too.

## Code

Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) and the constraints in [AGENTS.md](../AGENTS.md). Keep network and media work outside database transactions, preserve coroutine cancellation, and close the HTTP clients, jobs and processes you own. [Architecture](architecture.md) describes how the code is organized.

## Scripts

| Script | Purpose |
| --- | --- |
| `scripts/smoke.py` | Installed-application check over HTTP |
| `scripts/live-smoke.py` | Fixture playlists through the relay, probes, lineup edits and restart |
| `scripts/api-fuzz.py` | Schemathesis on every documented route of a fresh server |
| `scripts/bridge-smoke.py` | Imports one playlist into a temporary server, adopts its channels and optionally decodes one |
| `scripts/provider-smoke.py` | Checks one native provider against its live service |
| `scripts/generate-policy.py` | Generates a [lineup policy](lineup-policy.md#generate-a-policy) from a running server; `python3 -I -m unittest discover -s scripts -p 'test_*.py'` tests it, including its normalization against the server's golden keys |

To test an M3U provider, replace the URLs and channel name:

```sh
python3 scripts/bridge-smoke.py \
  --playlist https://provider.example/channels.m3u \
  --guide https://provider.example/guide.xml \
  --decode-channel 'CHANNEL_NAME'
```

This imports the playlist into a temporary server with probes disabled, adopts every channel into its lineup and optionally decodes the named channel. `--guide` is optional.

To check a [native provider](providers.md) against its live service:

```sh
python3 scripts/provider-smoke.py --service whale
python3 scripts/provider-smoke.py --service pbs --option zip=ZIP_CODE
```

This starts a temporary server with that one provider and adopts its channels. It opens sessions on three of them, expecting an HLS master from the relay, and decodes five seconds of two with FFmpeg. `--resolve`, `--decode`, `--seconds` and `--pick NAME,NAME` adjust it.

Native provider unit tests run against `ProviderFixture`, a loopback stand-in for each service's API fed by the sanitized documents under `server/src/test/resources/providers/`. Every client takes its base URLs from options, so a test can point it there.

## Add a native provider

A native provider is a client for one free streaming service, built into the server. The [providers](providers.md) page describes the existing clients and how they are configured.

1. Implement `ProviderClient` in `server/src/main/kotlin/dev/jamesrich/sofaking/backend/providers/`. The interface has `service`, `channels()`, `resolve(channelId)`, an optional `guide(wanted)` and `protocol`, `hls` unless the service streams MPEG-TS. Use the `ProviderContext` the registry passes in for HTTP, options, the shared cache and durable state (`provider_state`). Throw `ProviderFailure` with `transient = true` when the service is at fault and `transient = false` when the channel is gone from it. Keep tokens and URLs out of exception messages.
2. Register the service. Add its name and whether it has a guide of its own to the `NativeServices` map in `ProviderClient.kt`, and add a branch to `ProviderRegistry.client`. If the service publishes an XMLTV guide that the server should use by default, add it to `NativeServices.defaultGuideUrl`. Add a `ServiceInfo` for it, with its label, what it carries and its options, to `ServiceCatalog.all` in `setup/ServiceCatalog.kt`, at its place in the starter policy's priority order; it becomes the service's switch on the portal's Sources page, and `SetupServiceTest` fails until every service is there.
3. Add fixtures. Save the sanitized service responses under `server/src/test/resources/providers/`, named for the service, for example `example-channels.json`. Remove tokens, cookies, device identifiers and anything personal before committing a fixture.
4. Add a test. Write a `ProviderFixture`-based test beside the others in `server/src/test/kotlin/dev/jamesrich/sofaking/backend/providers/`, for example `ExampleClientTest.kt`. Point the client's base URLs at the fixture through its options, load the fixtures with `ProviderFixture.resource(...)`, and assert the channel list, a resolve, and the guide if the service has one. Include a case for a failing service and one for a channel that is gone.
5. Run `./gradlew test`, then check the service against its live endpoints:

   ```sh
   python3 scripts/provider-smoke.py --service example
   ```

6. Document the service in [providers](providers.md): add it to the service list and the configuration table, and write a section covering its endpoints, its guide and its fragility.

Native clients are unofficial. Keep the request shape close to the service's own client, and keep the fixtures small.

## Dependencies

Version changes follow [dependencies](dependencies.md#changing-versions). Run the checks and both smoke scripts after a change.
