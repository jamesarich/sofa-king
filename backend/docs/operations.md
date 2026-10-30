# Operations

This guide covers running a server after the first start: where its state lives, backups and restore, upgrades and rollback, monitoring and logs, native provider failures, AI assistants, and token rotation. Paths assume the Docker stack in [deploy](deploy.md), whose config folder is `deploy/config`; outside Docker, `SOFA_DATA_DIR` (default `data`) is the same directory. Commands run from `backend/`.

## State

The config folder holds everything the server keeps across restarts:

| Path | Contents |
| --- | --- |
| `backend.sqlite` (with `-wal` and `-shm` while running) | The lineup, guides and retained programs, source health and probe times, the lineup policy's station availability and change log, native provider device IDs and session tokens, the last Plex sync, the paired viewers and their preferences, the recordings, series rules and commercial breaks, the media libraries with their scanned items, TMDB matches, episodes' audio fingerprints and intros, and the household's watch progress, watchlist, plays and favorites, the portal's runtime settings, signed-in sessions (by the hash of their ID) and ignored home page items, the API tokens (by the hash of their secret), the virtual channels, the notification targets and their deliveries, the history of ended sessions, and the server identity |
| `logs/` | The log: `server.log` for today, gzipped `server.<date>.<n>.log.gz` for earlier days, kept `SOFA_LOG_DAYS` days; see [logs](#logs) |
| `logos/` | Cached lineup logos, one file per source URL, at most 2 MiB each; a logo the lineup does not name is deleted |
| `artwork/` | TMDB images and the video frames taken as stills for the media libraries; deleting it fetches and takes them again as clients ask |
| `transcode/` | The HLS output of library playback sessions, deleted when each session ends, and the subtitles extracted from library files |
| `dvr/` | Comskip's working files while it scans a recording |
| `intros/` | FFmpeg's fingerprint of the episode the intro pass is reading, deleted once it is read |
| `scrub/` | The library player's [scrub thumbnails](libraries.md#scrub-thumbnails), one folder of JPEG sheets per file; deleting it makes them again on the pass after the next scan |
| `recordings/` | The recordings, when `SOFA_RECORDINGS_DIR` is unset. Compose mounts its own `deploy/recordings` folder instead, as [the recordings folder](deploy.md#the-recordings-folder) describes |
| `backups/` | Database backups taken by the [updater](deploy.md#update-automatically) and by hand, readable by their owner alone. The updater's fallback backups and its `failed-image` record are in `backups/` in the Compose directory instead |
| `secrets.json` | The generated API, admin and output tokens, and the TMDB token the Library page saved, readable by the owner alone |
| `sources.json`, `policy.json` | The [source file](live-tv.md#source-settings) and the [lineup policy](lineup-policy.md), when they are kept here; the server writes numbers and edits back into the policy. `SOFA_SOURCES` and `SOFA_POLICY` override them, and the log names the files it picked, and why, at every start |

Playback sessions, library playback sessions, the live pairing code and resolved native stream URLs are held in memory. A restart ends active playback, and clients open new sessions. A recording running at the restart resumes into the same file.

## Backups

Back up the whole config folder, or the database alone from the portal: System, Backup takes a consistent copy while the server runs, once a day on its own and whenever you ask, keeps them in `config/backups/` and downloads any of them (see [Backup](admin.md#pages)). The rest of this section copies the whole folder by hand.

The database needs a consistent copy, either from SQLite's backup API while the server runs or from a stopped server; a plain copy of a running database can miss what is still in its write-ahead log. The logo cache is a cache: a restore without `logos/` fetches the logos again on the first sweep. `artwork/`, `scrub/` and `transcode/` can be left out the same way. The recordings folder sits outside the config folder in Compose, so back it up on its own if you keep recordings; the media folder is yours and the server never writes to it.

Online, without stopping the server. The image includes `sqlite3`, so one line copies the database into `config/backups/`:

```sh
cd deploy
docker compose exec sofa-king-backend sqlite3 /config/backend.sqlite ".backup '/config/backups/manual.sqlite'"
```

`sqlite3` does not create the folder, so make it first with `mkdir -m 700 config/backups` when it is missing. The copy belongs to the user the container runs as. To keep the rest of the folder with it, archive the folder without the live database and the logo cache:

```sh
mkdir -p backup
tar czf backup/config-$(date +%F).tgz --exclude='backend.sqlite*' --exclude=logos --exclude=artwork --exclude=scrub --exclude=transcode -C config .
```

Stopped, which copies everything in one step, from `backend/deploy`:

```sh
docker compose stop
tar czf backup/config-$(date +%F).tgz -C config .
docker compose start
```

Keep backups private and out of version control. They contain the generated tokens, the provider session tokens and stream URLs. The [updater](deploy.md#update-automatically) also backs up the database, through the same backup API, to `config/backups/` before each update. When the container cannot take that copy, the updater writes it with the host's Python 3 to `backups/` in the Compose directory, readable by root alone.

## Restore

To restore the database alone, use System, Backup in the portal: restore one of the backups listed there or upload a database file. The server checks it, sets the running database aside in `config/backups/` as a `before-restore-` backup and restarts on the restored one, which needs a restart policy such as Compose's `restart: unless-stopped`.

To restore the whole folder, stop the service, then put the files back. Remove the `-wal` and `-shm` files that belong to the old database so SQLite does not replay them into the restored copy:

```sh
cd deploy
docker compose stop
rm -f config/backend.sqlite-wal config/backend.sqlite-shm
tar xzf backup/config-DATE.tgz -C config
cp config/backups/manual.sqlite config/backend.sqlite   # or an updater backup, config/backups/backend-*.sqlite
docker compose start
```

An updater fallback backup is `backups/backend-*.sqlite` in the Compose directory.

Restore as the user that owns `config`, so the server can still write the files.

Check the result with `/readyz` and `/api/v1/providers` (see [monitoring](#monitoring)).

## Upgrade and rollback

A host that runs the [updater](deploy.md#update-automatically) does all of this itself. To upgrade by hand, back up first, and tag the running image so it stays available to roll back to. Then pull and start the new version, from the Compose directory:

```sh
docker tag "$(docker inspect --format '{{.Image}}' sofa-king-backend)" sofa-king-backend:previous
docker compose pull
docker compose up -d
```

With your own build, rebuild it as [build](deploy.md#build) shows instead of pulling, or on a host without a JDK as [deploy your build](deploy.md#deploy-your-build-to-a-host-without-a-jdk) shows. `docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' sofa-king-backend` prints the commit a running container was built from; `GET /api/v1/admin/status` and the admin portal footer report the same commit as `revision`. The portal's [About page](admin.md#update) says whether the published image is newer and lets you start the updater from the browser.

Schema migrations run at startup and only move forward. The server records each applied version in `schema_version`, and a server that does not know a version in the database refuses to start (`Unsupported database schema`). A rollback therefore needs both the previous image and the backup taken before the upgrade. [Restore](#restore) that backup, which drops whatever the newer image wrote after it was taken, such as recordings and watch state, then start the previous image, from the Compose directory:

```sh
docker tag sofa-king-backend:previous "$(docker compose config --images)"
docker compose up -d --force-recreate
```

The tag `compose.yaml` runs now holds the previous image until `docker compose pull` moves it again. Stop the updater's timer and path unit first, or it installs the newest image again at its next check.

## Monitoring

| Check | Meaning |
| --- | --- |
| `GET /healthz` | The process answers. No token |
| `GET /readyz` | The database answers; `503 NOT_READY` otherwise. The Docker health check uses it. No token |
| `GET /api/v1/admin/status` | One summary: the revision, uptime and schema version, channel and source counts, failing provider and guide imports, the last lineup policy run and Plex sync, and active sessions. See [server status](api.md#server-status) |
| `GET /api/v1/providers` | Per provider: `lastSuccessAt`, `channels`, `sources`, `skippedEntries`, `skipReasons` and `notes`. An `errorCode` of `IMPORT_FAILED` means the last import failed and the previous snapshot is still in use |
| `GET /api/v1/admin/guides` | The same for each guide, with the retained `programs` count. An `IMPORT_FAILED` guide keeps the previous data |
| `GET /api/v1/sources` | Health state of every source (`HEALTHY`, `UNVERIFIED`, `SUSPECT`, `QUARANTINED`, `PROBATION`) |
| `GET /api/v1/admin/sessions` | Live playback sessions. An empty list means nothing is playing |
| `GET /api/v1/admin/history` | Ended playback sessions, newest first, with who played what, for how long and why it ended. See [playback history](api.md#playback-history) |
| `GET /api/v1/admin/events/stream` | Session starts and ends, recordings, sources, the disk, updates, library scans and attention changes as server-sent events, for a script or a dashboard to follow. See [event stream](api.md#event-stream) |
| Notifications | The same events pushed to ntfy, an Apprise API server or a webhook, so nothing has to keep a stream open. Add targets on the Connect tab; see [notifications](notifications.md) |
| `GET /api/v1/admin/policy/changes` | What lineup policy runs changed, newest first |
| `GET /api/v1/admin/policy/findings` | Open findings of the lineup policy, such as missing required sources |
| `GET /api/v1/recordings` | Every recording with its `state`, `reason` and `conflict`, and the recordings folder's free space in `storage` |
| `GET /api/v1/admin/libraries` | Each media library with its last scan's `scanOk` and `scanNote` |

Use `Authorization: Bearer $SOFA_API_TOKEN` for the `/api/v1` checks, and `Authorization: Bearer $SOFA_ADMIN_TOKEN` for the `/api/v1/admin` routes.

## Logs

The server logs to stderr, which `docker compose logs` shows, and to `logs/server.log` in `SOFA_DATA_DIR` (the config folder in Docker), so the log outlives a container the updater replaces. The file rolls at midnight UTC, or early at 20 MB, into gzipped `server.<date>.<n>.log.gz` files; `SOFA_LOG_DAYS` keeps 14 days of them by default, at most 250 MB in all, and `0` writes no file. The file has the same level and format as stderr. The admin portal's [Events page](admin.md#pages) shows, downloads and temporarily raises the log. `SOFA_LOG_LEVEL` sets the level (`info` by default), which the portal's [runtime settings](admin.md#settings) change without a restart and take precedence over. `SOFA_LOG_FORMAT` sets the format:

- `text`, the default: one line per event with the time in UTC, the level, the thread and the logger, then the message. A line written while handling a request ends with `requestId=...`.
- `json`: one JSON object per line from logback's JSON encoder, with the level, the logger, the formatted message and the request id. Use it when a log collector parses the output.

Every request gets a random request id, returned in the `X-Request-Id` header; quote it when you report a problem and search the log for it. Each request also gets one access line, `request method=GET path=/api/v1/channels status=200 durationMs=12`, except media, logos, player streams, `/auto/v*` and the health checks. The path never includes the query, so a token in a URL does not reach the log.

Messages are `key=value` text that name providers, guides, sources and entries by ID. They cover:

- provider imports: counts, skipped entries, duration and failure class
- guide imports: channels, retained programs and duration, and on failure the exception class and its message with every URL replaced by `<url>`
- logo fetch failures: `logo fetch failed key=KEY` with the exception class, the message with every URL replaced by `<url>`, and whether a stale file remains
- probe batches (outcome counts, duration) and health transitions (source ID, old and new state)
- lineup policy runs (cause, station, change, assigned-number, learned-alias and finding counts, duration) and each learned alias
- relay upstream reconnects and failures: source ID, attempt, exception class and HTTP status
- `remux ended exit=` with FFmpeg's last lines, URLs removed, when an HDHomeRun remux exits with an error
- rejected tokens: `auth failed route=ROUTE remote=ADDRESS`, at most 30 lines a minute
- recordings: `recording schedule`, `recording started`, `recording attempt failed`, `recording finished`, `recording failed` or `recording missed`, `recording culled` with its reason, and `commercial scan` with the breaks found or why it failed
- media libraries and their playback: `library scan failed`, `artwork fetch failed`, `vod session started` with its mode, and at start `vod encoder ready`, `vod encoder unavailable` or, with `SOFA_HWACCEL=auto`, `no GPU encoder works` for `SOFA_HWACCEL`, then `multiview:` with whether the server offers [multiview](api.md#multiview) and why

A request that fails with a closed channel, broken pipe or connection reset, as when a player switches channels mid-body, logs at debug; other request failures log at error. Stream URLs, guide URLs, tokens and provider exception messages are never logged, and a guide or logo failure message only after URL redaction.

## When a native provider breaks

The [native providers](providers.md) call each service's public endpoints. A service change shows up as channels that vanish, resolves that answer `502 UPSTREAM_UNAVAILABLE`, or an `IMPORT_FAILED` provider.

1. Read the provider's status in `/api/v1/providers`, and its log lines, for the failure class.
2. Run the provider check. It starts a disposable server, opens sessions and decodes a few seconds of two channels:

   ```sh
   python3 scripts/provider-smoke.py --service NAME
   ```

   The check needs the built distribution and network access to the service.
3. Playback fails over on its own. Each entry keeps its other sources, failing sources are quarantined, and `GET /api/v1/sources` shows their states.
4. To stop the server from retrying a broken source, disable it:

   ```sh
   curl --fail -X PATCH -H "Authorization: Bearer $SOFA_ADMIN_TOKEN" \
     http://SERVER_HOST:9193/api/v1/sources/SOURCE_ID \
     -H 'Content-Type: application/json' --data '{"manuallyDisabled":true}'
   ```

   Send `false` to clear the override. To remove a whole provider, delete it from the source file and restart. Its sources stop being offered, and entries skip them.
5. A fix to a native client is a change to the client itself. See [add a native provider](development.md#add-a-native-provider).

## Use with an AI assistant

The server answers the [Model Context Protocol](api.md#mcp) at `/api/v1/mcp`, so an assistant such as Claude Code can check on it. [MCP](api.md#mcp) lists the read tools (triage, the lineup and policy, playback and recordings, libraries) and the admin tools, which also act on the server and never make a token, show a stream URL, a token or a request header.

Pick the token for what the assistant may do. A read token, made on the portal's Settings page under Access tokens with Read as what it opens, lets it look and change nothing. It also sees the log, the recordings, the guide and the libraries, which the read routes of the admin API do not show, and not the playback history or the Plex status. The admin token lets it act too; give it only to an assistant you trust with the portal. Then add the server to the assistant, for Claude Code:

```sh
claude mcp add --transport http sofa-king http://SERVER_HOST:9193/api/v1/mcp \
  --header "Authorization: Bearer TOKEN"
```

The assistant must reach the server's address, and must send the token as a bearer header: an assistant that runs in the cloud cannot reach a server on the home network. Revoke a read token on the Settings page to cut the assistant off.

### Set up a server with an assistant

With the admin token an assistant can walk a first run. Ask it to use the `triage_server` prompt, or go step by step:

1. `setup_status` shows the providers and guides in the source file (kind, host and interval, never a URL or header), whether a lineup policy exists, which of the TMDB, fanart.tv and ListenBrainz keys are set, and the folders a library may use. Providers still go in the source file or the portal's Sources page.
2. `write_starter_policy` writes the starter lineup policy for those providers when there is none. It restarts the server, so the assistant asks you first.
3. `add_library` adds a movies, shows, videos or music library from one of those folders and queues its scan.
4. `set_api_key` saves a TMDB token, fanart.tv key or ListenBrainz token after the service accepts it, then restarts the server. A key the environment sets is refused with `..._FROM_ENVIRONMENT`.
5. `preview_policy` and the `review_policy_change` prompt take over from there.

A key you give an assistant is in its transcript, and the server never repeats it. Prefer the Library page in the portal, or the `SOFA_*` variables, for a key you do not want an assistant to hold. Each restart ends every stream and the server is back in about a minute. The `sofa-king://support-snapshot` resource is what to attach to a bug report.

## Rotating tokens

A token change takes effect after a restart, and any client holding the old value gets `401` until it is updated. The `sk_` tokens the server issues (devices', read and hook tokens) change without a restart, and rotating the API token leaves them working.

| Token | Rotate by | Update |
| --- | --- | --- |
| API token (`SOFA_API_TOKEN`) | Set a new value from `openssl rand -hex 32` and restart. When it is generated, delete `apiToken` from `secrets.json` and restart, and a new one is generated | Every API client, including the Sofa King app |
| Admin token (`SOFA_ADMIN_TOKEN`) | Same, with `adminToken`; read the new value from `secrets.json` | Whatever calls `/api/v1/admin`, and the admin portal sign-in |
| Output token (`SOFA_OUTPUT_TOKEN`) | Set a new value from `openssl rand -hex 16` and restart. When it is generated, delete `outputToken` from `secrets.json` and restart, and a new one is generated | Each player's playlist and guide URL, and the Plex guide URL |
| A device's token | Pair the device again from the portal's Devices page; that revokes its old token. Forget revokes it without a new one | That device only |
| A read or hook token | Make a new one on the Settings page, put it where the old one was, then revoke the old one. No restart | The dashboard or the hook that holds it |

The output token goes in URLs, so changing it means editing every player and Plex. Logo URLs carry no token and need no change. Native provider session tokens are renewed by the server, so nothing is needed for those.

## Restarts and shutdown

On `SIGTERM` the server stops accepting connections, closes the imports, probes and relays, and then closes the database. The Compose file allows 20 seconds for this. In-memory playback sessions end with the process, so schedule restarts outside viewing hours when possible. A [setup write](admin.md#setup) or a [restore](#restore) from the portal, or a setup write from the admin API, ends the process the same way once it has answered, and the Compose file's `restart: unless-stopped` starts it again; a server run some other way needs starting again by hand.

## Direct broadcaster schedules

When an aggregator lists a channel but omits its programs, `scripts/refresh-schedules.py` converts verified broadcaster feeds into XMLTV files. It supports Wurl JSON (including series and episodes), CTVCraft program exports (several channels per URL, each item ending after its `duration`), Frequency XMLTV with an explicit unsigned zero timezone, and CGTN XMLTV with ISO timestamps. CGTN URLs can use `{date}` for the current UTC date. The converter preserves the supplied timezone; it does not infer a timezone for missing values.

Copy `deploy/schedules.example.json` to a private configuration file. Each entry has a unique `id`, `kind` (`wurl`, `ctvcraft`, `frequency` or `cgtn`) and `url`. The example names six public broadcaster feeds. Verify the channel and schedule against the stream before assigning it; providers can change their feeds.

Run with Python 3.11 or newer:

```sh
python3 scripts/refresh-schedules.py /path/to/schedules.json /path/to/public/schedules
```

Serve the output directory through an existing HTTP server. Add each file's HTTP URL to `guides` in the server source configuration with an hourly refresh, then assign its XMLTV channel ID to the matching lineup entry. Saving the source configuration restarts the server. Preserve existing providers and guides when editing it.

Schedule the same command hourly with cron or a systemd timer. Use `flock` to prevent overlapping runs. Keep the converter and its configuration outside the HTTP document root. A feed is published atomically only after its timestamps, titles and upcoming programs pass validation. Failed feeds retain their last valid file, other feeds continue, and the command exits nonzero if any feed fails. Monitor that exit status: retained schedules eventually expire. Logs report feed IDs, counts and exception types without source URLs or credentials.
