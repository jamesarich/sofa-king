# Sofa King Backend

Live TV, Sofa King good.

Sofa King Backend is a self-hosted IPTV server written in Kotlin. It imports M3U playlists and XMLTV guides, lists free streaming services through built-in native provider clients, and keeps a curated channel lineup in SQLite. It serves that lineup to the Sofa King app over a JSON API, to M3U and XMLTV players, and to Plex through an HDHomeRun emulation.

The server relays HLS and MPEG-TS streams, caches channel logos so every output points at this server, and takes failing sources out of rotation automatically. Curation rules keep the lineup in shape (groups, names, logos, equivalent sources across providers, channels disabled when nothing can play them). Rules never override a value an admin set. The server has no DVR.

## Features

- Imports M3U playlists and XMLTV guides, plain or gzip
- Native clients for 17 free streaming services, including their guides ([providers](docs/providers.md))
- A numbered lineup with groups, logos, failover order and guide mapping, stored in SQLite
- Source health with probes, quarantine and automatic failover between sources
- A [lineup policy](docs/lineup-policy.md) file that rebuilds the whole lineup the same way on any server, or curation rules that preview their changes before they apply
- HLS and MPEG-TS playback sessions with leases, relayed through the server
- M3U and XMLTV outputs for players, and an HDHomeRun emulation for Plex Live TV
- A logo cache served from this server
- A JSON API, with a Kotlin client module
- An [admin portal](docs/admin.md) at `/admin` for the lineup, station merges and Plex setup

## Sofa King fast setup

You need JDK 21 and FFmpeg. Run the commands from the repository root. For Docker, see [deploy](docs/deploy.md).

1. Build the server:

   ```sh
   ./gradlew :server:installDist
   ```

2. Create `data/sources.json`. Native providers need no account. Inside any string, `${NAME}` is replaced from the environment, so a tokenized URL stays out of the file:

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
   SOFA_SOURCES=./data/sources.json server/build/install/server/bin/server
   ```

   To serve other machines, set `SOFA_HOST=0.0.0.0` and `SOFA_API_TOKEN` to a value of at least 32 characters (`openssl rand -hex 32`). The server refuses a non-loopback bind without it.

4. Check the imports. The examples below assume `TOKEN` holds `SOFA_API_TOKEN`. Leave out the header when no token is set:

   ```sh
   curl --fail -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/providers
   curl --fail -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/admin/guides
   ```

5. Build the lineup. A new server serves no channels until you add some. Adopt a whole provider:

   ```sh
   curl --fail -X POST -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/admin/lineup/adopt \
     -H 'Content-Type: application/json' --data '{"providerId":"whale"}'
   curl --fail -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/channels
   ```

   For a full lineup of every free service, grouped, with fallbacks across services and your local channels, follow [build a starter lineup](docs/starter-lineup.md).

6. Curate by rule. `GET /api/v1/admin/curation/preview` shows what automation would change. Automation stays off until `settings.enabled` is set, and it never changes a field an admin set. See [curation](docs/curation.md).

Keep `data/` across restarts. It holds the database and the logo cache. [Operations](docs/operations.md) covers backups, upgrades and token rotation.

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
- [Curation](docs/curation.md)
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
