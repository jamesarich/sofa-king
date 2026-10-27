# Media libraries

A media library is a folder of movies, TV shows or other videos that the server reads and serves to clients. Libraries live under one media folder, `SOFA_MEDIA_DIR`, which the server never writes to. The admin portal's [Library page](admin.md#library) adds them, and the [admin API](api.md#admin-api) does the same.

## Mount the media folder

`SOFA_MEDIA_DIR` defaults to `media` inside `SOFA_DATA_DIR`; `deploy/compose.yaml` sets it to `/media`. In Docker, mount your media read only at `/media`, as the commented line in the Compose file shows:

```yaml
    environment:
      SOFA_MEDIA_DIR: /media
    volumes:
      - ./config:/config
      - /srv/media:/media:ro
```

Put each library in its own folder inside it, for example `/media/Movies`, `/media/TV` and `/media/Videos`. The Library page offers a folder right under the media folder, or under the recordings folder, whose name says what it holds (`Movies` or `Films`, `TV`, `TV Shows`, `Shows` or `Series`, `Videos` or `Home Videos`) as a library one click away. If you already keep a `data/media` tree for other tools, mount that folder at `/media`. The server must be able to read every file: with `user: "UID:GID"`, that user needs read access to the files and to list each folder.

A library's folder must sit inside `SOFA_MEDIA_DIR`, or inside the DVR's `SOFA_RECORDINGS_DIR`: a TV shows library on its `TV` folder and a Movies library on its `Movies` folder put recordings in the Library tab beside the rest, with posters and TMDB details. A recording being made is in the hidden `.inprogress` folder, which scans skip, so it joins the library at the next scan after it ends; a recording's watch progress in the Library tab is separate from the one in the Recordings tab. Links are not followed during a scan, and a library folder that is itself a link must point inside the folder it is under.

## Lay out the folders

The scan reads the same names Plex, Jellyfin and Kodi read, so a collection already organized for one of them needs no change.

| Library | Layout |
| --- | --- |
| Movies | `Title (Year)/Title (Year).mkv`, or a loose `Title (Year).mkv`. Tags after the year are ignored: `Alien (1979) [1080p]`, `Alien (1979) {tmdb-348}`, `Alien (1979) {imdb-tt0078748}`, and dotted release names such as `Alien.1979.1080p.BluRay.mkv`. A collection's order number before the title, `01 - Alien (1979)` or `3 - Alien (1979)`, is dropped too |
| TV shows | `Show (Year)/Season 01/Show - S01E02 - Title.mkv`. `S01E02E03` and `1x02` work too, `Specials` (or `Season 00`) is season 0, and a date-based show reads `Show/Season 2026/Show - 2026-10-17 - Title.ts`. The quality and release group Sonarr appends are dropped from the title: `Title [WEBDL-1080p][x264]-GROUP` and `Title WEBDL-1080p` read as Title |
| Other videos | Any layout; each file is one video titled by its name |
| Music | Any layout of tagged files; untagged ones as `Artist/Album/01 - Title.flac` or `Artist - Album - 01 - Title.flac`, with a disc's tracks in a folder of the album's such as `CD 1`, `Disc 2` or `12" Vinyl 01` |

Each show is one top folder of a TV library. An episode's season comes from its `.nfo`, then its season folder, then its file name, then its air date's year.

The scan skips hidden files and folders (a name starting with `.`), samples (a `Sample` or `Samples` folder, or a name ending in `-sample`), and NAS folders such as `@eaDir` and `#recycle`. Extras are listed on the page of the movie or show they belong to, not in its library's grid, search or home rows: the files in a movie's folder or a show's folder (a season's included) under `Trailers`, `Featurettes`, `Behind The Scenes`, `Deleted Scenes`, `Interviews`, `Scenes`, `Shorts`, `Extras` or `Other`, and a movie's files named for it with `-trailer`, `-featurette`, `-behindthescenes`, `-deleted`, `-interview`, `-scene`, `-short` or `-other`, such as `Alien (1979)-trailer.mkv`. An extra in a folder of loose movies, or anywhere in an Other videos library, is left out. It reads these containers: `mkv`, `mp4`, `m4v`, `mov`, `avi`, `ts`, `m2ts`, `mts`, `webm`, `wmv`, `mpg`, `mpeg`, `flv`, `ogv` and `3gp`.

## Music

A Music library groups its tracks by their tags, as Plex, Jellyfin and Navidrome do: an album in a folder belongs to the album artist (else the artist) of most of its tracks, or to `Various Artists` when none has most of them, when a track is tagged as part of a compilation, or when the album artist is `VA` or `Various`; the artist with the album makes the album, so a compilation tagged track by track or an album split across folders still makes one album. A disc named at the end of the album tag, `Album CD 1` or `Album (Disc 2)`, is that disc of `Album`. Names group without regard to case, spacing, punctuation or a leading "The", "A" or "An", so `Windows 96` and `Windows96` are one artist, named as most of their tracks spell it. MusicBrainz ids, as MusicBrainz Picard tags files, win over names: the tracks of one release are one album, by the title and artist most of them name, whatever edition each names (`Album` and `Album (Deluxe)`), and every name tagged with one artist id (`Beyoncé` and `Beyonce`) is that one artist; an album artist id of MusicBrainz's Various Artists makes a compilation. The scan keeps the artist's, the release's, the release group's and each track's recording's ids for looking them up. A track's title, track and disc numbers, date and genres come from its tags; a file without tags takes its artist and album from its folders and its number and title from its name, `03 - Title.mp3`. The scan reads `flac`, `mp3`, `m4a`, `aac`, `alac`, `opus`, `ogg`, `oga`, `wav`, `aiff`, `aif`, `wma`, `ape` and `wv` files, and no `.nfo`, extras or TMDB lookups. An album's cover is `cover`, `folder`, `front` or `album` in a folder of its tracks, else the picture in its first track that holds one, else the [Cover Art Archive's](#details-from-musicbrainz); an artist's picture is `artist` or `folder` in the folder above an album's, else [their Wikipedia picture](#details-from-musicbrainz), else their newest album's cover. The Compose apps and the Apple TV app show Music libraries, and the [API](api.md#media-libraries) lists them only to a client that asks.

### Details from MusicBrainz

After each scan, the server looks a Music library's albums and artists up on [MusicBrainz](https://musicbrainz.org), which needs no account or key. An album tagged with its release group or release id is fetched by it; any other album is searched by its title and artist, without an edition in brackets at the end of its title (`(Deluxe Edition)`, `[Vinyl]`, `(2011)`), and kept only when MusicBrainz's best match has that title and credits that artist, or Various Artists for a compilation. An artist tagged with its artist id is fetched by it, else by the artist most of its matched albums are credited to, and only failing both is searched by name; two artists with that name and the same score are left unmatched. A match keeps the MusicBrainz id, the year the album first came out, the five genres MusicBrainz votes for most, and the artist's Wikidata item. An album with no cover file and no picture in its tracks shows the front cover the [Cover Art Archive](https://coverartarchive.org) holds for its match, fetched once, 500 pixels across, and kept with the TMDB images. An artist whose Wikidata item links an English Wikipedia page takes its first paragraph as their biography, and, with no picture file of their own, the picture at its top, when Wikimedia Commons holds it (a picture only English Wikipedia holds may not be free to show). Both come under their Wikipedia and Commons licenses, mostly Creative Commons Attribution-ShareAlike, so credit them if you pass them on. The tags always win: nothing renames an album or artist, and an album whose tracks name a genre or a year keeps its own. MusicBrainz takes one request a second, and an album without ids takes two, so a first pass over a few hundred albums takes 10 to 20 minutes, and other libraries' scans wait for it; matches are kept for 30 days and misses for 7, as is an artist whose Wikipedia page has no Commons picture yet, and a later scan asks only for those, and for an album whose tagged ids changed. Refresh details beside a Music library on the admin portal's Library page queues a scan that asks MusicBrainz again for every album and artist, as for a new library, keeping what it found until then; a pass that throttling or a restart cuts short carries on with the rest on the next scan. Each artist is looked up right after their albums. A call MusicBrainz throttles waits and asks again, up to four times over about 30 seconds (or as long as MusicBrainz's `Retry-After` says); throttling that outlasts that, or MusicBrainz not answering three times in a row, ends the pass for that scan, and the scan's job note says so. `SOFA_MUSICBRAINZ=false` turns the lookups off; the lookups send album and artist names and ids to `musicbrainz.org`, ask `coverartarchive.org` (which answers from `archive.org`) for covers by id, and `www.wikidata.org`, `en.wikipedia.org` and `commons.wikimedia.org` (which answers from `upload.wikimedia.org`) for an artist's page and picture, and nothing else.

### Artwork from fanart.tv

With a [fanart.tv](https://fanart.tv/get-an-api-key/) API key, from `SOFA_FANART_KEY` or the Music artwork panel on the admin portal's Library page, each artist MusicBrainz matched is also looked up on fanart.tv by its MusicBrainz id. An artist takes the most liked of its fanart.tv pictures, over the Wikipedia one, and the most liked backdrop, which the app shows behind the artist's page; an album with no cover of its own, in its tracks or at the Cover Art Archive takes the most liked fanart.tv cover of its MusicBrainz match. A picture file in the folder always wins. The lookups ride the MusicBrainz ones and are kept as long; an artist looked up before the key was added is asked once on the next scan. The images are fetched once from `assets.fanart.tv` and kept with the other artwork. They come from fanart.tv's users, under fanart.tv's terms of use.

## Details and artwork

A Kodi `.nfo` beside a file replaces what its name says: `Title (Year).nfo` or `movie.nfo` for a movie, `tvshow.nfo` in a show's folder, and `Show - S01E02.nfo` for an episode. The scan reads the title, sort title, year, plot, runtime, season and episode, air date, genres, studio, rating, content rating, cast and directors. An `.nfo` that is not XML, such as one holding only a link, is ignored.

Artwork is read from the files beside the video, as `.jpg`, `.jpeg`, `.png` or `.webp`:

| Item | Poster | Backdrop |
| --- | --- | --- |
| Movie | `Title (Year)-poster`, `poster`, `folder`, `cover` | `Title (Year)-fanart`, `fanart`, `backdrop` |
| Show | `poster`, `folder`, `cover` in its folder | `fanart`, `backdrop` |
| Season | `season01-poster` in the show's folder, or `poster`, `folder` in the season's | |
| Episode or video | `Name-thumb`, or an image with the video's name | |

When neither the files nor TMDB have a picture, FFmpeg takes a frame a tenth of the way into the video (at most ten minutes in) as an episode's or a video's still and as any file's backdrop. The frame is taken the first time it is asked for and kept under `SOFA_DATA_DIR/artwork`.

Without a TMDB token the server looks nothing up online.

## Details from TMDB

With a [TMDB](https://www.themoviedb.org/settings/api) API read access token (or a v3 API key), each scan of a Movies or TV shows library looks up what the files do not say: a movie's or show's overview, genres, rating, cast and artwork, and each season's and episode's title, air date, overview and still. Other videos libraries are never looked up. `SOFA_TMDB_LANGUAGE` (default `en-US`) picks the language, and its region picks the content rating.

To add the token, paste it into the Library page's Details and artwork panel and choose Check and save. The server asks TMDB whether it takes the token, keeps it as `tmdbToken` in `secrets.json` in the data directory and restarts; the libraries scan again once it is back. Choose Check and save again to replace it. `SOFA_TMDB_TOKEN`, or `SOFA_TMDB_TOKEN_FILE`, sets it in the environment instead and wins over the saved one; the panel then has no form.

A title is matched by its name and year. A `{tmdb-348}` or `[tmdbid-348]` tag in the folder or file name, or a TMDB id in the `.nfo`, pins the match instead. Without a TMDB id, a show's TheTVDB id (`{tvdb-81189}`, `[tvdbid-81189]`, or `<uniqueid type="tvdb">` in `tvshow.nfo`) or an IMDb id (`{imdb-tt0078748}`, `[imdbid-tt0078748]`, `<uniqueid type="imdb">` or `<id>`) is looked up on TMDB first, and the title is searched only when TMDB knows neither; these are the tags the TRaSH naming schemes for Sonarr and Radarr write. Each movie and show carries the TMDB, TheTVDB and IMDb ids it has, from its name, its `.nfo` or TMDB's record, and `GET /api/v1/library/lookup` finds an item by any of them. A match is kept for 30 days and a title TMDB does not know is tried again after 7; a new episode of a known show fetches only its season. A `.nfo` beside the file wins over TMDB's text, and local artwork wins over TMDB's images. A refused token, TMDB's rate limit or three lookups in a row that cannot reach TMDB end the lookups for that scan, and the scan's job note says why; the next scan carries on.

The portal's Library section lists the movies and shows TMDB found nothing for on its Unmatched tab. Find on TMDB searches by a title and year you can change and links each result to its TMDB page; Choose keeps that entry for the item, or give its TMDB id, the number in the entry's address. The choice wins over a tag, a `.nfo` and a search on every later scan, and its library scans at once to fetch the details. Let the scan match it, under Matched by hand, drops the choice and the details it fetched, and the next scan matches the item itself again.

The token also gives the apps' break screen its facts about the live program on now (`GET /api/v1/program-facts`). A program is looked up by its guide title as a show, or first as a movie when the guide files it as one, and matches only a title TMDB lists as it is, letters and digits compared, so `Paid Programming` finds nothing rather than something wrong. The answers are kept in memory, the latest 500 titles: a match for 7 days, a title TMDB does not list for a day and a failed lookup for 10 minutes.

This product uses the TMDB API but is not endorsed or certified by TMDB.

## Scans

A library is scanned when it is added, every six hours, when Scan now is pressed in the portal or `POST /api/v1/admin/libraries/{id}/scan` is called, and when Sonarr, Radarr or another tool reports a changed file through `POST /api/v1/admin/libraries/scan` or a [hook](integrations.md). A request names a path, and the server queues a scan of the library whose folder holds it; requests for one library coalesce until its scan starts. The Jobs page lists the scan as Library scan. FFprobe reads each new or changed file's container, length and video, audio and subtitle streams; an unchanged file (same size and change time) keeps what the last scan read.

Every file keeps one id for as long as its path is the same, even across a removed and re-added library, so watch progress survives a rescan. A file that disappears is marked missing and is removed after seven days unless it comes back. A library folder that is gone, or empty while the library held files, reads as an unmounted disk: the scan changes nothing, and the portal shows Folder unavailable until the folder is back.

## Intros

After each scan of a TV shows library the server looks for the intro and the end credits each episode shares with the others of its show, the way Jellyfin's Intro Skipper does, and the app offers Skip intro while one plays and Skip credits, or Next episode, while the credits roll. The Jobs page lists the pass as Intro and credits detection; it runs one episode at a time, apart from the scan, and Run now starts it for every TV shows library.

FFmpeg reads a [Chromaprint](https://acoustid.org/chromaprint) fingerprint of each new or changed episode's audio: its first ten minutes, or its first 30% when that is shorter, which takes a few seconds an episode. The fingerprints are kept in the database, so an unchanged file (same size and change time) is never read again. Each episode is then compared with the four nearest of its show, the same season first: the intro is the stretch of 15 seconds to 2 minutes they share, where most of them agree. A show needs two episodes before either has an intro, and a stretch only one other episode shares, such as a recap, does not count when three or more could agree. An episode whose opening differs from the rest of its show's gets no intro, and movies and other videos are not searched.

Credits are found the same way from a second fingerprint of each episode's last tenth, at most its last five minutes, which needs the episode's length from the scan: the credits are the stretch of 15 seconds to 5 minutes the end shares with the nearest episodes'. A show whose credits play over different music each week has none found. Episodes read before credits detection have their ends read on the next pass, without reading their starts again.

## Scrub thumbnails

After each scan the server makes the thumbnails the app's library player shows over its seek bar while you scrub, the way Jellyfin's trickplay images do: one picture every ten seconds (the key frame at or just before each mark), 240 pixels wide, tiled ten by ten onto JPEG sheets. Movies, episodes and videos of five minutes or more get them; FFmpeg decodes only the key frames, so a two-hour film takes a minute or two. The Jobs page lists the pass as Scrub thumbnails; it runs one file at a time, apart from the scan, and Run now starts it for every library. An unchanged file (same size and change time) is never read again, a file FFmpeg cannot read is noted and not tried again until it changes, and the sheets of a file that is gone are deleted with the next pass. The sheets are kept under `SOFA_DATA_DIR/scrub`, a few megabytes for a two-hour film; deleting the folder makes them again, which is how sheets made before their pictures moved onto the mark pick up the change.

## Browse and watch

Clients read the libraries through the [library API](api.md#media-libraries): a home with Continue watching, the household's [Watchlist](api.md#watchlist) and Recently added for each library, each library's movies, shows or videos, a show's seasons and episodes, and search. Watch progress is the household's: an episode finished on the TV is watched on the phone too, and a show's next episode is the one after the last finished. Removing an item from Continue watching removes it for the whole household until someone plays it again. A client plays a file through a [library playback session](api.md#play-library-files): as it is when the player takes its format, otherwise remuxed or converted to HLS by FFmpeg. The app's Library tab, on the TV, phones, desktops, the web and the Apple TV, is the client; see the [app's usage](../../app/docs/usage.md#library).

Copied HEVC is written as fragmented MP4 tagged `hvc1`, which every player takes, and an HEVC file in MP4 tagged `hev1` is remuxed that way instead of played as it is, since AVPlayer refuses that tag. An HDR file converted for a player that cannot show HDR is tone-mapped to SDR. A file with more than 8 bits a sample, such as HEVC Main 10 or H.264 High 10, plays as it is only on a player that decodes that depth, and is converted to 8-bit H.264 for any other. Subtitles come from inside the file and from subtitle files beside it, named for the video with an optional language and `forced` or `sdh`, such as `Movie (2020).en.srt` or `Episode.de.forced.sup`. Text subtitles (SubRip, ASS, MP4 text, WebVTT) are served as WebVTT, which the apps draw over the picture. Picture subtitles (PGS from Blu-ray, VobSub from DVD) have no text, so the server draws the chosen one into the picture as it converts the file, which costs a transcode.

## Hardware transcoding

A file the player cannot take is converted to H.264 with libx264 on the CPU. A GPU encoder takes most of that work off it: give the container the GPU, and `SOFA_HWACCEL=auto`, which `deploy/compose.yaml` sets, finds the encoder. At start it tries NVENC when an NVIDIA device is there, then VA-API and Quick Sync when the render node is, encodes one test frame with each and keeps the first that works, logging `vod encoder ready accel=vaapi (auto)` or, with none, `no GPU encoder works`. `SOFA_HWACCEL` can also name one encoder, `vaapi` (Intel or AMD), `qsv` (Intel Quick Sync) or `nvenc` (NVIDIA); when its test frame fails the server logs `vod encoder unavailable`. Either way a failure converts in software, and decoding, scaling and tone mapping stay on the CPU, so any file still converts.

The About page's Video encoder panel shows the verdict: what `SOFA_HWACCEL` asked for, the encoder in use, and, for each encoder the check tried, why it failed, with the FFmpeg command that ran. When `SOFA_HWACCEL` names one encoder and it fails, the home page says so too, since everything then converts in software. Run the transcode test there, or as Transcode test on the Jobs page: it encodes five seconds of a moving 1080p test picture through software and each GPU encoder whose device is there, and shows how much faster than real time each one went. Each viewer converting a file needs one encoder running faster than 1.0x, and virtual channels and multiview take the same encoder.

For an Intel or AMD GPU, store the id of the host group that owns the render node in `.env`, then uncomment the `devices` and `group_add` lines in `compose.yaml`; `SOFA_HWACCEL_DEVICE` picks another node:

```sh
printf 'RENDER_GID=%s\n' "$(stat -c %g /dev/dri/renderD128)" >> .env
```

```yaml
    devices:
      - /dev/dri:/dev/dri
    group_add:
      - "${RENDER_GID:?set RENDER_GID in .env}"
```

The image carries Mesa's VA-API driver for AMD and, on amd64, Intel's media driver and Quick Sync runtime. For `nvenc`, install the NVIDIA driver and the NVIDIA Container Toolkit on the host (`nvidia-smi` works there, and `docker info` lists the `nvidia` runtime), then give the service the GPU, by uncommenting the NVIDIA lines in `compose.yaml` or with these in `compose.override.yaml`, which `docker compose` and `sofa-king-update` read beside it:

```yaml
services:
  sofa-king-backend:
    environment:
      NVIDIA_DRIVER_CAPABILITIES: compute,video,utility
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu, compute, video, utility]
```

The capabilities decide which driver libraries the runtime mounts. FFmpeg's NVENC encoder needs `video` (the encoder library) and `compute` (the CUDA library it opens first), and `utility` puts `nvidia-smi` in the container. Docker turns the listed capabilities into `NVIDIA_DRIVER_CAPABILITIES`, so `[gpu, video]` alone mounts no CUDA library and the test frame fails. Check the GPU from inside with `docker exec sofa-king-backend nvidia-smi`. GeForce drivers limit how many NVENC encodes run at once (8 on current drivers), so set `SOFA_MAX_TRANSCODES` below that. NVENC also draws [multiview](api.md#multiview) at 1080p, since `SOFA_MULTIVIEW=auto` offers it only when this encoder works.
