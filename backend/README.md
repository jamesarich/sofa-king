# Sofa King server

The Sofa King server is the heart of a self-hosted media center for a home network, and it is Sofa King busy. It keeps live TV, recordings and media libraries in one container, with no separate playlist tool, tuner proxy or recorder. The [Sofa King app](../app/README.md) plays all three on TVs, phones, desktops, iOS, Apple TV and the web.

## What it does

- **Live TV from native clients.** 21 free streaming (FAST) services, about twenty broadcasters' own free feeds and HDHomeRun tuners, with their guides ([providers](docs/providers.md)), plus M3U playlists and XMLTV guides, plain or gzip. The service clients run on your server, because hosted FAST playlists have been taken down after DMCA notices; only Samsung TV Plus and The Roku Channel take their channel lists from a published mirror.
- **One station per channel.** The same channel from several services becomes one station with the others as fallbacks. Channels whose names only roughly match are merged when their schedules agree, and kept apart otherwise.
- **A lineup that stays put.** A [lineup policy](docs/lineup-policy.md) file rebuilds the same lineup on any server, with a preview before any change. Groups, stable numbers, logos, guide mapping and failover order live in SQLite, and every number the server assigns is written back to the policy file, so a channel keeps its number through rebuilds and on a new server. A logo cache serves every client's logos from this server.
- **Failing sources step aside.** FFmpeg [probes sources](docs/source-health.md) on a schedule, quarantines the ones that fail, and playback fails over to the next source, from any service. HLS and MPEG-TS are relayed through the server.
- **Players and Plex.** M3U and XMLTV outputs, and an HDHomeRun emulation for Plex Live TV with an MPEG-TS remux. The server saves Plex's channel maps itself and can add the tuners Plex is missing; with extra ports (`SOFA_HDHR_EXTRA_PORTS`), a large lineup spreads over several tuner devices, 250 channels each under a lineup policy, the size Plex can save.
- **Virtual channels and multiview.** [Virtual channels](docs/virtual-channels.md) play your library on a schedule, with live slots and breaks you choose. [Multiview](docs/api.md#multiview) draws two to four channels into one picture on the server.
- **Recordings any media server reads.** [Recordings](docs/recordings.md) of one program, a time window or a whole series, named the way Plex, Jellyfin, Emby and Kodi scan them, with commercial breaks found by Comskip and cleanup by keep count, age and free space.
- **Libraries that play on anything.** [Media libraries](docs/libraries.md) of movies, shows, other videos and music from a read-only folder, scanned with their `.nfo` files, tags and artwork, with details from TMDB when a token is set and from MusicBrainz for music, household watch progress, and TV episodes' intros found by their repeated audio. A file a device can take plays as it is; anything else is copied or converted to HLS by FFmpeg as it plays, on the CPU or a GPU, with HDR tone-mapped for an SDR screen.
- **Easy to run.** An [admin portal](docs/admin.md) at `/admin` works from a phone browser, with or without JavaScript: it shows what needs attention, sets up the sources and a starter lineup, edits channels, merges, groups and provider priority, manages recordings and series rules, adds media libraries, pairs devices with a code and keeps database backups. A host can update itself from the published image, with a database backup and a rollback when the new image is unhealthy. The Docker image covers `linux/amd64` and `linux/arm64`, with an SBOM and build provenance.
- **Around the server.** A JSON API whose live TV, recording and library types the app shares ([API](docs/api.md)); the Sofa King [web app](docs/deploy.md#serve-the-web-app) at `/app/`, built from the same commit; [notifications](docs/notifications.md) to ntfy, Apprise or a webhook when a recording fails, a source breaks or an update is published; and an [MCP endpoint](docs/api.md#mcp) at `/api/v1/mcp` that lets an AI assistant check on the server ([setup](docs/operations.md#use-with-an-ai-assistant)).

## Clients

Only Plex and the Sofa King app have been tested. The others speak the same HDHomeRun or M3U and XMLTV interfaces and should work. Replace `SERVER_HOST` with the server's LAN address and `OUTPUT_TOKEN` with the output token, which the admin portal's Plex page reveals.

| Client | Status | Connect with |
| --- | --- | --- |
| Plex Live TV & DVR | Tested | HDHomeRun at `SERVER_HOST:9193`, guide `http://SERVER_HOST:9193/output/guide.xml?token=OUTPUT_TOKEN` ([Plex setup](docs/live-tv.md#plex-live-tv)) |
| [Sofa King app](../app/README.md) | Tested | Server `http://SERVER_HOST:9193` and the API token, or a [pairing code](docs/admin.md#devices); in a browser, the [web app](docs/deploy.md#serve-the-web-app) at `http://SERVER_HOST:9193/app/` |
| Jellyfin, Emby | Should work, untested | HDHomeRun tuner at `http://SERVER_HOST:9193`, or an M3U tuner with the playlist below; the XMLTV guide below |
| Channels DVR | Should work, untested | Custom channels source with the M3U playlist and XMLTV guide below |
| TiviMate, Kodi (IPTV Simple Client) | Should work, untested | The M3U playlist and XMLTV guide below |
| VLC | Should work, untested | Open the M3U playlist as a network stream; VLC shows no guide |

The playlist is `http://SERVER_HOST:9193/output/playlist.m3u?token=OUTPUT_TOKEN` and the guide `/output/guide.xml?token=OUTPUT_TOKEN`. [Third-party players](docs/live-tv.md#third-party-players) and [Plex Live TV](docs/live-tv.md#plex-live-tv) have the details, including the addresses the HDHomeRun routes admit.

## Quick start

You need Docker with Compose 2.24 or later on a Linux host, `amd64` or `arm64`. No checkout and no build: Compose runs the published image.

```sh
mkdir -p sofa-king/config sofa-king/recordings
cd sofa-king
curl -fsSLO https://raw.githubusercontent.com/jamesarich/sofa-king/main/backend/deploy/compose.yaml
docker compose up -d
```

Then open `http://SERVER_HOST:9193/admin`. [Getting started](docs/getting-started.md) signs in to the admin portal and walks to a playing channel. [Deploy with Docker](docs/deploy.md) covers the config folder, secrets, reverse proxies, the web app, your own build and automatic updates. To run without Docker, see [development](docs/development.md#run-without-docker).

## Documentation

The [documentation index](docs/README.md) lists every page. [Development](docs/development.md) has the build and test commands; the repository's [CONTRIBUTING.md](../CONTRIBUTING.md) covers conventions and CI. The [security page](docs/security.md) describes the deployment and the tokens; report a vulnerability as [SECURITY.md](../SECURITY.md) describes.

## Limitations

- Plex reaches the HDHomeRun emulation by address only. SSDP discovery is not implemented.
- The HDHomeRun remux of an HLS source carries one video stream and one audio stream.
- The native providers are unofficial clients of public service endpoints. They stop working when a service changes them.
- DRM channels are skipped by the native providers. M3U playlists are not filtered for DRM.
- [Series recordings](docs/recordings.md#limitations) need the guide; a channel without one records only as a time window.

## License

Licensed under the [GNU General Public License v3.0](../LICENSE).
