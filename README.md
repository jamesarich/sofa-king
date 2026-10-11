# Sofa King Backend

A Kotlin IPTV server for one household. It imports provider M3U playlists and independent XMLTV guides (plain or gzip), keeps a curated channel lineup in SQLite, serves a JSON API, an M3U/XMLTV output for third-party players and an HDHomeRun emulation for Plex Live TV, relays HLS and MPEG-TS streams, caches the lineup's channel logos so every output points at this server, and quarantines failing sources automatically. It replaces the Dispatcharr curation step: the app sees the lineup, not the raw feeds.

The [Sofa King Android app](https://github.com/jamesarich/sofa-king) currently uses M3U/XMLTV endpoints and does not connect to this API yet. This server has no web UI or DVR.

## Run

Requires JDK 21, FFmpeg, and HTTP(S) playlist and guide URLs. Run commands from the repository root.

1. Build the server:

   ```sh
   ./gradlew :server:installDist
   ```

2. Create `data/sources.json` with your providers and guides. `${NAME}` is replaced from the environment, so a tokenized path stays out of the file:

   ```sh
   mkdir -p data
   ```

   ```json
   {
     "providers": [
       { "id": "fastchannels", "playlistUrl": "http://192.168.1.3:5523/feeds/default/m3u" },
       { "id": "masqueradarr", "playlistUrl": "http://192.168.1.3:3000/${MASQ_PLAYLIST_PATH}.m3u" }
     ],
     "guides": [
       { "id": "gracenote", "url": "http://192.168.1.3:3000/DaddyLiveUSGracenote/epg/playlist.xml" },
       { "id": "plex", "url": "https://i.mjh.nz/Plex/us.xml.gz" }
     ]
   }
   ```

3. Start the server:

   ```sh
   MASQ_PLAYLIST_PATH='SECRET_PATH' SOFA_SOURCES=./data/sources.json \
     SOFA_RESTREAM_ORIGINS=192.168.1.3:3000 \
     server/build/install/server/bin/server
   ```

4. Check the imports:

   ```sh
   curl --fail http://127.0.0.1:8080/api/v1/providers
   curl --fail http://127.0.0.1:8080/api/v1/admin/guides
   ```

5. Build the lineup. A fresh server serves no channels until you curate. Either adopt a whole provider:

   ```sh
   curl --fail -X POST http://127.0.0.1:8080/api/v1/admin/lineup/adopt \
     -H 'Content-Type: application/json' --data '{"providerId":"fastchannels"}'
   ```

   or [migrate your Dispatcharr profile](docs/live-tv.md#migrate-from-dispatcharr). Then:

   ```sh
   curl --fail http://127.0.0.1:8080/api/v1/channels
   ```

The server listens on `127.0.0.1:8080`. Keep `data/` across restarts; it contains the database (lineup, guides, source health), the server identity and `logos/`, the cached channel logos ([logos](docs/api.md#logos)). Providers refresh every 15 minutes and guides every 6 hours by default.

## Documentation

- [Configuration, lineup and Dispatcharr migration](docs/live-tv.md)
- [Third-party players (TiviMate, VLC)](docs/live-tv.md#third-party-players)
- [Plex Live TV through the HDHomeRun emulation](docs/live-tv.md#plex-live-tv)
- [Deploy with Docker](docs/deploy.md)
- [HTTP API, admin API and Kotlin client](docs/api.md)
- [Source health and automatic recovery](docs/source-health.md)
- [Architecture](docs/architecture.md)
- [Build and test](docs/development.md)
- [Dependencies](docs/dependencies.md)

## Limitations

- Plex reaches the HDHomeRun emulation by address only; SSDP discovery is not implemented. Jellyfin and Channels DVR are not tested against it.
- The M3U/XMLTV output, the HDHomeRun routes and the FFmpeg remux are verified with FFmpeg and the Ktor client against local fixtures (a live HLS fixture remuxed to MPEG-TS, the process killed on hang-up), not with TiviMate, VLC or a Plex server. CEA-608 captions surviving the remux follows from stream copy and is not verified with a captioned source.
- An HDHomeRun stream of an HLS source carries one video and one audio stream, and FFmpeg reads the source directly, so a provider whose headers must stay on its origin is sent none.
- Only MPEG-TS sessions share an upstream connection; each HLS request fetches upstream itself.
- Guide programmes are kept only for channels the lineup maps, so a newly mapped channel has no guide until the refresh this triggers completes.
- Feeds with credential or custom headers are never probed, so their sources stay `UNVERIFIED`.
- Lineup curation is JSON over HTTP; there is no web UI.
- The restream-proxy 502 rule (`SOFA_RESTREAM_ORIGINS`), the MPEG-TS keepalive relay and the Dispatcharr export script are verified against local fixtures and the Dispatcharr source models, not against the household's Dispatcharr container, provider feeds or the Android client.
- A relay reconnect attempt already in progress finishes before the 30-second grace period is enforced, so a dead source can hold its sessions for up to one connect timeout longer.
- The probe's protocol detection and its persistence are verified against local fixtures, not against the Dispatcharr feed.
- The URL-keyed source identity rules and the Dispatcharr stream matcher are verified against sanitized fixtures and one read-only import of the FastChannels playlist, not against the masqueradarr playlist or the household's Dispatcharr export.
- The Docker image and compose stack are verified on a development host against a local fixture feed, not on OMV or with the Android client.
- The logo cache is verified against local fixtures (PNG, SVG, a mislabeled PNG, an HTML decoy, an oversized body, a failing refresh), not against the household's logo host, Dispatcharr's logo cache or provider CDNs. `/logos/*` is public, like the HDHomeRun routes.
