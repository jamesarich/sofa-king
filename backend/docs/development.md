# Development

The [contributing guide](../CONTRIBUTING.md) covers commit and pull request conventions and the steps to add a native provider. This page covers running the server from a build, the test suite and the scripts that exercise a running server.

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

   [`deploy/sources.example.json`](../deploy/sources.example.json) lists every native provider and an M3U provider example. The PBS and M3U entries need your values.

3. Start the server. It listens on `127.0.0.1:8080` by default:

   ```sh
   server/build/install/server/bin/server
   ```

   On loopback the API needs no token. To serve other machines, set `SOFA_HOST=0.0.0.0`, and either `SOFA_HDHR_ALLOW=PLEX_SERVER_IP` or `SOFA_HDHR_ENABLED=false`: on a LAN bind the server refuses to start without one of them. The first start then generates an API token and an admin token and keeps them in `data/secrets.json`. No token is logged. `SOFA_API_TOKEN` and `SOFA_ADMIN_TOKEN` set your own values instead.

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

Use JDK 21 and FFmpeg. Run from `backend/`:

```sh
./gradlew test :server:installDist
python3 scripts/smoke.py
python3 scripts/live-smoke.py
```

The unit tests cover persistence and migrations, feed and guide imports (including gzip guides), the native provider clients, the lineup and the lineup policy, and source health. They also cover playback sessions, the relay, the admin API and portal, the player outputs, the logo cache, the HDHomeRun routes, the FFmpeg remux and client cleanup. Tests that depend on a response's lifetime or on an FFmpeg client run the server on a real Netty socket (`RealServer`), because the Ktor test host hands a streamed body to a background job. `MediaFixture` generates H.264 and AAC HLS with FFmpeg, so FFmpeg is needed to run the suite.

The smoke scripts start disposable servers in temporary directories. `smoke.py` verifies the installed application over HTTP, including SIGTERM and restart. `live-smoke.py` generates H.264 and AAC fixtures and builds and edits a lineup through the admin API. It then checks concurrent playback and cancellation, loads the player output with FFmpeg as the player, and checks the lineup across a restart.

## Scripts

| Script | Purpose |
| --- | --- |
| `scripts/smoke.py` | Installed-application check over HTTP |
| `scripts/live-smoke.py` | Fixture feeds through the relay, probes, lineup edits and restart |
| `scripts/bridge-smoke.py` | Imports one feed into a temporary server, adopts its channels and optionally decodes one |
| `scripts/provider-smoke.py` | Checks one native provider against its live service |
| `scripts/generate-policy.py` | Generates a [lineup policy](lineup-policy.md#generate-a-policy) from a running server; `python3 -m unittest discover -s scripts -p 'test_*.py'` tests it, including its normalization against the server's golden keys |

To test a provider feed, replace the URLs and channel name:

```sh
python3 scripts/bridge-smoke.py \
  --playlist https://provider.example/channels.m3u \
  --guide https://provider.example/guide.xml \
  --decode-channel 'CHANNEL_NAME'
```

This imports the feed into a temporary server with probes disabled, adopts every channel into its lineup and optionally decodes the named channel. `--guide` is optional.

To check a [native provider](providers.md) against its live service:

```sh
python3 scripts/provider-smoke.py --service whale
python3 scripts/provider-smoke.py --service pbs --option zip=ZIP_CODE
```

This starts a temporary server with that one provider and adopts its channels. It opens sessions on three of them, expecting an HLS master from the relay, and decodes five seconds of two with FFmpeg. `--resolve`, `--decode`, `--seconds` and `--pick NAME,NAME` adjust it.

Native provider unit tests run against `ProviderFixture`, a loopback stand-in for each service's API fed by the sanitized documents under `server/src/test/resources/providers/`. Every client takes its base URLs from options, so a test can point it there.

## Dependencies

Version changes follow [dependencies](dependencies.md#changing-versions). Run the build with `--write-locks` after a change, review the lockfile diff, update the verification metadata, and run both smoke scripts.
