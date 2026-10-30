# Deploy with Docker

The image runs the server on Eclipse Temurin 21 (`eclipse-temurin:21.0.12.1_1-jre-noble`, pinned by digest) with Ubuntu's FFmpeg, curl and sqlite3, for `linux/amd64` and `linux/arm64`. Everything the server keeps lives in one folder, `/config`. It runs as any non-root user that owns that folder, listens on port 8080 unless `SOFA_PORT` says otherwise, and reports healthy once `/readyz` answers. The Compose file needs Docker Compose 2.24 or later.

## Start

The server needs one folder on the host, the Compose directory, holding [`compose.yaml`](../deploy/compose.yaml), its `config` and `recordings` folders and, when you change a setting, `.env`. No copy of this repository and no build is needed: Compose runs the [published image](#the-published-image).

```sh
mkdir -p sofa-king/config sofa-king/recordings
cd sofa-king
curl -fsSLO https://raw.githubusercontent.com/jamesarich/sofa-king/main/backend/deploy/compose.yaml
docker compose up -d
```

[Getting started](getting-started.md) goes on from there to sign in and play a channel. The portal's Sources page writes the source file and the starter lineup; to write them by hand instead, copy [`sources.example.json`](../deploy/sources.example.json) to `config/sources.json` and edit it as [configure the sources](starter-lineup.md#1-configure-the-sources) describes, and put a [lineup policy](lineup-policy.md) in `config/policy.json`.

The server runs without `.env`. Download [`.env.example`](../deploy/.env.example) as `.env` to change any [server setting](live-tv.md#server-settings); every variable in it reaches the server, so `${NAME}` references in `sources.json` resolve from it. Restart after a change with `docker compose up -d`.

The HDHomeRun emulation for Plex is on, and admits no address until you allow the Plex server's on the portal's Plex page, as [Plex Live TV](live-tv.md#plex-live-tv) describes.

## Install on a NAS

The same image runs from a NAS's own container screens. Each keeps the [host networking](#host-networking) that app discovery and the HDHomeRun tuners need, and the [container limits](#container-limits) of `compose.yaml`.

### Unraid

[`unraid/sofa-king.xml`](../deploy/unraid/sofa-king.xml) is a Docker template with the config, recordings and media folders, host networking and the limits as extra parameters. On the Unraid console:

```sh
curl -fsSL -o /boot/config/plugins/dockerMan/templates-user/my-sofa-king.xml \
  https://raw.githubusercontent.com/jamesarich/sofa-king/main/backend/deploy/unraid/sofa-king.xml
mkdir -p /mnt/user/appdata/sofa-king /mnt/user/recordings
chown 99:100 /mnt/user/appdata/sofa-king /mnt/user/recordings
```

Then open Docker, choose Add Container, pick `sofa-king` under Template and apply. The container runs as Unraid's `nobody:users` (`99:100`), which owns the shares. The WebUI link opens the admin portal; the admin token is `adminToken` in `secrets.json` in the config folder. For a GPU, set the GPU device to `/dev/dri` and add `--group-add` with the id `stat -c %g /dev/dri/renderD128` prints to Extra Parameters.

### Synology

Container Manager (DSM 7.2 or later) runs `compose.yaml` as a project:

1. In File Station, create `docker/sofa-king` with `config` and `recordings` folders inside it.
2. Over SSH, print your user's ids with `id -u` and `id -g`, and save them in `docker/sofa-king/.env` as `UID=` and `GID=` lines, since the first DSM user is usually `1026:100`, not `1000:1000`.
3. In Container Manager, open Project, choose Create, set the path to `docker/sofa-king`, and paste [`compose.yaml`](../deploy/compose.yaml) as the source. Build it.

The project keeps `network_mode: host`, so the server answers on port 9193 of the NAS. A media share mounts read only by changing the commented `/media` line to its path, such as `/volume1/video:/media:ro`.

### Portainer

In Portainer, open Stacks, choose Add stack and paste [`compose.yaml`](../deploy/compose.yaml) in the web editor. Portainer resolves relative paths inside its own container, so change `./config` and `./recordings` to absolute paths on the host, such as `/srv/sofa-king/config:/config`, and create both folders, owned by the `UID:GID` the stack runs as, before deploying. Remove the `env_file` lines and set any [server setting](live-tv.md#server-settings), `UID` and `GID` included, under Environment variables instead.

## Host networking

`compose.yaml` runs the container on the host's network (`network_mode: host`) with `SOFA_PORT=9193`, so the server answers on port 9193 of every address the host has. That lets two things work that a Docker bridge network stops:

- The server [advertises itself over mDNS](api.md#discovery), so the Sofa King app finds it on the LAN. Multicast does not leave a bridge network.
- The HDHomeRun tuners see each client's own address, which is the one to allow for Plex.

When another service holds port 9193, the log says `Address already in use`; set `SOFA_PORT` in `.env` to a free port and use it wherever these pages say 9193. On Docker Desktop for macOS or Windows, turn on host networking in its settings first.

To stay on a bridge network instead, replace the `network_mode: host` line with a port mapping and turn the advertisement off, since it cannot reach the LAN from there. Clients then need the address, and the tuners see Docker's address for some clients:

```yaml
    ports:
      - "9193:9193"
```

```sh
printf 'SOFA_MDNS=false\n' >> .env
```

## The config folder

`config` in the Compose directory is mounted at `/config`, the server's `SOFA_DATA_DIR`. [State](operations.md#state) lists what the server keeps there and what you write, and [backups](operations.md#backups) how to back it up. Keep it out of version control: the database and `secrets.json` hold credentials.

## The recordings folder

`recordings` in the Compose directory is mounted at `/recordings`, the server's `SOFA_RECORDINGS_DIR`. Create it next to `config` before the first start, since Docker creates a missing folder as root and the server then cannot write to it. To keep recordings on another disk, change the left side of the mount, for example `/mnt/disk/recordings:/recordings`, and give the folder to the user the container runs as. Without the mount and the variable, recordings go in `config/recordings`.

Recordings are MPEG-TS files, stream-copied from the source, in the layout Plex, Jellyfin, Emby and Kodi scan without help, so a media server can use the folder as its TV and movie libraries:

| Recording | Path in the recordings folder |
| --- | --- |
| An episode the guide numbers | `TV/Show/Season 01/Show - S01E02 - Episode.ts` |
| An episode without a season and episode number, such as news or sports | `TV/Show/Season 2026/Show - 2026-10-17 - Episode.ts`, dated in the server's time zone |
| A movie | `Movies/Title (1995)/Title (1995).ts`, without the year when the guide gives none |

A second recording with the same name gets its start time, as in `Show - 2026-10-17 (18.30).ts`. Names leave out `/ \ : * ? " < > |` and control characters. A recording is written under the hidden `.inprogress` folder at the same path and moved into place when it ends, so a scanner never picks up a partial file. [Recordings](recordings.md) covers scheduling them.

## The media folder

[Media libraries](libraries.md) are added from `SOFA_MEDIA_DIR`. To use them, uncomment the `/media` mount in `compose.yaml`, which already sets `SOFA_MEDIA_DIR: /media`, and change the left side of the mount to the folder that holds your movies and shows, for example `/mnt/disk/media:/media:ro`. Keep the `:ro`: the server only reads the folder. The user the container runs as needs read access to it. Then add each library on the portal's Library page. To convert files on a GPU, see [hardware transcoding](libraries.md#hardware-transcoding).

## Run as your own user

Compose runs the container as `1000:1000`, the first user on most Linux hosts, so a `config` folder you created is writable by the server and its files stay yours. When `id -u` or `id -g` prints something else, set them in `.env`:

```sh
printf 'UID=%s\nGID=%s\n' "$(id -u)" "$(id -g)" >> .env
```

The image runs under any `user: "UID:GID"`. The process writes only `/config`, `/recordings` and `/tmp`, so it needs no root step, no `PUID` or `PGID`, and no entry in the image's `/etc/passwd`. Without a `user:` line, it runs as the image's `sofa` user (uid 10001), which must then own the folder.

## Secrets

Left unset, the tokens are generated on the first start, as [security](security.md#credentials-and-the-routes-they-open) describes. To choose a token yourself, set it in `.env`, or keep it in a file and name the file with the `_FILE` variant: `SOFA_API_TOKEN_FILE`, `SOFA_ADMIN_TOKEN_FILE`, `SOFA_OUTPUT_TOKEN_FILE` and `SOFA_PLEX_TOKEN_FILE`. The server reads the file at start and drops a trailing line break. Setting both a variable and its `_FILE` variant, or naming a file it cannot read, stops the start.

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

[Traefik](https://doc.traefik.io/traefik/routing/providers/docker/) does the same, with labels on the service. The container is on the host's network, so Traefik reaches it on the host's port 9193:

```yaml
    labels:
      traefik.enable: "true"
      traefik.http.routers.sofa-king.rule: Host(`tv.example.com`)
      traefik.http.routers.sofa-king.entrypoints: websecure
      traefik.http.routers.sofa-king.tls.certresolver: letsencrypt
      traefik.http.services.sofa-king.loadbalancer.server.port: "9193"
```

Point Plex's HDHomeRun tuner at port 9193, not the proxy: the tuners admit clients by address, and behind a proxy every request comes from the proxy. Keep the proxy on your LAN or VPN, as [security](security.md#intended-deployment) describes.

## Remote access

The server has no relay and no cloud account, and never opens a port on your router. To watch away from home, join the devices to a private network of your own. [Tailscale](https://tailscale.com/kb/1017/install) is the shortest route:

1. Install Tailscale on the Docker host and sign in. The container runs on the host's network, so it is reachable on the host's tailnet address and MagicDNS name at port 9193 with no change to `compose.yaml`.
2. Install Tailscale on the phone, tablet or laptop that travels, signed in to the same tailnet.
3. In the app's Settings, enter the host's MagicDNS name, such as `sofa-host` or `sofa-host.tailnet-name.ts.net`; the app adds port 9193. Discovery does not search the tailnet (the server's [mDNS advertisement](live-tv.md#server-settings) skips VPN interfaces), so type the name once.

Traffic between tailnet devices is encrypted by WireGuard. For HTTPS as well, run `tailscale serve --bg 9193` on the host and enter `https://sofa-host.tailnet-name.ts.net` instead; see [Tailscale serve](https://tailscale.com/kb/1312/serve). WireGuard, ZeroTier or any VPN you already run works the same way: the app needs only an address that reaches port 9193. Keep the tuners' [allowed addresses](live-tv.md#plex-live-tv) to the LAN.

## Serve the web app

The server serves the Sofa King app's web build itself: open `http://SERVER_HOST:9193/app/`, or the server's address alone, which opens it. The published image carries the build made from the same commit as the server, so the web app always matches the server it talks to and updates with it. The page and the API share the server's origin, so nothing needs a proxy or `SOFA_CORS_ORIGINS`; enter a pairing code or the API token in its Settings like any client, or open it paired from the portal's [Devices page](admin.md#devices). The page takes no token, sends its own content security policy, and keeps its service worker to `/app/`. A browser downloads about 6 MB the first time and then revalidates; the WebAssembly files are cached for good, since a new build names them anew. `SOFA_WEB_DIR` names the folder the server serves (`/opt/sofa/web` in the image); unset, or a folder without `index.html`, serves no web app and leaves `/` unanswered.

To serve the web app from another host instead, as below, unzip `sofa-king-snapshot-web-wasm.zip` from the [snapshot release](https://github.com/jamesarich/sofa-king/releases/tag/snapshot), or `sofa-king-snapshot-web-js.zip` for browsers without WebAssembly GC. It holds `index.html`, `sofaking.js`, two `.wasm` files, `composeResources/`, the web app manifest with its icons, and `sw.js`. The web app plays HLS channels only.

Served over HTTPS (or from `localhost`), the web app is installable: Chrome and Edge offer Install in the address bar, and Safari on iOS has Add to Home Screen. It then opens in its own window, in landscape. Its service worker, `sw.js`, keeps a copy of the app's own files, fetched from the network first so a new bundle shows on the next load, and never touches `/api/`, `/media/`, `/logos/` or `/admin`. Over plain HTTP the browser runs no service worker and offers no install; the app works the same in a tab.

To serve it from the same origin as the API, the proxy forwards exactly `/api/`, `/media/` and `/logos/` at the origin root to the server and serves everything else from the bundle. It must serve `.wasm` files as `application/wasm`, which Caddy's `file_server` and nginx's stock `mime.types` do. The server cannot be served under a sub-path, so a proxy serves the web app at its root.

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

Open the proxy's address in a browser. While no server is saved, the app's Settings fills the server address with the page's own origin, the proxy's address with no path such as `http://tv.example.lan`; enter a pairing code or the API token, which playback through the proxy needs like any client. The browser saves the address per origin in its `localStorage`. When the proxy terminates TLS, the server reads `X-Forwarded-Proto` as [reverse proxy](#reverse-proxy) describes.

Without a proxy, serve the bundle from any static server and add the page's origin to [`SOFA_CORS_ORIGINS`](live-tv.md#server-settings). The app's Settings then takes the server's own address in place of the page's origin it fills in.

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

Homepage's [Custom API widget](https://gethomepage.dev/widgets/services/customapi/) shows the [dashboard summary](api.md#dashboard-summary) on that card. Make a read token on the portal's Settings page, under Access tokens, and add to the labels:

```yaml
      homepage.widget.type: customapi
      homepage.widget.url: http://sofa-king:9193/api/v1/status
      homepage.widget.headers.Authorization: Bearer READ_TOKEN
      homepage.widget.mappings[0].label: Channels
      homepage.widget.mappings[0].field: channels.enabled
      homepage.widget.mappings[1].label: Watching
      homepage.widget.mappings[1].field: sessions
      homepage.widget.mappings[2].label: Recording
      homepage.widget.mappings[2].field: recordings.running
      homepage.widget.mappings[3].label: Scheduled
      homepage.widget.mappings[3].field: recordings.scheduled
```

`sofa-king` is the service's name on a Docker network both containers share; use the server's address otherwise. A read token opens only the summary, the recordings calendar and the admin API's reads, so a leaked one changes nothing.

## Files

| File | Purpose |
| --- | --- |
| `Dockerfile` | Image definition; copies `server/build/install/server` to `/opt/sofa`, third-party jars first |
| `.dockerignore` | Admits only the installed distribution into the build context |
| `deploy/compose.yaml` | Service, the published image, port mapping, the config folder, [container limits](#container-limits) |
| `deploy/compose.build.yaml` | Builds the image from a checkout instead, see [build](#build) |
| `deploy/.env.example` | Optional settings; copy to `.env` beside `compose.yaml` |
| `deploy/sources.example.json` | Source template; copy to `config/sources.json` |
| `deploy/unraid/sofa-king.xml` | The Unraid Docker template, see [Unraid](#unraid) |
| `deploy/sofa-king-update.sh` | Keeps a host on the published image and installs its own systemd units, see [update automatically](#update-automatically) |
| `.github/workflows/backend.yml` (at the repository root) | Tests pull requests and pushes to `main`, and publishes the image for `main`, as [contributing](../../CONTRIBUTING.md#ci) describes |

`deploy/.env`, `deploy/config/` and `deploy/recordings/` are ignored by Git.

## Build

To run your own build instead of the published image, build it from a checkout. The server is built on the host and copied into the image, so the image build needs only the base image and Ubuntu packages. `deploy/compose.build.yaml` adds the build to `compose.yaml` and names the result `sofa-king-backend:local`. From `backend/`:

```sh
./gradlew :server:installDist
cd deploy
REVISION=$(git rev-parse --short HEAD) docker compose -f compose.yaml -f compose.build.yaml up -d --build
```

A build made this way serves no web app, since `backend/web` is empty. To include it, build it from `app/` with `./gradlew :ui:wasmJsBrowserDistribution` and copy `ui/build/dist/wasmJs/productionExecutable/` into `backend/web/` before the image build.

Later commands in `deploy` that recreate the container take both files, or set `SOFA_IMAGE=sofa-king-backend:local` in `.env`, which makes plain `docker compose up -d` run the local build.

`REVISION` stamps the image's `org.opencontainers.image.revision` label and sets `SOFA_REVISION`, which [`/api/v1/admin/status`](api.md#server-status) and the admin portal footer report. `VERSION` stamps `org.opencontainers.image.version` and sets `SOFA_VERSION`; the workflow takes it from [`.github/version.sh`](../../.github/version.sh), the same version as the app snapshot built from that commit. The build works without either. It targets the Docker host's platform (`linux/amd64` or `linux/arm64`); set `DOCKER_DEFAULT_PLATFORM=linux/amd64` to build for an amd64 host from Apple Silicon. `docker compose up -d` does not rebuild an existing image, so pass `--build` after a new `installDist`.

### Container limits

The Compose file runs the container with a read-only root filesystem; the server writes only `/config`, `/recordings` and a 64 MiB `/tmp`, which must stay `exec` because the SQLite driver loads its native library from there. The container drops every capability and cannot gain privileges, runs at most 1,024 processes, and keeps three 10 MB log files. Stopping sends `SIGTERM` and waits up to 20 seconds for the ordered shutdown.

`mem_limit` (4 GiB) covers the JVM and its FFmpeg processes, and the heap is 75% of it (`SERVER_OPTS`; `JAVA_OPTS` adds to those flags). Each FFmpeg process takes tens of megabytes, so raise the limit with `SOFA_MAX_SESSIONS` or `SOFA_PROBE_PARALLELISM`.

`SERVER_OPTS` also sets `-Djava.net.preferIPv4Stack=true`, since the default Compose network has no IPv6 route and Docker's resolver answers many CDN hosts with IPv6 addresses first, which fails every request to them. `JAVA_OPTS` cannot undo it; on a host whose Docker network routes IPv6, set the whole `SERVER_OPTS` in `.env` without that flag.

## Verify

From the Compose directory:

```sh
curl --fail http://SERVER_HOST:9193/readyz
docker inspect --format '{{.State.Health.Status}}' sofa-king-backend
SOFA_API_TOKEN=$(jq -r .apiToken config/secrets.json)
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://SERVER_HOST:9193/api/v1/providers
```

`/readyz` answers `ready`, and the health status becomes `healthy` after the 30-second start period. Each provider shows `lastSuccessAt` with its channel and source counts, and sources move from `UNVERIFIED` to `HEALTHY` as probes qualify them. A request without the token answers `401 AUTH_REQUIRED`. When `SOFA_API_TOKEN` is set in `.env`, use that value instead of the stored one.

`/api/v1/channels` stays empty until a lineup policy, or the admin API, builds the lineup. [Third-party players](live-tv.md#third-party-players) and [Plex](live-tv.md#plex-live-tv) have the playlist URL and the tuner check.

Upgrades and rollbacks are covered in [operations](operations.md#upgrade-and-rollback).

## The published image

For `main`, the [Backend workflow](../../CONTRIBUTING.md#ci) builds the image for `linux/amd64` and `linux/arm64` from the tested distribution and pushes it to GitHub Container Registry as `ghcr.io/jamesarich/sofa-king:main`, with a `sha-<commit>` tag beside it. For a release tag such as `v0.2.0` it pushes the same build as `:0.2.0`, `:0.2` and `:latest`. A commit whose tests fail publishes nothing.

Pick a channel by tag: `:latest` moves at each release, `:0.2` takes that minor version's patch releases only, and `:main` moves at every server change merged to `main`. Set the same tag in `compose.yaml`'s `image` and, for [automatic updates](#update-automatically), in `SOFA_UPDATE_IMAGE` for both the updater and the server, whose About page then compares against that tag. A host moves from `:main` to `:latest` only once a release is newer than the `main` build it runs, since migrations do not run backward.

The package is public, so pulling it needs no login, and `compose.yaml` runs it unless `.env` sets `SOFA_IMAGE`. Docker pulls the variant for the host, so the same tag serves `amd64` and `arm64` hosts. To update by hand, from the Compose directory:

```sh
docker compose pull
docker compose up -d
```

Take a backup first, as [upgrade and rollback](operations.md#upgrade-and-rollback) describes; the [updater](#update-automatically) does the backup and the rollback for you. Image watchers such as Watchtower, Diun or What's Up Docker see the tag like any other.

### Inspect the published image

Each published image, for `linux/amd64` and `linux/arm64`, carries a BuildKit SBOM and max-mode build provenance in the registry. The provenance records how the image was built, including the source repository, the commit and the build arguments. The SBOM lists the packages inside the image. Print them:

```sh
docker buildx imagetools inspect ghcr.io/jamesarich/sofa-king:main --format '{{json .Provenance}}'
docker buildx imagetools inspect ghcr.io/jamesarich/sofa-king:main --format '{{json .SBOM}}'
```

Compare the commit in the provenance with the `org.opencontainers.image.revision` label of the image you run.

Each published image is also signed with [Sigstore](https://www.sigstore.dev/)'s keyless signing and carries a GitHub build attestation, both made by `backend.yml` on `main` or a `v*` tag. Check either before you run an image:

```sh
cosign verify ghcr.io/jamesarich/sofa-king:main \
  --certificate-identity-regexp '^https://github.com/jamesarich/sofa-king/\.github/workflows/backend\.yml@refs/(heads/main|tags/v.*)$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify oci://ghcr.io/jamesarich/sofa-king:main --repo jamesarich/sofa-king
```

An image that this repository's workflow did not build fails both. The updater does not check either, so run them by hand when you want the signature verified before it installs an image.

## Update automatically

`deploy/sofa-king-update.sh` keeps a host on the [published image](#the-published-image). When the published image differs from the running one, it:

1. Backs up the database with the running container's `sqlite3` to `backups/` in the config folder. The container writes the copy as its own user, readable by that user alone. When that fails, as with an image that has no `sqlite3`, the host's Python 3 writes the copy to `backups/` in the Compose directory instead, readable by root alone. Each folder keeps the newest five. It finds the database through the container's `SOFA_DATA_DIR` and the mount that holds it. The backups hold provider session tokens.
2. Tags the running image `sofa-king-backend:previous` and points the image `compose.yaml` runs at the new one.
3. Recreates the container and waits up to three minutes for it to report healthy.
4. If it does not, stops it, restores the backup and the previous image (so whatever the new image wrote to the database during its health window, such as recordings started or finished and watch state, is discarded), and records the image in `backups/failed-image` in the Compose directory so later runs skip it until a newer one is published. A skipped image never stays behind the tag Compose runs, so a later `docker compose up -d` starts the image that was running.

Every run, including one that finds nothing new, writes `update-status.json` to the config folder: when it checked, the image, the running and candidate revisions, the outcome (`up-to-date`, `updated`, `rolled-back`, `skipped` or `failed`) and one line saying why. The admin portal's [About page](admin.md#update) shows it, so the portal says when the host last checked and what it did. The file is owned by the config folder's owner and holds no secret.

It needs Python 3 on the host only for the fallback backup. To install it, download it into the Compose directory and run its `install` there as root:

```sh
curl -fsSLO https://raw.githubusercontent.com/jamesarich/sofa-king/main/backend/deploy/sofa-king-update.sh
chmod 755 sofa-king-update.sh
sudo ./sofa-king-update.sh install
```

`install` writes `sofa-king-update.service`, `sofa-king-update.timer` and `sofa-king-update.path` to `/etc/systemd/system` for that directory and that copy of the script, then enables and starts the timer and the path unit. `sudo ./sofa-king-update.sh install /srv/sofa-king` installs for another Compose directory. The script stays where it is, so keep it there; the paths may not hold a space, `%` or a backslash. The timer checks every 15 minutes; `sudo systemctl start sofa-king-update` checks now, and `journalctl -u sofa-king-update` shows what each run did. The path unit starts the same service when the portal's Update now writes `config/update-requested`, so an update asked for in the portal starts within seconds; the script removes the file as it starts.

Other settings come from the environment: `SOFA_UPDATE_IMAGE` (the image to pull, `ghcr.io/jamesarich/sofa-king:main`; `:latest` for [releases only](#the-published-image)), `SOFA_UPDATE_CONFIG_DIR` (the folder with the status and request files, `config` under the Compose directory; set it when running `install` too), `SOFA_UPDATE_CONTAINER` (`sofa-king-backend`), `SOFA_UPDATE_LOCAL_TAG` (the image `compose.yaml` runs, read with `docker compose config --images`) and `SOFA_UPDATE_PREVIOUS_TAG` (`sofa-king-backend:previous`), `SOFA_UPDATE_KEEP_BACKUPS` and `SOFA_UPDATE_HEALTH_SECONDS`. An image built by hand is replaced at the next check; stop the timer and the path unit to keep it. The updater installs new images, not new copies of itself, so download it again and run `install` again to take a newer script.

The server also checks the registry and writes `config/update-requested` for a newer revision, so the path unit installs it within minutes and the 15-minute timer is the fallback; [update](admin.md#update) describes the check.

## Deploy your build to a host without a JDK

Run `installDist` on a workstation and let the host's Docker build the image over SSH. Only the distribution (about 50 MB) crosses the network, and the host reuses its cached layers. The host's `.env` sets `SOFA_IMAGE=sofa-king-backend:local`, so its `compose.yaml` runs the build. From `backend/` on the workstation:

```sh
./gradlew :server:installDist
DOCKER_HOST=ssh://HOST REVISION=$(git rev-parse --short HEAD) docker compose -f deploy/compose.yaml -f deploy/compose.build.yaml build
ssh HOST 'cd /srv/sofa-king && docker compose up -d'
```

To move a finished image instead, about 800 MB before compression, save it on the workstation and load it on the host:

```sh
docker save sofa-king-backend:local | gzip > sofa-king-backend.tar.gz
scp sofa-king-backend.tar.gz HOST:/srv/sofa-king/
ssh HOST 'cd /srv/sofa-king && gunzip -c sofa-king-backend.tar.gz | docker load && docker compose up -d'
```

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

1. Replace `compose.yaml` with `deploy/compose.yaml`. To keep the address clients already use, set `SOFA_PORT=8080` in `.env`; otherwise point the Sofa King app, players and Plex at port 9193.
2. In `.env`, delete any `SOFA_SOURCES` or `SOFA_POLICY` line and set `UID` and `GID` when `id -u` and `id -g` are not 1000. Tokens set in `.env` keep working.
3. Install the updater as [update automatically](#update-automatically) describes, and delete `backups/failed-image` from the Compose directory if a run recorded the new image there.
4. Start with `docker compose up -d`. The log names `/config/sources.json` and `/config/policy.json`, and `/api/v1/channels` lists the same lineup.
5. Start the timer again with `sudo systemctl start sofa-king-update.timer`. Once the server has run well for a while, delete the old volume with `docker volume rm sofa-king-backend_sofa-data`.

## Troubleshooting

[Troubleshooting](troubleshooting.md) covers a container that does not start, tokens, ports, registry access, reverse proxies and Plex.
