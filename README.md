# Sofa King Backend

A Kotlin IPTV server that imports M3U playlists and XMLTV guides, serves a JSON API, and relays HLS and MPEG-TS streams. It manages playback sessions and automatically quarantines failing channel sources.

The [Sofa King Android app](https://github.com/jamesarich/sofa-king) currently uses M3U/XMLTV endpoints and does not connect to this API yet. This server has no web UI or DVR.

## Run

Requires JDK 21, FFmpeg, and HTTP(S) playlist URLs. Run commands from the repository root.

1. Build the server:

   ```sh
   ./gradlew :server:installDist
   ```

2. Create `data/sources.json`. Replace the example URLs with your provider's playlist and guide URLs:

   ```sh
   mkdir -p data
   ```

   ```json
   [
     {
       "id": "provider",
       "playlistUrl": "https://provider.example/channels.m3u",
       "guideUrl": "https://provider.example/guide.xml"
     }
   ]
   ```

3. Start the server:

   ```sh
   SOFA_SOURCES=./data/sources.json \
     server/build/install/server/bin/server
   ```

4. Check the import:

   ```sh
   curl --fail http://127.0.0.1:8080/api/v1/providers
   curl --fail http://127.0.0.1:8080/api/v1/channels
   ```

The server listens on `127.0.0.1:8080`. Keep `data/` across restarts; it contains the database and server identity. Sources refresh every 15 minutes by default.

## Documentation

- [Configuration and troubleshooting](docs/live-tv.md)
- [Deploy with Docker](docs/deploy.md)
- [HTTP API and Kotlin client](docs/api.md)
- [Source health and automatic recovery](docs/source-health.md)
- [Architecture](docs/architecture.md)
- [Build and test](docs/development.md)
- [Dependencies](docs/dependencies.md)

## Limitations

- Only MPEG-TS sessions share an upstream connection; each HLS request fetches upstream itself.
- Feeds with credential or custom headers are never probed, so their sources stay `UNVERIFIED`.
- The restream-proxy 502 rule (`SOFA_RESTREAM_ORIGINS`) and the MPEG-TS keepalive relay are verified against local fixtures, not against a provider feed or the Android client.
- A relay reconnect attempt already in progress finishes before the 30-second grace period is enforced, so a dead source can hold its sessions for up to one connect timeout longer.
- The probe's protocol detection and its persistence are verified against local fixtures, not against the Dispatcharr feed.
- The Docker image and compose stack are verified on a development host against a local fixture feed, not on OMV or with the Android client.
