# Development

## Build and test

Use JDK 21 and FFmpeg. Run from the repository root:

```sh
./gradlew test :server:installDist
python3 scripts/smoke.py
python3 scripts/live-smoke.py
```

Unit tests cover persistence, imports, gzip guide parsing, lineup curation, the Dispatcharr import, health policy, source selection, sessions, relay behavior, the admin API and client cleanup. The smoke scripts start disposable servers; the media smoke generates H.264/AAC fixtures, curates a lineup through the admin API and checks concurrent playback and cancellation.

To test a provider feed, replace the URLs and channel name:

```sh
python3 scripts/bridge-smoke.py \
  --playlist https://provider.example/channels.m3u \
  --guide https://provider.example/guide.xml \
  --decode-channel 'CHANNEL_NAME'
```

This imports the feed into a temporary server with probes disabled, adopts every channel into its lineup and optionally decodes the named channel. `--guide` is optional.

`scripts/dispatcharr-export.py` runs inside a Dispatcharr container and `scripts/dispatcharr-import.py` posts its output; see [migrate from Dispatcharr](live-tv.md#migrate-from-dispatcharr).

On the shared development host, use `/home/james/.claude/bin/gradle-queue --` in place of `./gradlew`.

## Change dependencies

1. Update exact versions in [the version catalog](../gradle/libs.versions.toml).
2. Run the build with `--write-locks` and review the lockfile changes.
3. Run both smoke scripts.
4. For wrapper updates, pin the official distribution checksum in `gradle-wrapper.properties`.

Use [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html). Keep network and media work outside database transactions, preserve coroutine cancellation, and close owned HTTP clients, jobs and processes. Keep documentation about current behavior; put change rationale in commit or pull-request descriptions.
