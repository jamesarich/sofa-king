# Deploy with Docker

The image runs the installed server on `eclipse-temurin:21-jre-noble` with Ubuntu's FFmpeg and curl, as the non-root user `sofa` (uid 10001). The database and the logo cache live in the `/data` volume. The server listens on container port 8080, and a health check polls `/readyz` every 30 seconds after a 30-second start period.

The server is built on the host and copied into the image. Gradle resolves the pinned dependencies, some of them prereleases, from their repositories on the host, so the image build needs only the base image and Ubuntu packages. The Compose file targets Docker Compose v2.

## Files

| File | Purpose |
| --- | --- |
| `Dockerfile` | Image definition; copies `server/build/install/server` to `/opt/sofa` |
| `.dockerignore` | Admits only the installed distribution into the build context |
| `deploy/compose.yaml` | Service, port mapping, volume, source file mount |
| `deploy/.env.example` | Environment template; copy to `deploy/.env` |
| `deploy/sources.example.json` | Source template; copy to `deploy/sources.json` |

`deploy/.env` and `deploy/sources.json` are ignored by Git.

## Environment

The image sets `SOFA_HOST=0.0.0.0`, `SOFA_PORT=8080` and `SOFA_DATA_DIR=/data`. Compose sets `SOFA_SOURCES=/config/sources.json` and mounts `deploy/sources.json` there read-only. Every variable in `deploy/.env` reaches the server, so `${NAME}` references in `sources.json` resolve from it.

Compose requires `SOFA_API_TOKEN`. Set `SOFA_ADMIN_TOKEN`, `SOFA_OUTPUT_TOKEN` and `SOFA_HDHR_ALLOW` as the [configuration table](live-tv.md#server-settings) describes for your players and Plex. Any other variable in that table can go in `deploy/.env` too.

`JAVA_OPTS` reaches the JVM. Without it the heap is a quarter of the container's memory limit.

## Build

Run from the repository root with JDK 21 installed:

```sh
./gradlew :server:installDist
docker compose -f deploy/compose.yaml build
```

For an amd64 host built from an Apple Silicon machine, set the platform: `DOCKER_DEFAULT_PLATFORM=linux/amd64 docker compose -f deploy/compose.yaml build`.

## Configure

```sh
cp deploy/.env.example deploy/.env
cp deploy/sources.example.json deploy/sources.json
openssl rand -hex 32
```

Put generated values in `SOFA_API_TOKEN` and `SOFA_ADMIN_TOKEN` in `deploy/.env`. Two entries in `deploy/sources.json` need your input:

- `pbs` takes `ZIP_CODE`, which selects its member stations. Replace it with your ZIP code.
- `example-m3u` is the M3U provider example. Replace its `playlistUrl` and `guideUrl`, or delete the entry. The server starts without it, and an unreachable playlist fails only its own import. Its URLs can carry a secret; keep those as `${NAME}` references and set the variables in `deploy/.env`. The [provider fields](live-tv.md#providers) are described in the configuration reference.

A feed served by the Docker host is reachable as `http://host.docker.internal:PORT/...`, which the Compose file maps for the container, or by the host's LAN address. `127.0.0.1` is the container itself and is refused as an upstream.

## Run

```sh
cd deploy
docker compose up -d
```

`up -d` does not rebuild an existing image, so pass `--build` after a new `installDist`. Stopping sends `SIGTERM` and waits up to 20 seconds for the ordered shutdown.

```sh
docker compose logs -f
docker compose down
```

`down` keeps the `sofa-king-backend_sofa-data` volume. `down -v` deletes the database, the server identity and the logo cache.

### Data volume

`/data` holds `backend.sqlite` (with its WAL and shared-memory files) and `logos/`, one file per logo the lineup names. Each logo is at most 2 MiB. Logos the lineup no longer names are deleted on the next sweep after an import or lineup change, so the directory follows the lineup. Health observations are kept for `SOFA_OBSERVATION_RETENTION_DAYS` (default 7).

## Verify

Set `SERVER_HOST` to the Docker host's address, and export the token from `deploy/.env`:

```sh
curl --fail http://SERVER_HOST:8080/readyz
docker inspect --format '{{.State.Health.Status}}' sofa-king-backend
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://SERVER_HOST:8080/api/v1/providers
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://SERVER_HOST:8080/api/v1/sources
```

`/readyz` answers `ready`, and the health status becomes `healthy` after the start period. Each provider shows `lastSuccessAt` with its channel and source counts. Sources move from `UNVERIFIED` to `HEALTHY` as probes qualify them. A request without the token answers `401 AUTH_REQUIRED`. Qualifying a large source list takes a while, and until a source is probed its protocol comes from the import.

`/api/v1/channels` stays empty until the lineup exists. Check the guides with `GET /api/v1/admin/guides`, then either adopt a provider through `POST /api/v1/admin/lineup/adopt` or [migrate a Dispatcharr profile](migrate-from-dispatcharr.md). The [Sofa King app](../README.md#clients) connects to `http://SERVER_HOST:8080`.

With `SOFA_OUTPUT_TOKEN` set, `curl --fail "http://SERVER_HOST:8080/output/playlist.m3u?token=$SOFA_OUTPUT_TOKEN"` prints the playlist that [M3U and XMLTV players](live-tv.md#third-party-players) load. Its stream URLs carry the address the request used, so they work through a port mapping. For Plex, `curl --fail http://SERVER_HOST:8080/discover.json` from the Plex host shows the tuner that [Plex](live-tv.md#plex-live-tv) is pointed at. Plex finds the device by its address, so no SSDP (UDP) port is needed.

Upgrades and rollbacks are covered in [operations](operations.md#upgrade-and-rollback).

## Transfer the image to a host without a JDK

Build on a workstation, then move the image and the `deploy/` files:

```sh
docker save sofa-king-backend:local | gzip > sofa-king-backend.tar.gz
scp sofa-king-backend.tar.gz deploy/compose.yaml deploy/.env deploy/sources.json HOST:/srv/sofa-king-backend/
ssh HOST 'cd /srv/sofa-king-backend && gunzip -c sofa-king-backend.tar.gz | docker load && docker compose up -d --no-build'
```

`--no-build` runs the loaded image without the repository checkout that the `build` section points at. `compose.yaml`, `.env` and `sources.json` go into the same directory.

## Bind mount instead of a volume

To keep the database in a host directory, replace `sofa-data:/data` in `deploy/compose.yaml` with `./data:/data`, and give the directory to the container user before the first start:

```sh
mkdir -p deploy/data
sudo chown 10001:10001 deploy/data
```

Without the ownership change the server cannot create its database, and `/readyz` answers 503.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Compose refuses to start with a message about `SOFA_API_TOKEN` | Create `deploy/.env` from the example and set the token. |
| The container exits at once; the log says `SOFA_API_TOKEN must contain at least 32 characters` | The token is still the placeholder or too short. The same applies to `SOFA_ADMIN_TOKEN` and `SOFA_OUTPUT_TOKEN`, which need 32 characters of letters, digits, hyphens or underscores. |
| The container exits at once; the log says `Source configuration references unset variable` | `deploy/sources.json` uses `${NAME}` for a variable that `deploy/.env` does not set. |
| The container exits at once; the log says `Not an IP address or CIDR block` | `SOFA_HDHR_ALLOW` still holds the placeholder or a hostname. List the Plex server's IP address, or set `SOFA_HDHR_ENABLED=false`. |
| The container exits at once and Docker reports a directory where the file belongs | `deploy/sources.json` was missing at the first start, so Docker created a directory in its place. Remove the directory, copy the example, and start again. |
| A provider shows `IMPORT_FAILED` for a feed on the Docker host | Use `host.docker.internal` or the LAN address. Loopback is refused. |
| `/api/v1/channels` is empty | The lineup has not been built yet. See [lineup](live-tv.md#lineup). |
| `/readyz` answers 503 with a bind-mounted `/data` | The directory is not writable by uid 10001. |
| Sources stay `UNVERIFIED` | Check `docker compose logs` for probe outcomes. |
