# Deploy with Docker

The image runs the installed distribution on `eclipse-temurin:21-jre-noble` with Ubuntu's FFmpeg and curl, as the non-root user `sofa` (uid 10001). The database and the logo cache live in the `/data` volume, the server listens on container port 8080, and a `HEALTHCHECK` polls `/readyz` every 30 seconds after a 30-second start period. `deploy/compose.yaml` publishes host port 9193 and targets an amd64 host such as OMV with Docker Compose v2.

The server is built on the host and copied into the image. The build pulls a Gradle milestone and Kotlin and Ktor prereleases from JetBrains' EAP repository with a locked dependency set, so building inside Docker would repeat those downloads on every image build and fail whenever an artifact is unavailable; the host build uses the local Gradle cache and the image build only needs the base image and Ubuntu packages.

## Files

| File | Purpose |
| --- | --- |
| `Dockerfile` | Image definition; copies `server/build/install/server` to `/opt/sofa` |
| `.dockerignore` | Admits only the installed distribution into the build context |
| `deploy/compose.yaml` | Service, port mapping, volume, source file mount, token guard |
| `deploy/.env.example` | Environment template; copy to `deploy/.env` |
| `deploy/sources.example.json` | Source template with the household's providers and guides; copy to `deploy/sources.json` |

`deploy/.env` and `deploy/sources.json` are ignored by Git.

## Environment

The image sets `SOFA_HOST=0.0.0.0`, `SOFA_PORT=8080` and `SOFA_DATA_DIR=/data`. Compose sets `SOFA_SOURCES=/config/sources.json` and mounts `deploy/sources.json` there read-only. `deploy/.env` supplies the rest, and every variable in it reaches the server, so `${NAME}` references in `sources.json` resolve from it:

| Variable | Required | Description |
| --- | --- | --- |
| `SOFA_API_TOKEN` | Yes | At least 32 characters; the LAN bind refuses to start without it, and compose refuses to start when it is unset |
| `SOFA_ADMIN_TOKEN` | No | At least 32 characters; opens `/api/v1/admin/*` instead of the API token |
| `MASQ_PLAYLIST_PATH` | Yes with the example sources | The secret path of the masqueradarr playlist, referenced by `deploy/sources.json`; the server fails to start when a referenced variable is unset |
| `SOFA_RESTREAM_ORIGINS` | No | LAN restream proxies whose 502 counts against the offering, for example `192.168.1.3:3000` |
| `SOFA_PROBE_PARALLELISM` | No | Concurrent source probes, default 6 |
| `SOFA_MAX_SESSIONS` | No | Active playback sessions, default 4; player streams count too |
| `SOFA_OUTPUT_TOKEN` | For players | At least 32 letters, digits, hyphens or underscores; opens the M3U/XMLTV output and its stream URLs under `/output/*` through `?token=` |
| `SOFA_XMLTV_DAYS` | No | Days ahead in the XMLTV output, default 3 |
| `SOFA_HDHR_ENABLED` | No | HDHomeRun emulation for Plex at the root of the port, default `true` |
| `SOFA_HDHR_ALLOW` | For Plex | Client IPs or CIDR blocks allowed on the unauthenticated HDHomeRun routes, for example the Plex server's address; required unless `SOFA_HDHR_ENABLED=false`, since the container binds `0.0.0.0` |
| `SOFA_HDHR_TUNERS` | No | Tuners Plex sees, default 2, at most `SOFA_MAX_SESSIONS` |
| `SOFA_HDHR_EXTRA_PORTS` | For large lineups in Plex | Container ports for more HDHomeRun devices, each carrying part of the lineup; publish each one in `compose.yaml` |
| `SOFA_HDHR_DEVICE_ID`, `SOFA_HDHR_NAME` | No | The device identity Plex stores; the ID defaults to a value derived from the server identity in the volume |

Every variable in [configuration](live-tv.md#server-settings) can be added to `deploy/.env`. `JAVA_OPTS` reaches the JVM; without it the heap is a quarter of the container's memory limit.

## Build

Run from the repository root with JDK 21 installed:

```sh
./gradlew :server:installDist
docker compose -f deploy/compose.yaml build
```

On the shared development host use `/home/james/.claude/bin/gradle-queue -- :server:installDist` for the first step. On an Apple Silicon machine build for the amd64 host with `DOCKER_DEFAULT_PLATFORM=linux/amd64 docker compose -f deploy/compose.yaml build`.

## Configure

```sh
cp deploy/.env.example deploy/.env
cp deploy/sources.example.json deploy/sources.json
openssl rand -hex 32
```

Put generated values in `SOFA_API_TOKEN` and `SOFA_ADMIN_TOKEN` and the masqueradarr playlist path in `MASQ_PLAYLIST_PATH` in `deploy/.env`. `deploy/sources.json` lists the providers and guides as an object; edit the ids and URLs for your household and keep secrets as `${NAME}` references. A feed served by the same host as the container is reachable as `http://host.docker.internal:PORT/...` or by the host's LAN address; `127.0.0.1` would be the container itself and is refused as an upstream.

## Run

```sh
docker compose -f deploy/compose.yaml up -d
```

With the image already present, `up -d` does not rebuild; pass `--build` after a new `installDist`. Stopping sends SIGTERM and waits up to 20 seconds for the ordered shutdown.

```sh
docker compose -f deploy/compose.yaml logs -f
docker compose -f deploy/compose.yaml down
```

`down` keeps the `sofa-king-backend_sofa-data` volume; `down -v` deletes the database, the server identity and the logo cache.

### Data volume growth

`/data` holds `backend.sqlite` (with its WAL and shared-memory files) and `logos/`, one file per logo the lineup names. A logo is at most 2 MiB and most are tens of kilobytes, so a thousand-channel lineup stays well under 100 MB in practice; the hard bound is the entry count times 2 MiB. Logos the lineup no longer names are deleted on the next sweep (after an import or lineup change), so the directory tracks the lineup rather than growing without bound. The database keeps seven days of health observations by default (`SOFA_OBSERVATION_RETENTION_DAYS`) and the retained guide programmes of the mapped channels. The backup `tar` in [upgrade](#upgrade) includes the logos; a `/data` restored without `logos/` refetches them on the first sweep.

## Verify

Replace `OMV_HOST` with the host's address and export the token from `deploy/.env`:

```sh
curl --fail http://OMV_HOST:9193/readyz
docker inspect --format '{{.State.Health.Status}}' sofa-king-backend
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://OMV_HOST:9193/api/v1/providers
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://OMV_HOST:9193/api/v1/sources
```

`/readyz` answers `ready`, the health status is `healthy` once the start period has passed, each provider shows `lastSuccessAt` with its channel and source counts, and sources move from `UNVERIFIED` to `HEALTHY` as probes qualify them. A request without the token answers `401 AUTH_REQUIRED`. With 915 sources, six probes and the five-minute spacing per source, the first qualification pass takes over an hour; the import's protocol carries unqualified sources until then.

`/api/v1/channels` stays empty until the lineup exists. Check the guides with `curl --fail -H "Authorization: Bearer $SOFA_ADMIN_TOKEN" http://OMV_HOST:9193/api/v1/admin/guides`, then [migrate the Dispatcharr profile](live-tv.md#migrate-from-dispatcharr) with `scripts/dispatcharr-import.py --server http://OMV_HOST:9193`, or adopt a provider through `/api/v1/admin/lineup/adopt`. Point the TV client at `http://OMV_HOST:9193`. With `SOFA_OUTPUT_TOKEN` set, `curl --fail "http://OMV_HOST:9193/output/playlist.m3u?token=$SOFA_OUTPUT_TOKEN"` prints the playlist [third-party players](live-tv.md#third-party-players) load; its stream URLs carry `OMV_HOST:9193` because they derive from the request's `Host` header. `curl --fail http://OMV_HOST:9193/discover.json` from the Plex host shows the tuner [Plex](live-tv.md#plex-live-tv) is pointed at as `OMV_HOST:9193`; the one published port serves the API, the player outputs and the HDHomeRun routes, and no SSDP (UDP) port is needed because Plex takes the address by hand.

## Upgrade

```sh
git pull
./gradlew :server:installDist
docker compose -f deploy/compose.yaml build
docker compose -f deploy/compose.yaml stop
docker run --rm -v sofa-king-backend_sofa-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/sofa-data-$(date +%F).tgz -C /data .
docker compose -f deploy/compose.yaml up -d
```

The service is stopped before the backup so the SQLite WAL is checkpointed and the archive is consistent. Schema migrations apply at startup and are one-way: an older image refuses to start on a newer database. To roll back, stop the service, restore the backup into the volume, and start the older image:

```sh
docker compose -f deploy/compose.yaml down
docker run --rm -v sofa-king-backend_sofa-data:/data -v "$PWD":/backup alpine \
  sh -c 'rm -rf /data/* && tar xzf /backup/sofa-data-DATE.tgz -C /data'
docker compose -f deploy/compose.yaml up -d
```

## Transfer the image to a host without a JDK

Build on a workstation, then move the image and the `deploy/` files:

```sh
docker save sofa-king-backend:local | gzip > sofa-king-backend.tar.gz
scp sofa-king-backend.tar.gz deploy/compose.yaml deploy/.env deploy/sources.json OMV_HOST:/srv/sofa-king-backend/
ssh OMV_HOST 'cd /srv/sofa-king-backend && gunzip -c sofa-king-backend.tar.gz | docker load && docker compose up -d --no-build'
```

`--no-build` runs the loaded image without the repository checkout the `build` section points at. OMV's compose plugin keeps each project in its own directory; `compose.yaml`, `.env` and `sources.json` go there together.

## Bind mount instead of a volume

To keep the database in a host directory, replace `sofa-data:/data` in `deploy/compose.yaml` with `./data:/data` and give the directory to the container user before the first start:

```sh
mkdir -p deploy/data
sudo chown 10001:10001 deploy/data
```

Without the ownership change the server cannot create its database and `/readyz` answers 503.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Compose refuses to start: `required variable SOFA_API_TOKEN is missing` | Create `deploy/.env` from the example and set the token. |
| Container exits at once; log says `SOFA_API_TOKEN must contain at least 32 characters` | The token is still the placeholder or too short. The same applies to `SOFA_ADMIN_TOKEN`. |
| Container exits at once; log says `Source configuration references unset variable` | `deploy/sources.json` uses `${NAME}` for a variable that `deploy/.env` does not set. |
| Container exits at once; log says `Not an IP address or CIDR block` | `SOFA_HDHR_ALLOW` still holds the placeholder or a hostname; list the Plex server's IP, or remove the line to admit the whole LAN. |
| Container exits at once; log says `java.io.IOException: Is a directory` | `deploy/sources.json` was missing at first start, so Docker created a directory in its place. Remove that directory, copy the example and start again. |
| Provider shows `IMPORT_FAILED` for a feed on the same host | Use `host.docker.internal` or the LAN address; loopback is refused. |
| `/api/v1/channels` is empty | The lineup has not been curated yet; see [lineup](live-tv.md#lineup). |
| `/readyz` answers 503 with a bind-mounted `/data` | The directory is not writable by uid 10001. |
| Sources never leave `UNVERIFIED` | Check `docker compose logs` for probe outcomes; the image's `ffmpeg` is `/usr/bin/ffmpeg`. |
