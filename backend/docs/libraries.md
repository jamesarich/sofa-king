# Media libraries

A media library is a folder of movies, TV shows or other videos that the server reads and serves to clients. Libraries live under one media folder, `SOFA_MEDIA_DIR`, which the server never writes to. The admin portal's [Library page](admin.md#library) adds them, and the [admin API](api.md#admin-api) does the same.

## Mount the media folder

`SOFA_MEDIA_DIR` defaults to `media` inside `SOFA_DATA_DIR`. In Docker, mount your media read only and point `SOFA_MEDIA_DIR` at it:

```yaml
    environment:
      SOFA_MEDIA_DIR: /media
    volumes:
      - ./config:/config
      - /srv/media:/media:ro
```

Put each library in its own folder inside it, for example `/media/movies`, `/media/tv` and `/media/videos`. If you already keep a `data/media` tree for other tools, mount that folder at `/media`. The server must be able to read every file: with `user: "UID:GID"`, that user needs read access to the files and to list each folder.

A library's folder must sit inside `SOFA_MEDIA_DIR`. Links are not followed during a scan, and a library folder that is itself a link must point inside the media folder.

## Lay out the folders

The scan reads the same names Plex, Jellyfin and Kodi read, so a collection already organized for one of them needs no change.

| Library | Layout |
| --- | --- |
| Movies | `Title (Year)/Title (Year).mkv`, or a loose `Title (Year).mkv`. Tags after the year are ignored: `Alien (1979) [1080p]`, `Alien (1979) {tmdb-348}`, and dotted release names such as `Alien.1979.1080p.BluRay.mkv` |
| TV shows | `Show (Year)/Season 01/Show - S01E02 - Title.mkv`. `S01E02E03` and `1x02` work too, `Specials` (or `Season 00`) is season 0, and a date-based show reads `Show/Season 2026/Show - 2026-10-17 - Title.ts` |
| Other videos | Any layout; each file is one video titled by its name |

Each show is one top folder of a TV library. An episode's season comes from its `.nfo`, then its season folder, then its file name, then its air date's year.

The scan skips hidden files and folders (a name starting with `.`), extras folders (`Extras`, `Featurettes`, `Behind The Scenes`, `Deleted Scenes`, `Interviews`, `Scenes`, `Shorts`, `Trailers`, `Samples`, `Other`), files ending in `-trailer`, `-sample` and the like, and NAS folders such as `@eaDir` and `#recycle`. It reads these containers: `mkv`, `mp4`, `m4v`, `mov`, `avi`, `ts`, `m2ts`, `mts`, `webm`, `wmv`, `mpg`, `mpeg`, `flv`, `ogv` and `3gp`.

## Details and artwork

A Kodi `.nfo` beside a file replaces what its name says: `Title (Year).nfo` or `movie.nfo` for a movie, `tvshow.nfo` in a show's folder, and `Show - S01E02.nfo` for an episode. The scan reads the title, sort title, year, plot, runtime, season and episode, air date, genres, studio, rating, content rating, cast and directors. An `.nfo` that is not XML, such as one holding only a link, is ignored.

Artwork is read from the files beside the video, as `.jpg`, `.jpeg`, `.png` or `.webp`:

| Item | Poster | Backdrop |
| --- | --- | --- |
| Movie | `Title (Year)-poster`, `poster`, `folder`, `cover` | `Title (Year)-fanart`, `fanart`, `backdrop` |
| Show | `poster`, `folder`, `cover` in its folder | `fanart`, `backdrop` |
| Season | `season01-poster` in the show's folder, or `poster`, `folder` in the season's | |
| Episode or video | `Name-thumb`, or an image with the video's name | |

Without a TMDB token the server looks nothing up online.

## Details from TMDB

With `SOFA_TMDB_TOKEN` set to a [TMDB](https://www.themoviedb.org/settings/api) API read access token (or a v3 API key), each scan of a Movies or TV shows library looks up what the files do not say: a movie's or show's overview, genres, rating, cast and artwork, and each season's and episode's title, air date, overview and still. Other videos libraries are never looked up. `SOFA_TMDB_LANGUAGE` (default `en-US`) picks the language, and its region picks the content rating.

A title is matched by its name and year. A `{tmdb-348}` or `[tmdbid-348]` tag in the folder or file name, or a TMDB id in the `.nfo`, pins the match instead. A match is kept for 30 days and a title TMDB does not know is tried again after 7; a new episode of a known show fetches only its season. A `.nfo` beside the file wins over TMDB's text, and local artwork wins over TMDB's images. A refused token or TMDB's rate limit ends the lookups for that scan, and the scan's job note says why; the next scan carries on.

This product uses the TMDB API but is not endorsed or certified by TMDB.

## Scans

A library is scanned when it is added, every six hours, and when Scan now is pressed in the portal or `POST /api/v1/admin/libraries/{id}/scan` is called. The Jobs page lists the scan as Library scan. FFprobe reads each new or changed file's container, length and video, audio and subtitle streams; an unchanged file (same size and change time) keeps what the last scan read.

Every file keeps one id for as long as its path is the same, even across a removed and re-added library, so watch progress survives a rescan. A file that disappears is marked missing and is removed after seven days unless it comes back. A library folder that is gone, or empty while the library held files, reads as an unmounted disk: the scan changes nothing, and the portal shows Folder unavailable until the folder is back.

## Browse and watch

Clients read the libraries through the [library API](api.md#media-libraries): a home with Continue watching and Recently added for each library, each library's movies, shows or videos, a show's seasons and episodes, and search. Watch progress is the household's: an episode finished on the TV is watched on the phone too, and a show's next episode is the one after the last finished. A client plays a file through a [library playback session](api.md#play-library-files): as it is when the player takes its format, otherwise remuxed or converted to HLS by FFmpeg. The app's Library tab, on the TV, phones, desktops, the web and the Apple TV, is the client; see the [app's usage](../../app/docs/usage.md#library).

Copied HEVC is written as fragmented MP4 tagged `hvc1`, which every player takes, and an HEVC file in MP4 tagged `hev1` is remuxed that way instead of played as it is, since AVPlayer refuses that tag. An HDR file converted for a player that cannot show HDR is tone-mapped to SDR. Subtitles come from inside the file and from subtitle files beside it, named for the video with an optional language and `forced` or `sdh`, such as `Movie (2020).en.srt` or `Episode.de.forced.sup`. Text subtitles (SubRip, ASS, MP4 text, WebVTT) are served as WebVTT, which the apps draw over the picture. Picture subtitles (PGS from Blu-ray, VobSub from DVD) have no text, so the server draws the chosen one into the picture as it converts the file, which costs a transcode.

## Hardware transcoding

A file the player cannot take is converted to H.264 with libx264 on the CPU. A GPU encoder takes most of that work off it: set `SOFA_HWACCEL` to `vaapi` (Intel or AMD), `qsv` (Intel Quick Sync) or `nvenc` (NVIDIA), and give the container the GPU. Decoding, scaling and tone mapping stay on the CPU, so any file still converts. On start the server encodes one test frame; when that fails it logs `vod encoder unavailable` and converts in software.

For `vaapi` and `qsv`, pass the render node and the host group that owns it (`stat -c %g /dev/dri/renderD128` prints its id) in `compose.yaml`; `SOFA_HWACCEL_DEVICE` picks another node:

```yaml
    devices:
      - /dev/dri:/dev/dri
    group_add:
      - "RENDER_GID"
```

The image carries Mesa's VA-API driver for AMD and, on amd64, Intel's media driver and Quick Sync runtime. For `nvenc`, install the NVIDIA Container Toolkit on the host and give the service the GPU:

```yaml
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu, video]
```
