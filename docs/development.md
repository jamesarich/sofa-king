# Development

The [contributing guide](../CONTRIBUTING.md) covers commit and pull request conventions and the steps to add a native provider. This page covers the build, the test suite and the scripts that exercise a running server.

## Build and test

Use JDK 21 and FFmpeg. Run from the repository root:

```sh
./gradlew test :server:installDist
python3 scripts/smoke.py
python3 scripts/live-smoke.py
```

The unit tests cover persistence and migrations, feed and guide imports (including gzip guides), the native provider clients, lineup and curation rules, the Dispatcharr import, source health, playback sessions, the relay, the admin API, the admin portal, the player outputs, the logo cache, the HDHomeRun routes and the FFmpeg remux, and client cleanup. Tests that depend on a response's lifetime or on an FFmpeg client run the server on a real Netty socket (`RealServer`), because the Ktor test host hands a streamed body to a background job. `MediaFixture` generates H.264 and AAC HLS with FFmpeg, so FFmpeg is needed to run the suite.

The smoke scripts start disposable servers in temporary directories. `smoke.py` verifies the installed application over HTTP, including SIGTERM and restart. `live-smoke.py` generates H.264 and AAC fixtures, curates a lineup through the admin API, checks concurrent playback and cancellation, loads the player output with FFmpeg as the player, and runs a curation rule through preview, run and restart.

## Scripts

| Script | Purpose |
| --- | --- |
| `scripts/smoke.py` | Installed-application check over HTTP |
| `scripts/live-smoke.py` | Fixture feeds through the relay, probes, curation and restart |
| `scripts/bridge-smoke.py` | Imports one feed into a temporary server, adopts its channels and optionally decodes one |
| `scripts/provider-smoke.py` | Checks one native provider against its live service |
| `scripts/generate-policy.py` | Generates a [lineup policy](lineup-policy.md#generate-a-policy) from a running server; `python3 -m unittest discover -s scripts -p 'test_*.py'` tests it, including its normalization against the server's golden keys |
| `scripts/dispatcharr-export.py`, `scripts/dispatcharr-import.py` | Export a Dispatcharr profile and import it; see [migrate from Dispatcharr](migrate-from-dispatcharr.md) |

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

This starts a temporary server with that one provider, adopts its channels, opens sessions on three of them expecting an HLS master from the relay, and decodes five seconds of two with FFmpeg. `--resolve`, `--decode`, `--seconds` and `--pick NAME,NAME` adjust it. Native provider unit tests run against `ProviderFixture`, a loopback stand-in for each service's API fed by the sanitized documents under `server/src/test/resources/providers/`. Every client takes its base URLs from options, so a test can point it there.

## Dependencies

Version changes follow [dependencies](dependencies.md#changing-versions). Run the build with `--write-locks` after a change, review the lockfile diff, and run both smoke scripts.
