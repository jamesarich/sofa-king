# Operations

This guide covers running a server after the first start: where its state lives, backups and restore, upgrades and rollback, monitoring and logs, native provider failures, and token rotation. Paths assume the Docker stack in [deploy](deploy.md), whose config folder is `deploy/config`; outside Docker, `SOFA_DATA_DIR` (default `data`) is the same directory.

## State

The config folder holds everything the server keeps across restarts:

| Path | Contents |
| --- | --- |
| `backend.sqlite` (with `-wal` and `-shm` while running) | The lineup, guides and retained programs, source health and probe times, the lineup policy's station availability and change log, native provider device IDs and session tokens, and the server identity |
| `logos/` | Cached lineup logos, one file per source URL |
| `secrets.json` | The generated API and admin tokens |
| `sources.json`, `policy.json` | The source file and the lineup policy, when they are kept here |

Playback sessions and resolved native stream URLs are held in memory. A restart ends active playback, and clients open new sessions.

## Backups

Back up the whole config folder. The database needs a consistent copy, either from SQLite's backup API while the server runs or from a stopped server; a plain copy of a running database can miss what is still in its write-ahead log. The logo cache is a cache: a restore without `logos/` fetches the logos again on the first sweep.

Online, without stopping the server. The image does not include `sqlite3`, so run it on the host, or use Python's `sqlite3` module, which calls the same backup API:

```sh
cd deploy
mkdir -p backup
sqlite3 config/backend.sqlite ".backup 'backup/backend-$(date +%F).sqlite'"
tar czf backup/config-$(date +%F).tgz --exclude='backend.sqlite*' --exclude=logos -C config .
```

Stopped, which copies everything in one step:

```sh
cd deploy
docker compose stop
tar czf backup/config-$(date +%F).tgz -C config .
docker compose start
```

Keep backups private and out of version control. They contain the generated tokens, the provider session tokens and stream URLs. The [updater](deploy.md#update-automatically) also backs up the database, through the same backup API, before each update.

## Restore

Stop the service, then put the files back. Remove the `-wal` and `-shm` files that belong to the old database so SQLite does not replay them into the restored copy:

```sh
cd deploy
docker compose stop
rm -f config/backend.sqlite-wal config/backend.sqlite-shm
tar xzf backup/config-DATE.tgz -C config
cp backup/backend-DATE.sqlite config/backend.sqlite   # when the database was backed up on its own
docker compose start
```

Restore as the user that owns `config`, so the server can still write the files.

Check the result with `/readyz` and `/api/v1/providers` (see [monitoring](#monitoring)).

## Upgrade and rollback

A host that runs the updater upgrades itself from the published image, with the backup, the tag and the rollback below done for it; see [update automatically](deploy.md#update-automatically). To upgrade by hand, back up first, and tag the running image so it stays available to roll back to. Then build and start the new version:

```sh
docker tag sofa-king-backend:local sofa-king-backend:previous
git pull
./gradlew :server:installDist
REVISION=$(git rev-parse --short HEAD) docker compose -f deploy/compose.yaml build
cd deploy && docker compose up -d
```

On a host without a JDK, build over SSH as [deploy](deploy.md#deploy-to-a-host-without-a-jdk) shows and run the `docker tag` there first. `docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' sofa-king-backend` prints the commit a running container was built from.

Schema migrations run at startup and only move forward. The server records each applied version in `schema_version`, and a server that does not know a version in the database refuses to start (`Unsupported database schema`). A rollback therefore needs both the previous image and the backup taken before the upgrade. [Restore](#restore) that backup, then start the previous image:

```sh
docker tag sofa-king-backend:previous sofa-king-backend:local
cd deploy && docker compose up -d --no-build --force-recreate
```

## Monitoring

| Check | Meaning |
| --- | --- |
| `GET /healthz` | The process answers. No token |
| `GET /readyz` | The database answers; `503 NOT_READY` otherwise. The Docker health check uses it. No token |
| `GET /api/v1/providers` | Per provider: `lastSuccessAt`, `channels`, `sources`, `skippedEntries`, `skipReasons` and `notes`. An `errorCode` of `IMPORT_FAILED` means the last import failed and the previous snapshot is still in use |
| `GET /api/v1/admin/guides` | The same for each guide, with the retained `programmes` count. An `IMPORT_FAILED` guide keeps the previous data |
| `GET /api/v1/sources` | Health state of every source (`HEALTHY`, `UNVERIFIED`, `SUSPECT`, `QUARANTINED`, `PROBATION`) |
| `GET /api/v1/admin/sessions` | Live playback sessions. An empty list means nothing is playing |
| `GET /api/v1/admin/policy/changes` | What lineup policy runs changed, newest first |
| `GET /api/v1/admin/policy/findings` | Open findings of the lineup policy, such as missing required sources |

Use `Authorization: Bearer $SOFA_API_TOKEN` for the `/api/v1` checks, and `Authorization: Bearer $SOFA_ADMIN_TOKEN` for the `/api/v1/admin` routes.

## Logs

The server logs to stderr, which `docker compose logs` shows. `SOFA_LOG_LEVEL` sets the level (`info` by default) and `SOFA_LOG_FORMAT` the format:

- `text`, the default: one line per event with the time in UTC, the level, the thread and the logger, then the message. A line written while handling a request ends with `requestId=...`.
- `json`: one JSON object per line from logback's JSON encoder, with the level, the logger, the formatted message and the request id. Use it when a log collector parses the output.

Every request gets a random 16-character request id, returned in the `X-Request-Id` response header. Quote it when you report a problem, and search the log for it. Each request also gets one access line, `request method=GET path=/api/v1/channels status=200 durationMs=12`, except media, logos, player streams, `/auto/v*` and the health checks. The path never includes the query, so a token in a URL does not reach the log.

Messages are `key=value` text. They name providers, guides, sources and entries by ID, and they omit stream and guide URLs and tokens. Look for import failures, probe batch counts, health transitions (source ID with its old and new state), `auth failed route=... remote=...` for rejected tokens, and `remux ended exit=` when an HDHomeRun stream stops.

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
   curl --fail -X PATCH -H "Authorization: Bearer $SOFA_API_TOKEN" \
     http://SERVER_HOST:9193/api/v1/sources/SOURCE_ID \
     -H 'Content-Type: application/json' --data '{"manuallyDisabled":true}'
   ```

   Send `false` to clear the override. To remove a whole provider, delete it from the source file and restart. Its sources stop being offered, and entries skip them.
5. A fix to a native client is a change to the client itself. See [contributing](../CONTRIBUTING.md#add-a-native-provider).

## Rotating tokens

Tokens come from the environment (`deploy/.env` for Docker), from the file a `_FILE` variable names, or, for the API and admin tokens, from `secrets.json` in the config folder. Each change takes effect after a restart, and any client holding the old value gets `401` until it is updated.

| Token | Rotate by | Update |
| --- | --- | --- |
| API token (`SOFA_API_TOKEN`) | Set a new value from `openssl rand -hex 32` and restart. When it is generated, delete `apiToken` from `secrets.json` and restart, and a new one is generated | Every API client, including the Sofa King app |
| Admin token (`SOFA_ADMIN_TOKEN`) | Same, with `adminToken`; read the new value from `secrets.json` | Whatever calls `/api/v1/admin`, and the admin portal sign-in |
| Output token (`SOFA_OUTPUT_TOKEN`) | Set a new value from `openssl rand -hex 16` and restart | Each player's playlist and guide URL, and the Plex guide URL |

The output token goes in URLs, so changing it means editing every player and Plex. Logo URLs carry no token and need no change. Native provider session tokens are renewed by the server, so nothing is needed for those.

## Restarts and shutdown

On `SIGTERM` the server stops accepting connections, closes the imports, probes and relays, and then closes the database. The Compose file allows 20 seconds for this. In-memory playback sessions end with the process, so schedule restarts outside viewing hours when possible.
