# Sofa King Backend

Live TV, Sofa King good.

Sofa King Backend is a self-hosted IPTV server written in Kotlin. It imports M3U playlists and XMLTV guides, lists free streaming services through built-in native provider clients, and builds a channel lineup from them with a lineup policy. It serves that lineup to the Sofa King app over a JSON API, to M3U and XMLTV players, and to Plex through an HDHomeRun emulation.

The server relays HLS and MPEG-TS streams, caches channel logos so every output points at this server, and takes failing sources out of rotation automatically. The lineup policy keeps the lineup in shape: one station per channel with the other services' copies as fallbacks, groups, stable numbers, and stations turned off while nothing can play them. The server has no DVR.

## Features

- Imports M3U playlists and XMLTV guides, plain or gzip
- Native clients for 17 free streaming services, including their guides ([providers](docs/providers.md))
- A numbered lineup with groups, logos, failover order and guide mapping, stored in SQLite
- Source health with probes, quarantine and automatic failover between sources
- A [lineup policy](docs/lineup-policy.md) file that rebuilds the whole lineup the same way on any server, with a preview before any change
- HLS and MPEG-TS playback sessions with leases, relayed through the server
- M3U and XMLTV outputs for players, and an HDHomeRun emulation for Plex Live TV
- A logo cache served from this server
- A JSON API, with a Kotlin client module
- An [admin portal](docs/admin.md) at `/admin` that shows what needs attention and edits channels, merges, groups and provider priority

## Sofa King fast setup

With Docker and JDK 21, from the repository root:

```sh
./gradlew :server:installDist
mkdir -p deploy/config
cd deploy && docker compose up -d --build
docker compose exec sofa-king-backend cat /config/secrets.json
```

Open `http://SERVER_HOST:9193/admin` and sign in with the `adminToken` from that file. Everything the server keeps, including the generated tokens, is in `deploy/config`; drop a `sources.json` and a `policy.json` there to add channels. [Deploy](docs/deploy.md) covers the config folder, running as your own user, secrets, reverse proxies and a host that updates itself from the published image.

Without Docker you need JDK 21 and FFmpeg. Run the commands from the repository root:

1. Build the server:

   ```sh
   ./gradlew :server:installDist
   ```

2. Create `data/sources.json`. The server reads it from its data directory, `data`, at start. Native providers need no account. Inside any string, `${NAME}` is replaced from the environment, so a tokenized URL stays out of the file:

   ```sh
   mkdir -p data
   ```

   ```json
   {
     "providers": [
       { "id": "whale", "type": "native", "service": "whale" },
       { "id": "tubi", "type": "native", "service": "tubi" },
       { "id": "pluto", "type": "native", "service": "pluto" }
     ],
     "guides": []
   }
   ```

   [`deploy/sources.example.json`](deploy/sources.example.json) lists every native provider and an M3U provider example. The PBS and M3U entries need your values.

3. Start the server. It listens on `127.0.0.1:8080` by default:

   ```sh
   server/build/install/server/bin/server
   ```

   On loopback the API needs no token. To serve other machines, set `SOFA_HOST=0.0.0.0`, and either `SOFA_HDHR_ALLOW=PLEX_SERVER_IP` or `SOFA_HDHR_ENABLED=false`: on a LAN bind the server refuses to start without one of them. The first start then generates an API token and an admin token and keeps them in `data/secrets.json`; no token is logged. `SOFA_API_TOKEN` and `SOFA_ADMIN_TOKEN` set your own values instead.

4. Check the imports. On a LAN bind, `/api/v1/admin/*` takes only the admin token and refuses the API token. The examples below assume `SOFA_API_TOKEN` and `SOFA_ADMIN_TOKEN` hold the tokens, for example from `jq -r .apiToken data/secrets.json`. Leave out the header when no token is set:

   ```sh
   curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://127.0.0.1:8080/api/v1/providers
   curl --fail -H "Authorization: Bearer $SOFA_ADMIN_TOKEN" http://127.0.0.1:8080/api/v1/admin/guides
   ```

5. Build the lineup. A new server serves no channels until a [lineup policy](docs/lineup-policy.md) builds them. Write a policy that takes every channel of the three services, Whale first, to `data/policy.json`:

   ```sh
   echo '{"providers": [{"id": "whale"}, {"id": "tubi"}, {"id": "pluto"}], "groups": {"default": "Entertainment"}}' > data/policy.json
   ```

   Restart the server. It logs which sources and policy files it picked, and stations come on as the probes verify one of their sources:

   ```sh
   server/build/install/server/bin/server
   curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://127.0.0.1:8080/api/v1/channels
   ```

   For a full lineup of every free service, grouped, with fallbacks across services and your local channels, follow [build a starter lineup](docs/starter-lineup.md).

Keep `data/` across restarts. It holds the database, the logo cache, the generated tokens and the two files you wrote. `SOFA_SOURCES` and `SOFA_POLICY` point at files elsewhere. [Operations](docs/operations.md) covers backups, upgrades and token rotation.

## Configuration

Every environment variable is listed in one table in [live TV configuration](docs/live-tv.md#server-settings). The source file, provider and guide fields, and the native provider options are in [source settings](docs/live-tv.md#source-settings) and [native providers](docs/providers.md#configuration).

## Clients

- **Sofa King Android TV app**: the [app](https://github.com/jamesarich/sofa-king) reads the lineup, guide and playback sessions through `/api/v1` and `/media`.
- **Plex**: through the [HDHomeRun emulation](docs/live-tv.md#plex-live-tv).
- **Any M3U/XMLTV player**: through the [player outputs](docs/live-tv.md#third-party-players).

## Documentation

The [documentation index](docs/README.md) lists every guide. The most used are:

- [Build a starter lineup](docs/starter-lineup.md)
- [Live TV: configuration, lineup and sources](docs/live-tv.md)
- [Native providers](docs/providers.md)
- [Lineup policy](docs/lineup-policy.md)
- [HTTP API](docs/api.md)
- [Deploy with Docker](docs/deploy.md)
- [Operations](docs/operations.md)

## Contributing

Build and test with `./gradlew test`. [CONTRIBUTING.md](CONTRIBUTING.md) covers the commit conventions, the test scripts and how to add a native provider.

## Security

[SECURITY.md](SECURITY.md) describes which token opens which routes, the outbound address policy, and how to report a vulnerability.

## Limitations

- Plex reaches the HDHomeRun emulation by address only. SSDP discovery is not implemented.
- The HDHomeRun remux of an HLS source carries one video stream and one audio stream.
- The native providers are unofficial clients of public service endpoints. They stop working when a service changes them.
- DRM channels are skipped by the native providers. M3U playlists are not filtered for DRM.

## License

Licensed under the GNU General Public License v3.0.
