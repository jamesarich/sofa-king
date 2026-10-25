# Use with Sonarr, Radarr and Bazarr

Sonarr and Radarr download and rename the files, Bazarr adds their subtitles, and the server reads the result. The server reads the folders as these tools write them, and a hook lets Sonarr and Radarr tell it at once when a file changes, so a new episode shows in the app within a minute instead of at the next six-hourly scan.

## Lay out the media

Keep the media in one tree that every container mounts, the layout the [TRaSH guides](https://trash-guides.info/File-and-Folder-Structure/) recommend:

```text
/data/media/movies/Alien (1979)/Alien (1979) {imdb-tt0078748} [Bluray-1080p].mkv
/data/media/tv/Show (2020) {tvdb-349232}/Season 01/Show (2020) - S01E02 - Second [WEBDL-1080p].mkv
```

Mount `/data/media` read only at `/media` in the server's container (`SOFA_MEDIA_DIR`, see [mount the media folder](libraries.md#mount-the-media-folder)) and add a Movies library on `/media/movies` and a TV shows library on `/media/tv`. The names Sonarr and Radarr write by default and the TRaSH naming schemes both read: the quality and release group after the title are dropped, and a `{tmdb-…}`, `{tvdb-…}` or `{imdb-…}` tag pins the [TMDB match](libraries.md#details-from-tmdb).

Sonarr and Radarr see the tree as `/data/media`, and the server as `/media`. Tell the server how they name it, in its environment:

```yaml
    environment:
      SOFA_LIBRARY_PATH_MAP: /data/media=/media
```

With the same mount path in every container, leave it unset.

## Make a hook token

On the portal's Settings page, under Access tokens, make a token with a label such as `Sonarr` and Hook as what it opens. Copy the token; the page shows it once. A hook token opens the hooks and nothing else, so one leaked from Sonarr's settings cannot read or change anything. Make one per program, so each can be revoked on its own.

## Connect Sonarr and Radarr

Either connection works; use one per program.

The Webhook connection:

1. In Sonarr or Radarr, open Settings, then Connect, and add a Webhook.
2. Name it `Sofa King` and tick the import, upgrade, rename and delete triggers. Grab and the health triggers queue nothing, so leave them off.
3. Set the URL to `http://SERVER_HOST:9193/api/v1/hooks/servarr` and the method to POST.
4. Put any username and the hook token as the password.
5. Press Test, which should succeed, then Save.

The Emby / Jellyfin connection, for a setup that already uses it:

1. In Settings, then Connect, add Emby / Jellyfin.
2. Set the host to the server's address, the port to `9193`, and the API key to the hook token.
3. Turn on Update Library. Send Notifications does nothing here; leave it off.
4. Press Test, then Save.

Sonarr's and Radarr's Map Paths From and To, under the connection's advanced settings, do what `SOFA_LIBRARY_PATH_MAP` does for this connection only.

Each event queues a scan of the library holding the series or movie folder; a library already waiting for a scan is scanned once. A hook for a folder no library holds, and the events that change no file (Grab, Health and the like), queue nothing. The Jobs page shows each scan as Library scan. `POST /api/v1/admin/libraries/scan` does the same from a script, with the admin token; the [API](api.md#hooks) has both.

## Bazarr

Bazarr writes each subtitle file beside its video, named for it with the language, such as `Show (2020) - S01E02 - Second [WEBDL-1080p].en.srt`. The server reads the subtitle files beside a video each time its page opens, so a subtitle Bazarr adds shows at once, with no hook and no scan; [the API](api.md#play-library-files) lists the formats it reads.
