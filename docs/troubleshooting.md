# Troubleshooting and FAQ

Start with the log. With Docker, `docker compose logs --tail 200 sofa-king-backend` in `deploy` shows it. Every response carries an `X-Request-Id` header, and the log lines for that request end with the same `requestId`, so a failing request is easy to find. Set `SOFA_LOG_LEVEL=debug` in `.env` and run `docker compose up -d` for more detail. [Logs](operations.md#logs) describes the format.

The examples use the Docker port, `SERVER_HOST:9193`, and the tokens in `$SOFA_API_TOKEN` and `$SOFA_ADMIN_TOKEN`. Outside Docker the port is 8080.

## The container does not start

| Symptom | Check |
| --- | --- |
| `docker compose up` says `port is already allocated` or `address already in use` | Another service holds port 9193. Change the host side of the port line in `compose.yaml`, for example `"9194:8080"`, and use that port everywhere these pages say 9193. With [Plex sync](live-tv.md#keep-plex-in-step) adding devices, set `SOFA_HDHR_PUBLIC_PORTS` to the new port too. |
| `docker pull` or the updater says `denied` or `unauthorized` | The image package is private. Log Docker in to `ghcr.io` with a personal access token (classic) that has `read:packages`, from an account with access to the package, as [the published image](deploy.md#the-published-image) describes. The updater runs as root, so log in with `sudo`. Without access, build the image from the repository instead. |
| The log says `/config is not writable by the user the server runs as` | `config` belongs to another user. Set `UID` and `GID` in `.env` to its owner, or `chown` it to them. Docker creates a missing bind-mount folder as root, so create it before the first start. See [run as your own user](deploy.md#run-as-your-own-user). |
| The log says `Non-loopback binding with SOFA_HDHR_ENABLED requires SOFA_HDHR_ALLOW` | The HDHomeRun emulation is on with no allow-list. Set `SOFA_HDHR_ALLOW` to the Plex server's address, or `SOFA_HDHR_ENABLED=false` when Plex is not used. |
| The log says `Not an IP address or CIDR block` | `SOFA_HDHR_ALLOW` holds a placeholder or a hostname. List IP addresses or CIDR blocks. |
| The log says `must contain at least 32 characters` | A token set in `.env` or its file is too short. The output token needs 32 letters, digits, hyphens or underscores. Leave the API and admin tokens unset to have them generated. |
| The log says `Set SOFA_API_TOKEN or SOFA_API_TOKEN_FILE, not both` | Remove one of the two from `.env`. The same holds for each `_FILE` variable. |
| The log says `/data/backend.sqlite exists but SOFA_DATA_DIR (/config) holds no database` | Move the database as [move from a /data volume](deploy.md#move-from-a-data-volume) describes. |
| The log says `Source configuration references unset variable` | `config/sources.json` uses `${NAME}` for a variable that `.env` does not set. |
| The log says `Unsupported database schema` | The database was written by a newer server. Run that server, or restore a backup taken before the upgrade, as [upgrade and rollback](operations.md#upgrade-and-rollback) describes. |
| The log names `SOFA_PLEX_URL`, `SOFA_PLEX_TOKEN` or `SOFA_PLEX_TUNER_BASE` | `SOFA_PLEX_URL` takes `http(s)://HOST[:PORT]` and `SOFA_PLEX_TUNER_BASE` takes `http(s)://HOST`, neither with a path, query or credentials. The token takes no spaces. The URL and the token are set together. |

## Signing in and tokens

| Symptom | Check |
| --- | --- |
| The admin token is not in the log | No token is logged. Read it with `docker compose exec sofa-king-backend cat /config/secrets.json`, or sign in and reveal the API token in Settings. |
| `401 AUTH_REQUIRED` | Send the token in the `Authorization: Bearer` header. `/api/v1/admin/*` takes only the admin token whenever one is set, which a LAN bind always has. |
| The sign-in page answers `429 Too Many Requests` | More than 10 sign-ins came from one address within a minute. Wait a minute. Behind a reverse proxy every sign-in shares the proxy's address and its budget. |
| `413 REQUEST_TOO_LARGE` | The body is over 64 KiB, or over 4 MiB for a policy write. The server answers this before it checks the token. For a policy over 4 MiB, edit `config/policy.json` and restart instead. |

## Channels and sources

| Symptom | Check |
| --- | --- |
| `/api/v1/channels` is empty | The lineup has not been built, or every channel is off. Add a [lineup policy](lineup-policy.md). With `health.requireVerified`, stations come on as the probes verify a source of each, which takes a while for a large lineup. `GET /api/v1/admin/lineup` shows every entry. |
| No candidates | Add `sources.json` to the config folder or set `SOFA_SOURCES`, then check `/api/v1/providers` for `IMPORT_FAILED` and the playlist URL and format. |
| A provider shows `IMPORT_FAILED` for a feed on the Docker host | Use `host.docker.internal` or the LAN address. Loopback is refused. |
| An import stays unchanged | A failed or empty import keeps the previous snapshot. Check the provider status and `allowEmpty`. |
| Sources stay `UNVERIFIED` or never qualify | Check `docker compose logs` for probe outcomes. Outside Docker, check that `SOFA_FFMPEG` names a working FFmpeg. Check the provider's `probe` setting too, and whether the feed sends credential or custom headers, which are never probed. |
| A source shows `WITHDRAWN` | The provider no longer lists that stream. The entry keeps the reference. Replace the source when the provider lists a new URL. |
| A channel vanished from the lineup | `GET /api/v1/admin/lineup/ENTRY_ID`: `autoDisabledAt` set means the lineup policy turned it off for want of an eligible source. `GET /api/v1/admin/policy/changes` has the change and `GET /api/v1/admin/stations/KEY` the station's candidates. |
| `503 SOURCE_UNAVAILABLE` | No source of the channel is selectable now. `GET /api/v1/admin/lineup/ENTRY_ID` shows each source's state, and `/api/v1/sources` shows quarantine, probation or a manual disable. |
| Extensionless HLS answers `410 STREAM_CHANGED` once | The source has not been probed yet. The first fetch detects HLS, stores it and starts a new epoch, so fetch the session again. Set `defaultProtocol` to `hls`, or `stream-type="hls"` on the entry, so unprobed sources start right. |
| `429 CAPACITY_EXHAUSTED` | Every playback slot holds a watch session. Release unused sessions or wait for their 90-second leases to expire. Previews and idle player sessions give way on their own. Raise `SOFA_MAX_SESSIONS` if the host can take it. |

## Guide

| Symptom | Check |
| --- | --- |
| Channels show only their own name, hour by hour | Those are placeholder programs: the channel has no guide data in the window. The portal's Channels page filters channels whose guide is missing or ended. `GET /api/v1/admin/guides` shows `IMPORT_FAILED` for a guide whose fetch or parse failed. |
| A channel has no guide | Check the entry's `guide` mapping against `GET /api/v1/admin/guides/GUIDE_ID/channels`. A mapping changed moments ago is filled on the next guide fetch. |
| Plex has no guide at all | The guide URL in Plex needs the output token: `http://SERVER_HOST:9193/output/guide.xml?token=OUTPUT_TOKEN`. Open it in a browser to check it answers. Plex fetches the guide on its own schedule; refresh it from the DVR settings in Plex. |

## Players and logos

| Symptom | Check |
| --- | --- |
| A player gets `401` on the playlist URL | `SOFA_OUTPUT_TOKEN` is unset or the `token` query differs. The API token opens `/output/*` only as a bearer header. |
| A player plays a channel as the wrong type after a probe | The stream suffix follows the stored protocol. Refresh the playlist in the player. |
| A channel shows no logo | `curl -i http://SERVER_HOST:9193/logos/KEY` with the key from the playlist. `404` means the logo source was refused or failed: a loopback or link-local address, a non-image content type, a body over 2 MiB, or an error. The log has `logo fetch failed key=KEY` with the reason, and the source is tried again an hour later. `GET /api/v1/admin/lineup/ENTRY_ID` shows the logo's source URL. |

## Plex

| Symptom | Check |
| --- | --- |
| Plex does not find the tuner | Plex looks for tuners by SSDP, which the server does not answer. Enter `SERVER_HOST:9193` by hand. From the Plex host, `curl http://SERVER_HOST:9193/discover.json` answers `403` when `SOFA_HDHR_ALLOW` leaves Plex out, and `404` when the emulation is off. With the Compose file it is off until `.env` sets `SOFA_HDHR_ENABLED=true`. |
| `403` while Plex runs in Docker on the same host | A Plex container on a Docker bridge network reaches the server from a Docker address, not the host's LAN address. Docker's default address pools lie in `172.16.0.0/12`, so `SOFA_HDHR_ALLOW=PLEX_SERVER_IP,172.16.0.0/12` admits it, along with every other container on the host. Running Plex with `network_mode: host` avoids the bridge. |
| Plex is on another VLAN or subnet | Discovery never crosses subnets, so enter the address by hand. The firewall between the networks must allow TCP to port 9193 and to every port in `SOFA_HDHR_EXTRA_PORTS`. `SOFA_HDHR_ALLOW` must name Plex's address as the server sees it, which is the router's address when the router translates addresses between the networks. |
| Plex says all tuners are in use | `SOFA_HDHR_TUNERS` streams are open, every `SOFA_MAX_SESSIONS` slot is taken, or the channel has no eligible source. Check `GET /api/v1/admin/lineup/ENTRY_ID`. |
| A channel plays for a moment and stops | FFmpeg ended the remux. The log has `remux ended exit=` with FFmpeg's last lines, URLs removed. |
| "There was a problem saving channel mappings" | One device carries more channels than Plex saves at once, about 250. Split the lineup with `SOFA_HDHR_EXTRA_PORTS`, as [several devices](live-tv.md#several-devices-for-a-large-lineup) describes. |
| Plex channels lag the lineup | Check `lastError` and `findings` in `GET /api/v1/admin/plex`. `answered 401` means `SOFA_PLEX_TOKEN` is wrong, and a device in `missingDevices` is in no DVR. `POST /api/v1/admin/plex/sync` runs a sync now. Without Plex sync, run "Scan channels" for the DVR in Plex. |

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

`SOFA_HDHR_TUNERS` sets the tuners of each HDHomeRun device, 2 by default. It can be at most `SOFA_MAX_SESSIONS`, which is 4 by default and at most 16. Every stream from Plex, the app or a player takes one session, so the sessions cap all clients together. An HLS stream for Plex runs one FFmpeg process that copies the stream without transcoding. Raise `mem_limit` in `compose.yaml` with the session count.
