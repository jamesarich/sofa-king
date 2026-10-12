# Deploy with Docker

The image runs the server on Eclipse Temurin 21 (`eclipse-temurin:21.0.12.1_1-jre-noble`, pinned by digest) with Ubuntu's FFmpeg and curl. Everything the server keeps lives in one folder, `/config`. It runs as any non-root user that owns that folder, listens on container port 8080, and reports healthy once `/readyz` answers. The Compose file needs Docker Compose 2.24 or later.

## Quick start

Run from the repository root with JDK 21 installed:

```sh
./gradlew :server:installDist
mkdir -p deploy/config
cd deploy
docker compose up -d --build
docker compose exec sofa-king-backend cat /config/secrets.json
```

1. The first start generates the API and admin tokens and stores them in `config/secrets.json`. The log names the file and the command above, never a token. To read the admin token alone, run `jq -r .adminToken config/secrets.json`.
2. Open `http://SERVER_HOST:9193/admin` and sign in with the admin token. **Settings** reveals the API token the [Sofa King app](../README.md#clients) needs.
3. Add your channels. Copy [`sources.example.json`](../deploy/sources.example.json) to `config/sources.json`, edit it as [configure the sources](starter-lineup.md#1-configure-the-sources) describes, and run `docker compose restart`. To build the lineup, put a [lineup policy](lineup-policy.md) in `config/policy.json` too, or follow [build a starter lineup](starter-lineup.md).

The server runs without `deploy/.env`. Copy [`.env.example`](../deploy/.env.example) to `deploy/.env` to change any [server setting](live-tv.md#server-settings); every variable in it reaches the server, so `${NAME}` references in `sources.json` resolve from it. Restart after a change with `docker compose up -d`.

The HDHomeRun emulation for Plex stays off until `.env` sets `SOFA_HDHR_ENABLED=true` and names the Plex server in `SOFA_HDHR_ALLOW`, as [Plex Live TV](live-tv.md#plex-live-tv) describes.

## The config folder

`deploy/config` is mounted at `/config`, the server's `SOFA_DATA_DIR`:

| Path | Written by | Contents |
| --- | --- | --- |
| `backend.sqlite` (with `-wal` and `-shm` while running) | Server | The lineup, guides, source health, provider session tokens and the server identity |
| `logos/` | Server | Cached lineup logos, at most 2 MiB each; logos the lineup no longer names are deleted |
| `secrets.json` | Server | The generated API and admin tokens, readable by the owner alone |
| `sources.json` | You | The [source file](live-tv.md#source-settings), used when `SOFA_SOURCES` is unset |
| `policy.json` | You and the server | The [lineup policy](lineup-policy.md), used when `SOFA_POLICY` is unset; the server writes numbers and edits back into it |

The log names the sources and policy files the server picked, and why, at every start. `SOFA_SOURCES` and `SOFA_POLICY` override the files in the folder. Back up the whole folder, as [operations](operations.md#backups) describes, and keep it out of version control: the database and `secrets.json` hold credentials.

## Run as your own user

Compose runs the container as `1000:1000`, the first user on most Linux hosts, so a `config` folder you created is writable by the server and its files stay yours. When `id -u` or `id -g` prints something else, set them in `deploy/.env`:

```sh
printf 'UID=%s\nGID=%s\n' "$(id -u)" "$(id -g)" >> .env
```

The image runs under any `user: "UID:GID"`. The process writes only `/config` and `/tmp`, so it needs no root step, no `PUID` or `PGID`, and no entry in the image's `/etc/passwd`. Without a `user:` line, it runs as the image's `sofa` user (uid 10001), which must then own the folder.

## Secrets

Left unset, `SOFA_API_TOKEN` and `SOFA_ADMIN_TOKEN` are generated on the first start and kept in `config/secrets.json`. A token set in the environment always wins over the stored one. To choose a token yourself, set it in `.env`, or keep it in a file and name the file with the `_FILE` variant: `SOFA_API_TOKEN_FILE`, `SOFA_ADMIN_TOKEN_FILE`, `SOFA_OUTPUT_TOKEN_FILE` and `SOFA_PLEX_TOKEN_FILE`. The server reads the file at start and drops a trailing line break. Setting both a variable and its `_FILE` variant, or naming a file it cannot read, stops the start.

With [Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/):

```yaml
services:
  sofa-king-backend:
    secrets:
      - sofa_output_token
    environment:
      SOFA_OUTPUT_TOKEN_FILE: /run/secrets/sofa_output_token

secrets:
  sofa_output_token:
    file: ./secrets/sofa_output_token
```

```sh
mkdir -p secrets && openssl rand -hex 16 > secrets/sofa_output_token && chmod 600 secrets/sofa_output_token
```

Compose mounts a file secret with its host owner and mode, so a file you own with mode `600` is readable by a container that runs as you.

## Time zone

`TZ` in `.env` sets the container's time zone, for example `TZ=America/Chicago`. The server stores and serves its times in UTC, so the guide is the same in any zone.

## Reverse proxy

The server can sit behind a TLS-terminating proxy on a subdomain of its own, such as `tv.example.com`. It builds the URLs in playlists, guides and the admin portal from the request's `Host` header, uses `https` when the proxy sends `X-Forwarded-Proto: https`, and then marks the admin session cookie `Secure`. It cannot be served under a sub-path such as `example.com/tv`.

[Caddy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy) passes `Host` and sets `X-Forwarded-Proto` by default:

```caddy
tv.example.com {
	reverse_proxy 127.0.0.1:9193
}
```

[Traefik](https://doc.traefik.io/traefik/routing/providers/docker/) does the same, with labels on the service and the container on Traefik's network:

```yaml
    labels:
      traefik.enable: "true"
      traefik.http.routers.sofa-king.rule: Host(`tv.example.com`)
      traefik.http.routers.sofa-king.entrypoints: websecure
      traefik.http.routers.sofa-king.tls.certresolver: letsencrypt
      traefik.http.services.sofa-king.loadbalancer.server.port: "8080"
```

Point Plex's HDHomeRun tuner at the published port, not the proxy: `SOFA_HDHR_ALLOW` admits clients by address, and behind a proxy every request comes from the proxy. Keep the proxy on your LAN or VPN, as [security](../SECURITY.md#intended-deployment) describes.

## Homepage dashboard

A [Homepage](https://gethomepage.dev/configs/docker/) instance with access to the Docker socket lists the server from these labels, which `compose.yaml` carries commented out:

```yaml
    labels:
      homepage.group: Media
      homepage.name: Sofa King
      homepage.icon: mdi-television-classic
      homepage.href: https://tv.example.com/admin
      homepage.description: Live TV
```

## Files

| File | Purpose |
| --- | --- |
| `Dockerfile` | Image definition; copies `server/build/install/server` to `/opt/sofa`, third-party jars first |
| `.dockerignore` | Admits only the installed distribution into the build context |
| `deploy/compose.yaml` | Service, port mapping, the config folder, [container limits](#container-limits) |
| `deploy/.env.example` | Optional settings; copy to `deploy/.env` |
| `deploy/sources.example.json` | Source template; copy to `deploy/config/sources.json` |
| `deploy/sofa-king-update.sh`, `deploy/systemd/` | Keeps a host on the published image, see [update automatically](#update-automatically) |
| `.github/workflows/image.yml` | Tests every push and publishes the image |

`deploy/.env` and `deploy/config/` are ignored by Git.

## Build

The server is built on the host and copied into the image, so the image build needs only the base image and Ubuntu packages. The third-party jars get their own layer below the server's, so a rebuild after a code change reuses every layer but the top one.

```sh
./gradlew :server:installDist
REVISION=$(git rev-parse --short HEAD) docker compose -f deploy/compose.yaml build
```

`REVISION` stamps the image's `org.opencontainers.image.revision` label; the build works without it. For an amd64 host built from an Apple Silicon machine, set `DOCKER_DEFAULT_PLATFORM=linux/amd64`. `docker compose up -d` does not rebuild an existing image, so pass `--build` after a new `installDist`.

### Container limits

The Compose file runs the container with a read-only root filesystem; the server writes only `/config` and temporary files in a 64 MiB `/tmp`. `/tmp` is mounted `exec` because the SQLite driver loads its native library from there; a `noexec` `/tmp` stops the server at startup. The container drops every capability and cannot gain privileges, runs at most 1,024 processes, and keeps three 10 MB log files. Stopping sends `SIGTERM` and waits up to 20 seconds for the ordered shutdown.

`mem_limit` (4 GiB) covers the JVM and its FFmpeg processes, and the heap is 75% of it (`SERVER_OPTS`; `JAVA_OPTS` adds to those flags). Each FFmpeg process takes tens of megabytes, so raise the limit with `SOFA_MAX_SESSIONS` or `SOFA_PROBE_PARALLELISM`.

## Verify

```sh
curl --fail http://SERVER_HOST:9193/readyz
docker inspect --format '{{.State.Health.Status}}' sofa-king-backend
TOKEN=$(jq -r .apiToken config/secrets.json)
curl --fail -H "Authorization: Bearer $TOKEN" http://SERVER_HOST:9193/api/v1/providers
```

`/readyz` answers `ready`, and the health status becomes `healthy` after the 30-second start period. Each provider shows `lastSuccessAt` with its channel and source counts, and sources move from `UNVERIFIED` to `HEALTHY` as probes qualify them. A request without the token answers `401 AUTH_REQUIRED`. When `SOFA_API_TOKEN` is set in `.env`, use that value instead of the stored one.

`/api/v1/channels` stays empty until a lineup policy builds the lineup. With `SOFA_OUTPUT_TOKEN` set, `http://SERVER_HOST:9193/output/playlist.m3u?token=OUTPUT_TOKEN` is the playlist [M3U and XMLTV players](live-tv.md#third-party-players) load; its stream URLs carry the address the request used. For Plex, `curl --fail http://SERVER_HOST:9193/discover.json` from the Plex host shows the tuner that [Plex](live-tv.md#plex-live-tv) is pointed at. When `SOFA_PLEX_TUNER_BASE` lets a sync add devices, set `SOFA_HDHR_PUBLIC_PORTS=9193` so Plex is given the published port.

Upgrades and rollbacks are covered in [operations](operations.md#upgrade-and-rollback).

## Update automatically

The [Image workflow](../.github/workflows/image.yml) tests every push to `main` and publishes the image to GitHub Container Registry as `ghcr.io/jamesarich/sofa-king-backend:main`, with a `sha-<commit>` tag beside it. A commit whose tests fail publishes nothing. Pull requests run the tests and build the image without publishing it.

`deploy/sofa-king-update.sh` keeps a host on that image. When the published image differs from the running one, it:

1. Backs up the database to `backups/` in the Compose directory, keeping the newest five. It finds the database through the container's `SOFA_DATA_DIR` and the mount that holds it. The backups hold provider session tokens, so only root can read them.
2. Tags the running image `sofa-king-backend:previous` and the new one `sofa-king-backend:local`.
3. Recreates the container and waits up to three minutes for it to report healthy.
4. If it does not, stops it, restores the backup and the previous image, and records the image in `backups/failed-image` so later runs skip it until a newer one is published.

It needs Python 3 on the host for the database backup. To install it, log Docker in to the registry with a [personal access token (classic)](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry#authenticating-with-a-personal-access-token-classic) that has only the `read:packages` scope, then add the script and the timer:

```sh
sudo docker login ghcr.io -u GITHUB_USER
sudo install -m 755 deploy/sofa-king-update.sh /srv/sofa-king-backend/
sudo install -m 644 deploy/systemd/sofa-king-update.service deploy/systemd/sofa-king-update.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now sofa-king-update.timer
```

Set `SOFA_UPDATE_DIR` in the service to the Compose directory if it is not `/srv/sofa-king-backend`. The timer checks every 15 minutes; `sudo systemctl start sofa-king-update` checks now, and `journalctl -u sofa-king-update` shows what each run did. Other settings come from the environment: `SOFA_UPDATE_IMAGE`, `SOFA_UPDATE_KEEP_BACKUPS` and `SOFA_UPDATE_HEALTH_SECONDS`. An image built by hand is replaced at the next check; stop the timer to keep it. The updater installs new images, not new copies of itself, so run the `install` lines again when `deploy/sofa-king-update.sh` or the units change.

## Deploy to a host without a JDK

Run `installDist` on a workstation and let the host's Docker build the image over SSH. Only the distribution (about 50 MB) crosses the network, and the host reuses its cached layers:

```sh
./gradlew :server:installDist
DOCKER_HOST=ssh://HOST REVISION=$(git rev-parse --short HEAD) docker compose -f deploy/compose.yaml build
ssh HOST 'cd /srv/sofa-king-backend && docker compose up -d --no-build'
```

The host directory holds `compose.yaml`, `.env` and `config/`. To move a finished image instead, about 800 MB before compression, save it on the workstation and load it on the host:

```sh
docker save sofa-king-backend:local | gzip > sofa-king-backend.tar.gz
scp sofa-king-backend.tar.gz deploy/compose.yaml HOST:/srv/sofa-king-backend/
ssh HOST 'cd /srv/sofa-king-backend && gunzip -c sofa-king-backend.tar.gz | docker load && docker compose up -d --no-build'
```

`--no-build` runs the image already on the host; there is no repository checkout there for the `build` section to point at.

## Move a database from a /data volume

A server whose database is in a `/data` volume, with `sources.json` and `policy/policy.json` mounted beside it, moves into the config folder like this. The image refuses to start on an empty `/config` while a database is mounted at `/data`, so an image that reaches the host first fails its health check and the updater puts the previous one back. From the Compose directory:

```sh
sudo systemctl stop sofa-king-update.timer
docker compose stop
mkdir -p config
docker run --rm -v sofa-king-backend_sofa-data:/data:ro -v "$PWD/config":/config alpine cp -a /data/. /config/
cp sources.json config/sources.json
cp policy/policy.json config/policy.json
sudo chown -R "$(id -u):$(id -g)" config
```

Then:

1. Replace `compose.yaml` with `deploy/compose.yaml`. To keep the address clients already use, change its port line to `"8080:8080"`; otherwise point the Sofa King app, players and Plex at port 9193.
2. In `.env`, delete any `SOFA_SOURCES` or `SOFA_POLICY` line, set `UID` and `GID` when `id -u` and `id -g` are not 1000, and keep `SOFA_HDHR_ENABLED=true` when Plex uses the tuner. Tokens set in `.env` keep working.
3. Install the updater again with the `install` lines in [update automatically](#update-automatically), and delete `backups/failed-image` if a run recorded the new image there.
4. Start with `docker compose up -d`. The log names `/config/sources.json` and `/config/policy.json`, and `/api/v1/channels` lists the same lineup.
5. Start the timer again with `sudo systemctl start sofa-king-update.timer`. Once the server has run well for a while, delete the old volume with `docker volume rm sofa-king-backend_sofa-data`.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| The container exits at once; the log says `/config is not writable by the user the server runs as` | `config` belongs to another user. Set `UID` and `GID` in `.env` to its owner, or `chown` it to them. Docker creates a missing bind-mount folder as root, so create it before the first start. |
| The container exits at once; the log says `must contain at least 32 characters` | A token set in `.env` or its file is too short. The output token needs 32 letters, digits, hyphens or underscores. |
| The container exits at once; the log says `Set SOFA_API_TOKEN or SOFA_API_TOKEN_FILE, not both` | Remove one of the two from `.env`. The same holds for each `_FILE` variable. |
| The container exits at once; the log says `/data/backend.sqlite exists but SOFA_DATA_DIR (/config) holds no database` | Move the database as [move a database from a /data volume](#move-a-database-from-a-data-volume) describes. |
| The container exits at once; the log says `Source configuration references unset variable` | `config/sources.json` uses `${NAME}` for a variable that `.env` does not set. |
| The container exits at once; the log says `Not an IP address or CIDR block` | `SOFA_HDHR_ALLOW` holds a placeholder or a hostname. List the Plex server's IP address. |
| The admin token line is gone from the log | It is printed only on the start that generates it. Read it with `jq -r .adminToken config/secrets.json`. |
| A provider shows `IMPORT_FAILED` for a feed on the Docker host | Use `host.docker.internal` or the LAN address. Loopback is refused. |
| `/api/v1/channels` is empty | The lineup has not been built yet. See [lineup](live-tv.md#lineup). |
| Sources stay `UNVERIFIED` | Check `docker compose logs` for probe outcomes. |
