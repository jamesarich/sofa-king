# Getting started

This tutorial takes a Docker host from an empty folder to a channel playing in Plex, the Sofa King app or an M3U player. It uses three free services that need no account, and a small lineup policy. It takes about fifteen minutes, plus the time the server needs to verify the first channels.

Replace `SERVER_HOST` with the host's LAN address and `PLEX_SERVER_IP` with the address of your Plex server. The server answers on port 9193.

## What you need

- A Linux host, `amd64` or `arm64`, with Docker Engine and Docker Compose 2.24 or later.
- `curl`, `jq` and `openssl` for the shell steps.

## 1. Start the server

Make a folder for the server, with its `config` and `recordings` folders, download the Compose file into it and start it:

```sh
mkdir -p sofa-king/config sofa-king/recordings
cd sofa-king
curl -fsSLO https://raw.githubusercontent.com/jamesarich/sofa-king/main/backend/deploy/compose.yaml
docker compose up -d
```

Compose pulls the published image, `ghcr.io/jamesarich/sofa-king:main`, which needs no login. Create both folders before the first start: Docker creates a missing one as root, and the server, which runs as your user, then cannot write to it. The remaining steps run in this folder. Check that the server is up:

```sh
curl --fail http://SERVER_HOST:9193/readyz
```

It answers `ready`. If the container exits at once instead, `docker compose logs` says why; [troubleshooting](troubleshooting.md#the-container-does-not-start) lists the common causes. To build the image from a checkout of this repository instead, see [build](deploy.md#build).

## 2. Sign in

The first start generated an API token, an admin token and an output token and stored them in `config/secrets.json`. Print them, and keep them in shell variables for the later steps:

```sh
docker compose exec sofa-king-backend cat /config/secrets.json
SOFA_API_TOKEN=$(jq -r .apiToken config/secrets.json)
SOFA_ADMIN_TOKEN=$(jq -r .adminToken config/secrets.json)
```

Open `http://SERVER_HOST:9193/admin`, or `http://sofa-king.local:9193/admin` on a computer that resolves `.local` names, and sign in with the `adminToken`. The home page starts with the setup steps and lists what needs attention.

## 3. Choose services and build the lineup

On the home page, choose Choose services. The Sources page lists every [native service](providers.md) as a switch, with the ones that need no option already ticked. For this tutorial, untick everything but Whale TV+, Tubi and Pluto TV, three services that need no account. Leave Build the starter lineup too ticked, and choose Save and restart.

The confirmation names the three services and the starter lineup. The server writes `config/sources.json` and the [starter policy](starter-lineup.md) in `config/policy.json`, which takes every channel of the three services in its priority order, groups them with the shipped category map and numbers each group in its own block. Then it restarts, and the page returns to the portal in twenty seconds, still signed in.

The same by hand, if you prefer, with a smaller policy that takes every channel of the three services, Whale first, and puts whatever nothing else groups in Entertainment:

```sh
cat > config/sources.json <<'EOF'
{
  "providers": [
    { "id": "whale", "type": "native", "service": "whale" },
    { "id": "tubi", "type": "native", "service": "tubi" },
    { "id": "pluto", "type": "native", "service": "pluto" }
  ],
  "guides": []
}
EOF
cat > config/policy.json <<'EOF'
{
  "providers": [{ "id": "whale" }, { "id": "tubi" }, { "id": "pluto" }],
  "groups": { "default": "Entertainment" }
}
EOF
docker compose restart
```

Each native service brings its own guide, so `guides` stays empty. After a minute, the portal's Sources page lists each service with its channel count. The same counts come from the API:

```sh
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://SERVER_HOST:9193/api/v1/providers
```

The server builds one station per channel. When two services carry the same channel, the first one listed leads and the other becomes its fallback. A station comes on once one of its sources has played in a probe, so the Channels page fills over the next hour or more.

[Build a starter lineup](starter-lineup.md) describes what the starter policy holds and how to tune it.

## 4. Watch

Plex and M3U players reach the guide and the streams with the output token, `outputToken` in `config/secrets.json`. The portal's Plex page, under Players, shows the playlist and guide URLs with it once you choose Reveal the output token.

### Plex

The server emulates an HDHomeRun tuner, which admits no address until you allow one. On the portal's Plex page, under Tuner access, allow the Plex server's address. If you do not know the address the server sees, add the tuner in Plex first: the Plex page then lists the address it refused, with Allow.

Add the tuner at `SERVER_HOST:9193` and the guide at `http://SERVER_HOST:9193/output/guide.xml?token=OUTPUT_TOKEN` in Plex, with the output token, as steps 2 to 5 of [Plex Live TV](live-tv.md#plex-live-tv) describe. [Keep Plex in step](live-tv.md#keep-plex-in-step) lets the server update Plex's channel maps itself.

### The Sofa King app

Install the [Sofa King app](../../app/README.md) and give it the server address, `http://SERVER_HOST:9193`, and the API token. The portal's Settings page reveals the API token too, or pair the app with a code from the portal's [Devices page](admin.md#devices) instead of typing the token. The app reads the lineup and its programs, and plays through the server. To use the web app, serve it as [serve the web app](deploy.md#serve-the-web-app) describes.

### An M3U player

Add the playlist to the player, and the guide as its EPG or XMLTV source:

```text
http://SERVER_HOST:9193/output/playlist.m3u?token=OUTPUT_TOKEN
http://SERVER_HOST:9193/output/guide.xml?token=OUTPUT_TOKEN
```

Players that read the playlist header pick up the guide on their own. [Third-party players](live-tv.md#third-party-players) has the details.

## Next steps

Live TV is one third of the media center. The other two are Sofa King close:

- Add your movies and shows: [mount the media folder](libraries.md#mount-the-media-folder) and add a library.
- Record from the guide: [record a program](recordings.md#record-a-program), or keep the recordings on a disk of their own with [the recordings folder](deploy.md#the-recordings-folder).
- Add the other services and group the lineup: [build a starter lineup](starter-lineup.md).
- Back up the config folder: [operations](operations.md#backups).
- Keep the host up to date, or put the server behind a reverse proxy: [deploy with Docker](deploy.md).
- When something does not work: [troubleshooting](troubleshooting.md).
