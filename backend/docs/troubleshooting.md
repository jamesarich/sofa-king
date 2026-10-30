# Troubleshooting and FAQ

Start with the log. The admin portal's Events page (under System) shows the newest lines at INFO and above, and its Jobs page says when each import, policy run, Plex sync, recording job and library scan last ran and how it ended. With Docker, `docker compose logs --tail 200 sofa-king-backend` in `deploy` shows the whole log. Every response carries an `X-Request-Id` header, and the log lines for that request end with the same `requestId`, so a failing request is easy to find. Set `SOFA_LOG_LEVEL=debug` in `.env` and run `docker compose up -d` for more detail. [Logs](operations.md#logs) describes the format.

The examples use the Docker port, `SERVER_HOST:9193`, and the tokens in `$SOFA_API_TOKEN` and `$SOFA_ADMIN_TOKEN`. Outside Docker the port is 8080.

## The container does not start

| Symptom | Check |
| --- | --- |
| The log says `Address already in use`, or `docker compose up` says `port is already allocated` | Another service holds port 9193, or a port in `SOFA_HDHR_EXTRA_PORTS`. Set `SOFA_PORT` in `.env` to a free port, for example `SOFA_PORT=9200`, and use it everywhere these pages say 9193. |
| On Docker Desktop for macOS or Windows the container runs but nothing answers on port 9193 | Host networking is turned off in Docker Desktop's settings, so the container is on the network of Docker's own virtual machine. Turn it on, or run on a [bridge network](deploy.md#host-networking). |
| `docker pull` or the updater says `denied` or `unauthorized` | The image name is wrong, or Docker is logged in to `ghcr.io` as an account the registry refuses; the package itself is public and needs no login. Check `SOFA_UPDATE_IMAGE`, or `docker logout ghcr.io` and pull again. |
| The log says `/config is not writable by the user the server runs as` | `config` belongs to another user. Set `UID` and `GID` in `.env` to its owner, or `chown` it to them. Docker creates a missing bind-mount folder as root, so create it before the first start. See [run as your own user](deploy.md#run-as-your-own-user). |
| The log says `Not an IP address or CIDR block` | `SOFA_HDHR_ALLOW`, or an address entered on the Plex page, holds a placeholder or a hostname. List IP addresses or CIDR blocks. |
| The log says `SOFA_API_TOKEN must contain at least 32 characters` or `SOFA_ADMIN_TOKEN must contain at least 32 characters` | The token set in `.env` or its file is too short. Leave the API and admin tokens unset to have them generated, or use `openssl rand -hex 32`. |
| The log says `SOFA_OUTPUT_TOKEN must contain at least 32 letters, digits, hyphens or underscores` | The output token is too short or holds other characters. Use `openssl rand -hex 16`. |
| The log says `Set SOFA_API_TOKEN or SOFA_API_TOKEN_FILE, not both` | Remove one of the two from `.env`. The same holds for each `_FILE` variable. |
| The log says `/data/backend.sqlite exists but SOFA_DATA_DIR (/config) holds no database` | Move the database as [move from a /data volume](deploy.md#move-from-a-data-volume) describes. |
| The log says `Source configuration references unset variable` | `config/sources.json` uses `${NAME}` for a variable that `.env` does not set. |
| The log says `Unsupported database schema` | The database was written by a newer server. Run that server, or restore a backup taken before the upgrade, as [upgrade and rollback](operations.md#upgrade-and-rollback) describes. |
| The log names `SOFA_PLEX_URL`, `SOFA_PLEX_TOKEN` or `SOFA_PLEX_TUNER_BASE` | `SOFA_PLEX_URL` takes `http(s)://HOST[:PORT]` and `SOFA_PLEX_TUNER_BASE` takes `http(s)://HOST`, neither with a path, query or credentials. The token takes no spaces. The URL and the token are set together. |

## Signing in and tokens

| Symptom | Check |
| --- | --- |
| The admin token is not in the log | No token is logged. [Read it from `secrets.json`](getting-started.md#2-sign-in), or sign in and reveal the API token under Advanced on the Settings page. |
| `401 AUTH_REQUIRED` | Send the token in the `Authorization: Bearer` header. `/api/v1/admin/*` takes only the admin token whenever one is set, which a LAN bind always has. |
| The sign-in page answers `429 Too Many Requests` | More than 10 sign-ins came from one address within a minute. Wait a minute. Behind a reverse proxy every sign-in shares the proxy's address and its budget. |
| `413 REQUEST_TOO_LARGE` | The body is over 64 KiB, or over 4 MiB for a policy write. The server answers this before it checks the token. For a policy over 4 MiB, edit `config/policy.json` and restart instead. |

## Channels and sources

| Symptom | Check |
| --- | --- |
| `/api/v1/channels` is empty | The lineup has not been built, or every channel is off. Add a [lineup policy](lineup-policy.md). With `health.requireVerified`, stations come on as the probes verify a source of each, which takes a while for a large lineup. `GET /api/v1/admin/lineup` shows every entry. |
| No candidates | Add `sources.json` to the config folder or set `SOFA_SOURCES`, then check `/api/v1/providers` for `IMPORT_FAILED` and the playlist URL and format. |
| A provider shows `IMPORT_FAILED` for a playlist on the Docker host | Use `host.docker.internal` or the LAN address. Loopback is refused. |
| An import stays unchanged | A failed or empty import keeps the previous snapshot. Check the provider status and `allowEmpty`. |
| Sources stay `UNVERIFIED` or never qualify | Check `docker compose logs` for probe outcomes. Outside Docker, check that `SOFA_FFMPEG` names a working FFmpeg. Under a lineup policy only the sources its stations use are probed, whatever the provider's `probe` setting; without one, check that setting. See [probing](lineup-policy.md#probing). |
| A source shows `WITHDRAWN` | The provider's current list does not have that stream. The entry keeps the reference. Replace the source when the provider lists a new URL. |
| A channel vanished from the lineup | `GET /api/v1/admin/lineup/ENTRY_ID`: `autoDisabledAt` set means the lineup policy turned it off for want of an eligible source. `GET /api/v1/admin/policy/changes` has the change and `GET /api/v1/admin/stations/KEY` the station's candidates. |
| `503 SOURCE_UNAVAILABLE` | No source of the channel is selectable now. `GET /api/v1/admin/lineup/ENTRY_ID` shows each source's state, and `/api/v1/sources` shows quarantine, probation or a manual disable. |
| Extensionless HLS answers `410 STREAM_CHANGED` once | The source has not been probed yet. The first fetch detects HLS, stores it and starts a new epoch, so fetch the session again. Set `defaultProtocol` to `hls`, or `stream-type="hls"` on the entry, so unprobed sources start right. |

## Capacity exhausted

| Answer | Cause and fix |
| --- | --- |
| `429 CAPACITY_EXHAUSTED` | Every `SOFA_MAX_SESSIONS` slot holds a watch session or a busy player stream. Release unused sessions or wait for their 90-second leases to expire; previews and idle player sessions give way on their own. Raise `SOFA_MAX_SESSIONS` if the host can take it. |
| `429` on a media request | The request's session already has 12 requests in flight. |
| `503 CAPACITY_EXHAUSTED` playing a library file | `SOFA_MAX_TRANSCODES` files, virtual channels or multiview streams are already being copied or converted. Stop one, or raise it if the CPU or GPU keeps up. |

## Guide

| Symptom | Check |
| --- | --- |
| Channels show only their own name, hour by hour | Those are placeholder programs: the channel has no guide data in the window. The portal's Channels page filters channels whose guide is missing or ended. `GET /api/v1/admin/guides` shows `IMPORT_FAILED` for a guide whose fetch or parse failed. |
| A channel has no guide | Check the entry's `guide` mapping against `GET /api/v1/admin/guides/GUIDE_ID/channels`. A mapping changed moments ago is filled on the next guide fetch. |
| Plex has no guide at all | The guide URL in Plex needs the output token: `http://SERVER_HOST:9193/output/guide.xml?token=OUTPUT_TOKEN`. Open it in a browser to check it answers. Plex fetches the guide on its own schedule; refresh it from the DVR settings in Plex. |

## Players and logos

| Symptom | Check |
| --- | --- |
| A player gets `401` on the playlist URL | The `token` query differs from the output token; copy the URL again from the portal's Plex page. The API token opens `/output/*` only as a bearer header. |
| A player plays a channel as the wrong type after a probe | The stream suffix follows the stored protocol. Refresh the playlist in the player. |
| A channel shows no logo | `curl -i http://SERVER_HOST:9193/logos/KEY` with the key from the playlist. `404` means the logo source was refused or failed: a loopback or link-local address, a non-image content type, a body over 2 MiB, or an error. The log has `logo fetch failed key=KEY` with the reason, and the source is tried again an hour later. `GET /api/v1/admin/lineup/ENTRY_ID` shows the logo's source URL. |

## Finding the server

| Symptom | Check |
| --- | --- |
| The Sofa King app does not list the server | The app finds servers on its own network over mDNS. The log says `mdns: advertising` with the addresses it uses, at the start and again whenever they change; `no LAN address to advertise on` means none qualified yet. Discovery does not cross subnets or VLANs, does not leave a Docker bridge network (see [host networking](deploy.md#host-networking)), and stops at a firewall that drops UDP port 5353; `SOFA_MDNS=false` turns it off. Enter the address in the app instead, `SERVER_HOST:9193`. |
| `http://sofa-king.local:9193` does not open | The server answers that name over mDNS, with a number added when another device already holds it; the log's `mdns: advertising` line follows the start. Computers that do not resolve `.local` names, and networks that block mDNS, need the address. |

## Plex

| Symptom | Check |
| --- | --- |
| Plex does not find the tuner | Plex looks for tuners by SSDP, which the server does not answer. Enter `SERVER_HOST:9193` by hand. From the Plex host, `curl http://SERVER_HOST:9193/discover.json` answers `403` until Plex's address is allowed, and `404` when the emulation is off (`SOFA_HDHR_ENABLED=false`). The portal's Plex page lists the refused addresses under Tuner access, with Allow. |
| `403` while Plex runs in Docker on the same host | A Plex container on a Docker bridge network reaches the server from a Docker address, not the host's LAN address. The Plex page lists that address among the refused ones; Allow admits it. Allowing `172.16.0.0/12`, Docker's default address pools, admits it across container restarts, along with every other container on the host. Running Plex with `network_mode: host` avoids the bridge. |
| Plex is on another VLAN or subnet | Discovery never crosses subnets, so enter the address by hand. The firewall between the networks must allow TCP to port 9193 and to every port in `SOFA_HDHR_EXTRA_PORTS`. `SOFA_HDHR_ALLOW` must name Plex's address as the server sees it, which is the router's address when the router translates addresses between the networks. |
| Plex says all tuners are in use | `SOFA_HDHR_TUNERS` streams are open, every `SOFA_MAX_SESSIONS` slot is taken, or the channel has no eligible source. Check `GET /api/v1/admin/lineup/ENTRY_ID`. |
| A channel plays for a moment and stops | FFmpeg ended the remux. The log has `remux ended exit=` with FFmpeg's last lines, URLs removed. |
| "There was a problem saving channel mappings" | One device carries more channels than Plex saves at once, about 250. Split the lineup with `SOFA_HDHR_EXTRA_PORTS`, as [several devices](live-tv.md#several-devices-for-a-large-lineup) describes. |
| Plex channels lag the lineup | Check `lastError` and `findings` in `GET /api/v1/admin/plex`. `answered 401` means `SOFA_PLEX_TOKEN` is wrong, and a device in `missingDevices` is in no DVR. `POST /api/v1/admin/plex/sync` runs a sync now. Without Plex sync, run "Scan channels" for the DVR in Plex. |

## Recordings

| Symptom | Check |
| --- | --- |
| A recording is `failed` with `MISSED` | Its whole window passed while the server was down. A restart during a window resumes into the same file instead. |
| A recording is `failed` with `CAPACITY_EXHAUSTED` | Every playback slot was held by someone watching for the whole window; a recording takes a preview's or an idle player's slot but never a viewer's. Raise `SOFA_MAX_SESSIONS`, or check `conflict` on the scheduled ones. |
| Scheduling answers `PROGRAM_NOT_FOUND` | The channel has no guide, or the program is not in it. Map a guide to the channel, or record it as a [time window](recordings.md#record-a-time-window). |
| The home page says the server cannot write the recordings folder | Docker created `deploy/recordings` as root, or the folder belongs to another user. `chown` it to the container's user (`UID` and `GID` in `.env`, 1000 by default), or create it before the first start; see [the recordings folder](deploy.md#the-recordings-folder). |
| No commercial breaks on a recording | `commercials` is `done` with no `breaks` when Comskip found none, and `failed` when it could not run; the log has `commercial scan failed`. Comskip finds fewer breaks on channels without a logo or black frames between ads. Outside the image, set `SOFA_COMSKIP` to its path. |
| A recording ends early with `DISK_FULL` | The recordings folder's disk fell below 512 MiB free with no finished recording left to delete. Free space on that disk or move the folder to a bigger one. |
| Recordings disappear | Cleanup deleted them: the log names each `recording culled` with `KEEP`, `AGE` or `SPACE`. Protect a recording, raise a rule's `keep`, or lower `SOFA_RECORDINGS_FREE_GIB` or `SOFA_RECORDINGS_KEEP_DAYS` on the Settings page. |

## Media libraries

| Symptom | Check |
| --- | --- |
| The portal says Folder unavailable | The library's folder is missing or empty, as an unmounted disk reads. Mount it at the same path under `SOFA_MEDIA_DIR`; the scan changes nothing until it is back. |
| A library folder cannot be added | It must sit inside `SOFA_MEDIA_DIR` or the recordings folder, and a link must point inside it. Check the `/media` mount in `compose.yaml` and that the container's user can read and list it. |
| A Music album or artist has no year, genres, cover, picture or biography | The [MusicBrainz lookup](libraries.md#details-from-musicbrainz) keeps only a match with the album's own title and artist; tag the files with MusicBrainz Picard to pin it. The scan's job note counts what MusicBrainz matched and says when it throttled the pass, which resumes on the next scan. `SOFA_MUSICBRAINZ=false` turns the lookups off. |
| A movie or show has the wrong title or no details | Name it as [lay out the folders](libraries.md#lay-out-the-folders) shows, or pin the TMDB match with `{tmdb-ID}` in the folder name. Without a TMDB token, from `SOFA_TMDB_TOKEN` or the Library page, the details come only from the names and `.nfo` files; with it, the scan's job note says when TMDB refused the token or rate-limited the scan. |
| The app offers no multiview | The server offers it only while a GPU encoder works; the log's `multiview:` line at start says why it is off. Set up [hardware transcoding](libraries.md#hardware-transcoding), or set `SOFA_MULTIVIEW=on` to draw it at 720p in software if the CPU has cores to spare. |
| Library playback stutters or is slow to start | The file is being converted on the CPU. Give the container a GPU, as [hardware transcoding](libraries.md#hardware-transcoding) describes; the log's `vod encoder` line at start names the encoder in use. |
| `vod encoder unavailable` in the log at start | The GPU encoder failed its test frame, so files convert in software. Check the `/dev/dri` device and `group_add` for `vaapi` and `qsv`, or for `nvenc` the NVIDIA Container Toolkit and the `compute,video,utility` driver capabilities ([hardware transcoding](libraries.md#hardware-transcoding)). |

## Reverse proxy

| Symptom | Check |
| --- | --- |
| Playlist, guide or portal links use `http://` or the wrong host behind an HTTPS proxy | The proxy must pass the original `Host` header and send `X-Forwarded-Proto: https`. Caddy and Traefik do by default. With nginx, add `proxy_set_header Host $host;` and `proxy_set_header X-Forwarded-Proto $scheme;`. Without the header the session cookie is not marked `Secure` either. |
| Plex reaches the tuner through the proxy and gets `403` | Every request through the proxy comes from the proxy's address. Point Plex at the published port, not the proxy. See [reverse proxy](deploy.md#reverse-proxy). |

## FAQ

### Why are some channels missing?

The native providers leave out DRM channels and channels that need a sign-in, because they would not play through the server. M3U playlists are not filtered: a DRM channel there fails its probes, and with a lineup policy it stays off. A channel can also be off because none of its sources has played in a probe yet.

### Why does Plex not discover the server?

The server does not implement SSDP, the multicast discovery HDHomeRun tuners answer. Plex, Jellyfin and Emby accept a tuner's address entered by hand. Multicast does not leave a Docker bridge network or cross subnets, so those setups need the manual address with any tuner.

### Does it run on ARM?

Yes, on 64-bit ARM. The published image is built for `linux/amd64` and `linux/arm64`, and Docker pulls the one that matches the host. A local build targets the host it runs on.

### How many tuners can Plex use?

`SOFA_HDHR_TUNERS` sets the tuners of each HDHomeRun device (see [server settings](live-tv.md#server-settings)). Every stream from Plex, the app or a player takes one of the `SOFA_MAX_SESSIONS` sessions, so that cap covers all clients together. An HLS stream for Plex runs one FFmpeg process that copies the stream without transcoding. Raise `mem_limit` in `compose.yaml` with the session count.
