# Deploy with Docker

The image runs the installed distribution on `eclipse-temurin:21-jre-noble` with Ubuntu's FFmpeg and curl, as the non-root user `sofa` (uid 10001). The database lives in the `/data` volume, the server listens on container port 8080, and a `HEALTHCHECK` polls `/readyz` every 30 seconds after a 30-second start period. `deploy/compose.yaml` publishes host port 9193 and targets an amd64 host such as OMV with Docker Compose v2.

The server is built on the host and copied into the image. The build pulls a Gradle milestone and Kotlin and Ktor prereleases from JetBrains' EAP repository with a locked dependency set, so building inside Docker would repeat those downloads on every image build and fail whenever an artifact is unavailable; the host build uses the local Gradle cache and the image build only needs the base image and Ubuntu packages.

## Files

| File | Purpose |
| --- | --- |
| `Dockerfile` | Image definition; copies `server/build/install/server` to `/opt/sofa` |
| `.dockerignore` | Admits only the installed distribution into the build context |
| `deploy/compose.yaml` | Service, port mapping, volume, source file mount, token guard |
| `deploy/.env.example` | Environment template; copy to `deploy/.env` |
| `deploy/sources.example.json` | Source template for the Dispatcharr direct feed; copy to `deploy/sources.json` |

`deploy/.env` and `deploy/sources.json` are ignored by Git.

## Environment

The image sets `SOFA_HOST=0.0.0.0`, `SOFA_PORT=8080` and `SOFA_DATA_DIR=/data`. Compose sets `SOFA_SOURCES=/config/sources.json` and mounts `deploy/sources.json` there read-only. `deploy/.env` supplies the rest:

| Variable | Required | Description |
| --- | --- | --- |
| `SOFA_API_TOKEN` | Yes | At least 32 characters; the LAN bind refuses to start without it, and compose refuses to start when it is unset |
| `SOFA_RESTREAM_ORIGINS` | No | LAN restream proxies whose 502 counts against the offering, for example `192.168.1.3:3000` |
| `SOFA_PROBE_PARALLELISM` | No | Concurrent source probes, default 6 |
| `SOFA_MAX_SESSIONS` | No | Active playback sessions, default 4 |

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

Put the generated value in `SOFA_API_TOKEN` in `deploy/.env`. Edit `deploy/sources.json` for your feed; `defaultProtocol: "hls"` is right for Dispatcharr's `direct=true` output, whose extensionless stream URLs serve HLS. A feed served by the same host as the container is reachable as `http://host.docker.internal:PORT/...` or by the host's LAN address; `127.0.0.1` would be the container itself and is refused as an upstream.

## Run

```sh
docker compose -f deploy/compose.yaml up -d
```

With the image already present, `up -d` does not rebuild; pass `--build` after a new `installDist`. Stopping sends SIGTERM and waits up to 20 seconds for the ordered shutdown.

```sh
docker compose -f deploy/compose.yaml logs -f
docker compose -f deploy/compose.yaml down
```

`down` keeps the `sofa-king-backend_sofa-data` volume; `down -v` deletes the database and server identity.

## Verify

Replace `OMV_HOST` with the host's address and export the token from `deploy/.env`:

```sh
curl --fail http://OMV_HOST:9193/readyz
docker inspect --format '{{.State.Health.Status}}' sofa-king-backend
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://OMV_HOST:9193/api/v1/providers
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://OMV_HOST:9193/api/v1/sources
```

`/readyz` answers `ready`, the health status is `healthy` once the start period has passed, each provider shows `lastSuccessAt` with its channel and source counts, and sources move from `UNVERIFIED` to `HEALTHY` as probes qualify them. A request without the token answers `401 AUTH_REQUIRED`. With 915 sources, six probes and the five-minute spacing per source, the first qualification pass takes over an hour; `defaultProtocol` carries unqualified sources until then. Point the TV client at `http://OMV_HOST:9193`.

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
| Container exits at once; log says `SOFA_API_TOKEN must contain at least 32 characters` | The token is still the placeholder or too short. |
| Container exits at once; log says `java.io.IOException: Is a directory` | `deploy/sources.json` was missing at first start, so Docker created a directory in its place. Remove that directory, copy the example and start again. |
| Provider shows `IMPORT_FAILED` for a feed on the same host | Use `host.docker.internal` or the LAN address; loopback is refused. |
| `/readyz` answers 503 with a bind-mounted `/data` | The directory is not writable by uid 10001. |
| Sources never leave `UNVERIFIED` | Check `docker compose logs` for probe outcomes; the image's `ffmpeg` is `/usr/bin/ffmpeg`. |
