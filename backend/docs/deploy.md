# Deploy with Docker

The image runs the server on Eclipse Temurin 21 (`eclipse-temurin:21.0.12.1_1-jre-noble`, pinned by digest) with Ubuntu's FFmpeg, curl and sqlite3, for `linux/amd64` and `linux/arm64`. Everything the server keeps lives in one folder, `/config`. It runs as any non-root user that owns that folder, listens on container port 8080, and reports healthy once `/readyz` answers. The Compose file needs Docker Compose 2.24 or later.

## Start

[Getting started](getting-started.md) builds the image from `backend/`, starts it, signs in and walks to a playing channel. To read the admin token alone, run `jq -r .adminToken config/secrets.json` in `deploy`. For the channels, copy [`sources.example.json`](../deploy/sources.example.json) to `config/sources.json` and edit it as [configure the sources](starter-lineup.md#1-configure-the-sources) describes; to build the lineup, put a [lineup policy](lineup-policy.md) in `config/policy.json` too.

The server runs without `deploy/.env`. Copy [`.env.example`](../deploy/.env.example) to `deploy/.env` to change any [server setting](live-tv.md#server-settings); every variable in it reaches the server, so `${NAME}` references in `sources.json` resolve from it. Restart after a change with `docker compose up -d`.

The HDHomeRun emulation for Plex stays off until `.env` sets `SOFA_HDHR_ENABLED=true` and names the Plex server in `SOFA_HDHR_ALLOW`, as [Plex Live TV](live-tv.md#plex-live-tv) describes.

## The config folder

`deploy/config` is mounted at `/config`, the server's `SOFA_DATA_DIR`:

| Path | Written by | Contents |
| --- | --- | --- |
| `backend.sqlite` (with `-wal` and `-shm` while running) | Server | The lineup, guides, source health, provider session tokens and the server identity |
| `logos/` | Server | Cached lineup logos, at most 2 MiB each; a logo the lineup does not name is deleted |
| `backups/` | The updater and you | Database backups, readable by their owner alone; see [backups](operations.md#backups) |
| `secrets.json` | Server | The generated API and admin tokens, readable by the owner alone |
| `sources.json` | You | The [source file](live-tv.md#source-settings), used when `SOFA_SOURCES` is unset |
| `policy.json` | You and the server | The [lineup policy](lineup-policy.md), used when `SOFA_POLICY` is unset; the server writes numbers and edits back into it |

The log names the sources and policy files the server picked, and why, at every start. `SOFA_SOURCES` and `SOFA_POLICY` override the files in the folder. Back up the whole folder, as [operations](operations.md#backups) describes, and keep it out of version control: the database and `secrets.json` hold credentials.

## The recordings folder

`deploy/recordings` is mounted at `/recordings`, the server's `SOFA_RECORDINGS_DIR`. Create it next to `config` before the first start, since Docker creates a missing folder as root and the server then cannot write to it. To keep recordings on another disk, change the left side of the mount, for example `/mnt/disk/recordings:/recordings`, and give the folder to the user the container runs as. Without the mount and the variable, recordings go in `config/recordings`.

Recordings are MPEG-TS files, stream-copied from the source, in the layout Plex, Jellyfin, Emby and Kodi scan without help, so a media server can use the folder as its TV and movie libraries:

| Recording | Path in the recordings folder |
| --- | --- |
| An episode the guide numbers | `TV/Show/Season 01/Show - S01E02 - Episode.ts` |
| An episode without a season and episode number, such as news or sports | `TV/Show/Season 2026/Show - 2026-10-17 - Episode.ts`, dated in the server's time zone |
| A movie | `Movies/Title (1995)/Title (1995).ts`, without the year when the guide gives none |

A second recording with the same name gets its start time, as in `Show - 2026-10-17 (18.30).ts`. Names leave out `/ \ : * ? " < > |` and control characters. A recording is written under the hidden `.inprogress` folder at the same path and moved into place when it ends, so a scanner never picks up a partial file. [Recordings](recordings.md) covers scheduling them.

## The media folder

[Media libraries](libraries.md) are added from `SOFA_MEDIA_DIR`. To use them, uncomment the `SOFA_MEDIA_DIR: /media` line and the `/media` mount in `compose.yaml`, and change the left side of the mount to the folder that holds your movies and shows, for example `/mnt/disk/media:/media:ro`. Keep the `:ro`: the server only reads the folder. The user the container runs as needs read access to it. Then add each library on the portal's Library page.

## Run as your own user

Compose runs the container as `1000:1000`, the first user on most Linux hosts, so a `config` folder you created is writable by the server and its files stay yours. When `id -u` or `id -g` prints something else, set them in `deploy/.env`:

```sh
printf 'UID=%s\nGID=%s\n' "$(id -u)" "$(id -g)" >> .env
```

The image runs under any `user: "UID:GID"`. The process writes only `/config`, `/recordings` and `/tmp`, so it needs no root step, no `PUID` or `PGID`, and no entry in the image's `/etc/passwd`. Without a `user:` line, it runs as the image's `sofa` user (uid 10001), which must then own the folder.

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

Point Plex's HDHomeRun tuner at the published port, not the proxy: `SOFA_HDHR_ALLOW` admits clients by address, and behind a proxy every request comes from the proxy. Keep the proxy on your LAN or VPN, as [security](security.md#intended-deployment) describes.

## Serve the web app

The Sofa King app's web build is a static site. Unzip `sofa-king-snapshot-web-wasm.zip` from the [snapshot release](https://github.com/jamesarich/sofa-king/releases/tag/snapshot), or `sofa-king-snapshot-web-js.zip` for browsers without WebAssembly GC. It holds `index.html`, `sofaking.js`, two `.wasm` files and `composeResources/`. The web app plays HLS channels only.

To serve it from the same origin as the API, the proxy forwards exactly `/api/`, `/media/` and `/logos/` at the origin root to the server and serves everything else from the bundle. It must serve `.wasm` files as `application/wasm`, which Caddy's `file_server` and nginx's stock `mime.types` do. The server cannot be served under a sub-path, so neither can the web app.

With [Caddy](https://caddyserver.com/docs/caddyfile/directives/handle):

```caddy
http://tv.example.lan {
	@server path /api/* /media/* /logos/*
	handle @server {
		reverse_proxy SERVER_HOST:9193
	}
	handle {
		root * /srv/sofa-king-web
		file_server
	}
}
```

With nginx, inside `http` with the stock `mime.types` included:

```nginx
server {
    listen 80;
    server_name tv.example.lan;
    root /srv/sofa-king-web;

    location ~ ^/(api|media|logos)/ {
        proxy_pass http://SERVER_HOST:9193;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
    }
}
```

Both serve plain HTTP, like the server on the LAN. For HTTPS, give Caddy a public hostname, or give nginx a certificate, and enter the `https://` address; a page served over HTTPS cannot call a plain-HTTP server, so the proxy is then the only way in.

Open the proxy's address in a browser. The web app does not find the server on its own: in the app's Settings, enter the proxy's own address, the origin with no path such as `http://tv.example.lan`, and the API token, which playback through the proxy needs like any client. The browser saves the address per origin in its `localStorage`. When the proxy terminates TLS, the server reads `X-Forwarded-Proto` as [reverse proxy](#reverse-proxy) describes.

Without a proxy, serve the bundle from any static server and add the page's origin to [`SOFA_CORS_ORIGINS`](live-tv.md#server-settings). The app's Settings then takes the server's own address.

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
| `.github/workflows/backend.yml` (at the repository root) | Tests pull requests and pushes to `main`, and publishes the image for `main`, as [contributing](../../CONTRIBUTING.md#ci) describes |

`deploy/.env`, `deploy/config/` and `deploy/recordings/` are ignored by Git.

## Build

The server is built on the host and copied into the image, so the image build needs only the base image and Ubuntu packages. The third-party jars get their own layer below the server's, so a rebuild after a code change reuses every layer but the top one. From `backend/`:

```sh
./gradlew :server:installDist
REVISION=$(git rev-parse --short HEAD) docker compose -f deploy/compose.yaml build
```

`REVISION` stamps the image's `org.opencontainers.image.revision` label and sets `SOFA_REVISION`, which [`/api/v1/admin/status`](api.md#server-status) and the admin portal footer report; `VERSION` stamps `org.opencontainers.image.version`, which the workflow reads from the catalog's `sofaking` version. The build works without either. The build targets the platform of the Docker host that runs it, `linux/amd64` or `linux/arm64`. To build for another platform, for example an amd64 host from an Apple Silicon machine, set `DOCKER_DEFAULT_PLATFORM=linux/amd64`. `docker compose up -d` does not rebuild an existing image, so pass `--build` after a new `installDist`.

### Container limits

The Compose file runs the container with a read-only root filesystem; the server writes only `/config`, `/recordings` and temporary files in a 64 MiB `/tmp`. `/tmp` is mounted `exec` because the SQLite driver loads its native library from there; a `noexec` `/tmp` stops the server at startup. The container drops every capability and cannot gain privileges, runs at most 1,024 processes, and keeps three 10 MB log files. Stopping sends `SIGTERM` and waits up to 20 seconds for the ordered shutdown.

`mem_limit` (4 GiB) covers the JVM and its FFmpeg processes, and the heap is 75% of it (`SERVER_OPTS`; `JAVA_OPTS` adds to those flags). Each FFmpeg process takes tens of megabytes, so raise the limit with `SOFA_MAX_SESSIONS` or `SOFA_PROBE_PARALLELISM`.

`SERVER_OPTS` also sets `-Djava.net.preferIPv4Stack=true`. The server connects to the first address a host resolves to, and Docker's resolver answers with IPv6 addresses first for many CDN hosts while the default Compose network has no IPv6 route, which fails every request to those hosts with a socket error. `SERVER_OPTS` comes last on the JVM command line, so `JAVA_OPTS` cannot undo it; on a host whose Docker network routes IPv6, set the whole `SERVER_OPTS` in `.env` without that flag.

## Verify

From `backend/deploy`:

```sh
curl --fail http://SERVER_HOST:9193/readyz
docker inspect --format '{{.State.Health.Status}}' sofa-king-backend
SOFA_API_TOKEN=$(jq -r .apiToken config/secrets.json)
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://SERVER_HOST:9193/api/v1/providers
```

`/readyz` answers `ready`, and the health status becomes `healthy` after the 30-second start period. Each provider shows `lastSuccessAt` with its channel and source counts, and sources move from `UNVERIFIED` to `HEALTHY` as probes qualify them. A request without the token answers `401 AUTH_REQUIRED`. When `SOFA_API_TOKEN` is set in `.env`, use that value instead of the stored one.

`/api/v1/channels` stays empty until a lineup policy, or the admin API, builds the lineup. With `SOFA_OUTPUT_TOKEN` set, `http://SERVER_HOST:9193/output/playlist.m3u?token=OUTPUT_TOKEN` is the playlist [M3U and XMLTV players](live-tv.md#third-party-players) load; its stream URLs carry the address the request used. For Plex, `curl --fail http://SERVER_HOST:9193/discover.json` from the Plex host shows the tuner that [Plex](live-tv.md#plex-live-tv) is pointed at. When `SOFA_PLEX_TUNER_BASE` lets a sync add devices, set `SOFA_HDHR_PUBLIC_PORTS=9193` so Plex is given the published port.

Upgrades and rollbacks are covered in [operations](operations.md#upgrade-and-rollback).

## The published image

For `main`, the [Backend workflow](../../CONTRIBUTING.md#ci) builds the image for `linux/amd64` and `linux/arm64` from the tested distribution and pushes it to GitHub Container Registry as `ghcr.io/jamesarich/sofa-king:main`, with a `sha-<commit>` tag beside it. A commit whose tests fail publishes nothing.

The package is public, so pulling it needs no login. To run the published image instead of a local build, tag it as the image `compose.yaml` names and start without building, from `backend/`:

```sh
docker pull ghcr.io/jamesarich/sofa-king:main
docker tag ghcr.io/jamesarich/sofa-king:main sofa-king-backend:local
cd deploy && docker compose up -d --no-build
```

Docker pulls the variant for the host, so the same tag serves `amd64` and `arm64` hosts.

### Inspect the published image

Each published image, for `linux/amd64` and `linux/arm64`, carries a BuildKit SBOM and max-mode build provenance in the registry. The provenance records how the image was built, including the source repository, the commit and the build arguments. The SBOM lists the packages inside the image. Print them:

```sh
docker buildx imagetools inspect ghcr.io/jamesarich/sofa-king:main --format '{{json .Provenance}}'
docker buildx imagetools inspect ghcr.io/jamesarich/sofa-king:main --format '{{json .SBOM}}'
```

Compare the commit in the provenance with the `org.opencontainers.image.revision` label of the image you run.

## Update automatically

`deploy/sofa-king-update.sh` keeps a host on the [published image](#the-published-image). When the published image differs from the running one, it:

1. Backs up the database with the running container's `sqlite3` to `backups/` in the config folder. The container writes the copy as its own user, readable by that user alone. When that fails, as with an image that has no `sqlite3`, the host's Python 3 writes the copy to `backups/` in the Compose directory instead, readable by root alone. Each folder keeps the newest five. It finds the database through the container's `SOFA_DATA_DIR` and the mount that holds it. The backups hold provider session tokens.
2. Tags the running image `sofa-king-backend:previous` and the new one `sofa-king-backend:local`.
3. Recreates the container and waits up to three minutes for it to report healthy.
4. If it does not, stops it, restores the backup and the previous image, and records the image in `backups/failed-image` in the Compose directory so later runs skip it until a newer one is published.

Every run, including one that finds nothing new, writes `update-status.json` to the config folder: when it checked, the image, the running and candidate revisions, the outcome (`up-to-date`, `updated`, `rolled-back`, `skipped` or `failed`) and one line saying why. The admin portal's [About page](admin.md#update) shows it, so the portal says when the host last checked and what it did. The file is owned by the config folder's owner and holds no secret.

It needs Python 3 on the host only for the fallback backup. To install it, add the script, the timer and the path unit:

```sh
sudo install -m 755 deploy/sofa-king-update.sh /srv/sofa-king-backend/
sudo install -m 644 deploy/systemd/sofa-king-update.service deploy/systemd/sofa-king-update.timer deploy/systemd/sofa-king-update.path /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now sofa-king-update.timer sofa-king-update.path
```

Set `SOFA_UPDATE_DIR` in the service to the Compose directory if it is not `/srv/sofa-king-backend`, and the `PathExists` line of the path unit to `update-requested` in that directory's `config` folder. The timer checks every 15 minutes; `sudo systemctl start sofa-king-update` checks now, and `journalctl -u sofa-king-update` shows what each run did. The path unit starts the same service when the portal's Update now writes `config/update-requested`, so an update asked for in the portal starts within seconds; the script removes the file as it starts. Other settings come from the environment: `SOFA_UPDATE_IMAGE`, `SOFA_UPDATE_CONFIG_DIR` (the folder with the status and request files, `config` under the Compose directory), `SOFA_UPDATE_CONTAINER` (`sofa-king-backend`), `SOFA_UPDATE_LOCAL_TAG` and `SOFA_UPDATE_PREVIOUS_TAG` (`sofa-king-backend:local` and `sofa-king-backend:previous`), `SOFA_UPDATE_KEEP_BACKUPS` and `SOFA_UPDATE_HEALTH_SECONDS`. An image built by hand is replaced at the next check; stop the timer and the path unit to keep it. The updater installs new images, not new copies of itself, so run the `install` lines again when `deploy/sofa-king-update.sh` or the units change.

The server checks the registry itself too, as [update](admin.md#update) describes: it reads the revision the `main` tag's index carries and compares it with its own, so the portal says whether an update is available before the updater runs. `SOFA_UPDATE_IMAGE` in `.env` names another image for that check, or set it empty to turn it off.

## Deploy to a host without a JDK

Run `installDist` on a workstation and let the host's Docker build the image over SSH. Only the distribution (about 50 MB) crosses the network, and the host reuses its cached layers. From `backend/` on the workstation:

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

## Move from a /data volume

The server refuses to start while `/data/backend.sqlite` exists and `SOFA_DATA_DIR` (`/config` in the image) holds no database. Its log then says `/data/backend.sqlite exists but SOFA_DATA_DIR (/config) holds no database`. These steps move a server whose database is in a Docker volume mounted at `/data`, with `sources.json` and `policy/policy.json` mounted beside it, into the config folder. Stop the updater first, so it does not start the new image before the move. From the Compose directory:

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
3. Install the updater with the `install` lines in [update automatically](#update-automatically), and delete `backups/failed-image` from the Compose directory if a run recorded the new image there.
4. Start with `docker compose up -d`. The log names `/config/sources.json` and `/config/policy.json`, and `/api/v1/channels` lists the same lineup.
5. Start the timer again with `sudo systemctl start sofa-king-update.timer`. Once the server has run well for a while, delete the old volume with `docker volume rm sofa-king-backend_sofa-data`.

## Troubleshooting

[Troubleshooting](troubleshooting.md) covers a container that does not start, tokens, ports, registry access, reverse proxies and Plex.
