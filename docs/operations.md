# Operations

This guide covers running a server after the first start: where its state lives, how to back it up and restore it, how to upgrade and roll back, what to watch, what to do when a native provider breaks, and how to rotate tokens. Paths assume the Docker stack in [deploy](deploy.md); outside Docker, `SOFA_DATA_DIR` (default `data`) is the same directory.

## State

The data directory holds everything the server keeps across restarts:

| Path | Contents |
| --- | --- |
| `backend.sqlite` (with `-wal` and `-shm` while running) | The lineup, guides and retained programmes, source health and probe times, curation rules, settings and change log, native provider device IDs and session tokens, and the server identity |
| `logos/` | Cached lineup logos, one file per source URL |

Playback sessions and resolved native stream URLs are held in memory. A restart ends active playback, and clients open new sessions.

## Backups

Back up `backend.sqlite`. The logo cache is a cache: a restore without `logos/` fetches the logos again on the first sweep.

Cold copy, which is the simplest consistent backup. Stop the service, archive the volume, and start it again:

```sh
cd deploy
docker compose stop sofa-king-backend
docker run --rm -v sofa-king-backend_sofa-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/sofa-data-$(date +%F).tgz -C /data .
docker compose start sofa-king-backend
```

Online copy, without stopping the server. Run the SQLite CLI on the host against the data directory, because the image does not include `sqlite3`:

```sh
sqlite3 data/backend.sqlite ".backup 'backup/backend-$(date +%F).sqlite'"
```

Keep backups out of version control. They contain the provider tokens and stream URLs.

## Restore

Stop the service, then replace the database. Remove the `-wal` and `-shm` files that belong to the old database so SQLite does not replay them into the restored copy:

```sh
cd deploy
docker compose stop sofa-king-backend
docker run --rm -v sofa-king-backend_sofa-data:/data -v "$PWD":/backup alpine \
  sh -c 'rm -rf /data/* && tar xzf /backup/sofa-data-DATE.tgz -C /data'
docker compose start sofa-king-backend
```

Check the result with `/readyz` and `/api/v1/providers` (see [monitoring](#monitoring)).

## Upgrade and rollback

Back up before every upgrade. Then build and start the new version:

```sh
git pull
./gradlew :server:installDist
cd deploy && docker compose up -d --build
```

Schema migrations run at startup and only move forward. The server records each applied version in `schema_version`, and a server that does not know a version in the database refuses to start (`Unsupported database schema`). A rollback therefore means the previous image plus the backup taken before the upgrade. Restore that backup as shown above, then start the older image.

## Monitoring

| Check | Meaning |
| --- | --- |
| `GET /healthz` | The process answers. No token |
| `GET /readyz` | The database answers; `503 NOT_READY` otherwise. The Docker health check uses it. No token |
| `GET /api/v1/providers` | Per provider: `lastSuccessAt`, `channels`, `sources`, `skippedEntries`, `skipReasons` and `notes`. An `errorCode` of `IMPORT_FAILED` means the last import failed and the previous snapshot is still in use |
| `GET /api/v1/admin/guides` | The same for each guide, with the retained `programmes` count. An `IMPORT_FAILED` guide keeps the previous data |
| `GET /api/v1/sources` | Health state of every source (`HEALTHY`, `UNVERIFIED`, `SUSPECT`, `QUARANTINED`, `PROBATION`) |
| `GET /api/v1/admin/sessions` | Live playback sessions. An empty list means nothing is playing |
| `GET /api/v1/admin/curation/changes` | Actions automation applied, newest first |

The logs are `key=value` lines. They name providers, guides, sources and entries by ID, and they omit stream and guide URLs and tokens. Look for import failures, probe batch counts, health transitions (source ID with its old and new state), and `remux ended exit=` when an HDHomeRun stream stops.

Use `Authorization: Bearer $TOKEN` for the `/api/v1` checks, with the admin token for `/api/v1/admin` routes when one is configured.

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
   curl --fail -X PATCH -H "Authorization: Bearer $TOKEN" \
     http://SERVER_HOST:8080/api/v1/sources/SOURCE_ID \
     -H 'Content-Type: application/json' --data '{"manuallyDisabled":true}'
   ```

   Send `false` to clear the override. To remove a whole provider, delete it from the source file and restart. Its sources stop being offered, and entries skip them.
5. A fix to a native client is a change to the client itself. See [contributing](../CONTRIBUTING.md#add-a-native-provider).

## Rotating tokens

Tokens are set in the environment (`deploy/.env` for Docker). Each change takes effect after a restart, and any client holding the old value gets `401` until it is updated.

| Token | Rotate by | Update |
| --- | --- | --- |
| API token (`SOFA_API_TOKEN`) | Generate with `openssl rand -hex 32`, set it, restart | Every API client, including the Sofa King app |
| Admin token (`SOFA_ADMIN_TOKEN`) | Same | Whatever calls `/api/v1/admin` |
| Output token (`SOFA_OUTPUT_TOKEN`) | Generate with `openssl rand -hex 16`, set it, restart | Each player's playlist and guide URL, and the Plex guide URL |

The output token goes in URLs, so changing it means editing every player and Plex. Logo URLs carry no token and need no change. Native provider session tokens are renewed by the server, so nothing is needed for those.

## Restarts and shutdown

On `SIGTERM` the server stops accepting connections, closes the imports, probes and relays, and then closes the database. The Compose file allows 20 seconds for this. In-memory playback sessions end with the process, so schedule restarts outside viewing hours when possible.
