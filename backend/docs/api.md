# API

The API uses JSON under `/api/v1`. All timestamps are UTC instants. The examples assume `$SOFA_API_TOKEN` and `$SOFA_ADMIN_TOKEN` hold the tokens and that the server runs under Docker at `SERVER_HOST:9193`; outside Docker the port is 8080.

## Tokens

Send `Authorization: Bearer $SOFA_API_TOKEN` on every `/api/v1` request. Routes under `/api/v1/admin` take `Authorization: Bearer $SOFA_ADMIN_TOKEN` instead when an admin token is configured. On a loopback server with no token, leave the header out; it then answers only requests from the same machine. A missing or wrong token answers `401 AUTH_REQUIRED`.

The server also issues tokens of its own, each starting `sk_`, stored only as a hash and revoked one at a time:

| Kind | Where it comes from | What it opens |
| --- | --- | --- |
| `api` | A device [pairing](#pair-a-client), one per `clientId`; forgetting the device revokes it | What `SOFA_API_TOKEN` opens, except `/api/v1/admin` and `/api/v1/hooks` |
| `read` | The portal's Settings page or `POST /api/v1/admin/tokens`, for a dashboard or a calendar | The [dashboard summary](#dashboard-summary) `GET /api/v1/status`, the [recordings calendar](#player-outputs) `/output/recordings.ics` (also as `?token=`), and `GET` of `/api/v1/admin/status`, `/sessions`, `/guides`, `/lineup`, `/policy/preview`, `/policy/findings`, `/policy/changes` and `/libraries`, with what lies under each |
| `hook` | The same, for Sonarr and Radarr | The [hooks](#hooks) only: `/api/v1/hooks` and the Emby-style routes |

`SOFA_API_TOKEN` stays valid beside them, for scripts and devices set up by hand, and opens the hooks too.

These routes take no token: `/healthz`, `/readyz`, `/logos/{key}`, `/artwork/{id}/{role}`, `/artwork/{id}/scrub/{sheet}`, `POST /api/v1/pair` (a [pairing code](#pair-a-client) stands in for the token) and the session media URLs under `/media/sessions/` and `/media/vod/`, which work until the session ends or its epoch changes. The HDHomeRun routes take none either and admit clients by address. `/output/*` takes the output token, and the admin portal its own session after the sign-in page. A browser page on another origin can call the API when `SOFA_CORS_ORIGINS` lists its origin. The whole model is in [security](security.md#credentials-and-the-routes-they-open).

## Endpoints

| Method | Path | Response or action |
| --- | --- | --- |
| GET | `/healthz` | Public liveness check |
| GET | `/readyz` | Public database readiness check |
| GET | `/api/v1/bootstrap` | Server ID, time, API version and capabilities (`catalog`, `guide`, `playback`, `events`, `viewers`, `library`, `recordings`) |
| POST | `/api/v1/pair` | Exchange a pairing code for a token of the device's own, see [pair a client](#pair-a-client); no token. 10 claims a minute per client address, then `429` |
| GET | `/api/v1/preferences/{clientId}` | The preferences the client with that id last saved, see [viewer preferences](#viewer-preferences); `404 VIEWER_NOT_FOUND` before its first save |
| PUT | `/api/v1/preferences/{clientId}` | Replace that client's preferences; answers the stored document with the server's `updatedAt` |
| GET | `/api/v1/channels` | Catalog revision and the enabled lineup entries as channels; supports ETag/If-None-Match |
| GET | `/api/v1/providers` | Provider import status and channel counts |
| GET | `/api/v1/sources` | Health and manual-disable flag of every source |
| PATCH | `/api/v1/sources/{id}` | Set `manuallyDisabled`; takes the admin token, as the admin routes do |
| POST | `/api/v1/guide/query` | Programs overlapping a requested channel/time window |
| POST | `/api/v1/playback-sessions` | Create a preview or watch session |
| GET | `/api/v1/playback-sessions/{id}` | Read the current playback descriptor |
| PATCH | `/api/v1/playback-sessions/{id}` | Renew the lease and optionally change intent |
| GET | `/api/v1/program-facts?title=&movie=` | What TMDB says about a live program, found by its exact guide title, for the break screen: `kind` (`show` or `movie`), `year`, `endYear`, `studio`, `seasons`, `episodes`, `cast`, `creators`, `tagline` and `rating`. `movie=true` searches movies first. Every field is empty when TMDB lists no such title or the server has no TMDB token; see [libraries](libraries.md#details-from-tmdb) |
| GET | `/api/v1/playback-sessions/{id}/ad-break?aheadMs=` | Whether the session's player shows an ad: `inBreak` and, when the stream planned the break, `remainingMs`. `aheadMs` is how much the player has loaded past the picture; see [architecture](architecture.md#playback) |
| DELETE | `/api/v1/playback-sessions/{id}` | Release the session; returns 204 |
| GET | `/api/v1/recordings` | Every [recording](#recordings), scheduled and running soonest first, then the rest newest first, and the recordings folder's `storage` |
| POST | `/api/v1/recordings` | Record the program on `channelId` that starts at `startsAt`, or with `endsAt` (and an optional `title`) that [time window](recordings.md#record-a-time-window); `201`, or the existing recording. `404 CHANNEL_NOT_FOUND` or `PROGRAM_NOT_FOUND`, `409 PROGRAM_ENDED` |
| GET | `/api/v1/recordings/{id}` | One recording; `404 RECORDING_NOT_FOUND` |
| PATCH | `/api/v1/recordings/{id}` | Set `protected`, `positionMs` or `watched` |
| DELETE | `/api/v1/recordings/{id}` | Cancel a scheduled recording, stop a running one, or delete a finished one and its file; returns 204 |
| GET | `/api/v1/recording-rules` | Every series rule, oldest first |
| POST | `/api/v1/recording-rules` | Add a series rule, by title or with a `match` for a team or keywords ([recordings](recordings.md#record-a-series)); `201` |
| PATCH | `/api/v1/recording-rules/{id}` | Change `channelId` (`anyChannel: true` clears it), `newOnly`, `keep`, the paddings or `priority`; `404 RULE_NOT_FOUND` |
| DELETE | `/api/v1/recording-rules/{id}` | Delete a rule and its scheduled recordings; what it recorded stays. Returns 204 |
| GET | `/media/sessions/{id}/{epoch}/{token}` | Session-authorized HLS or MPEG-TS media. `{epoch}` is the descriptor's epoch and `{token}` a random token for one playlist, segment or key |
| GET | `/output/playlist.m3u` | The enabled lineup as an M3U playlist for third-party players, see [player outputs](#player-outputs) |
| GET | `/output/guide.xml?days=` | The lineup's programs as XMLTV |
| GET | `/output/stream/{channelId}.m3u8` or `.ts` | Opens a player session for the channel and streams it |
| GET | `/logos/{key}` | A cached lineup logo, public, see [logos](#logos) |
| GET | `/api/v1/libraries` | Every [media library](#media-libraries) with its count of movies, shows or videos |
| GET | `/api/v1/libraries/{id}/items?sort=&unwatched=&genre=&cursor=&limit=` | One page of a library's movies, shows or videos |
| GET | `/api/v1/library/home` | Continue watching, then Recently added for each library, and the libraries |
| GET | `/api/v1/library/items/{id}` | An item with its seasons or episodes, the next episode of a show, and a file's tracks |
| GET | `/api/v1/library/lookup?tmdb=&tvdb=&imdb=` | The movies and shows whose TMDB, TheTVDB or IMDb (`tt…`) id is one of those given, as a `LibraryPage` in title order. 400 without an id or with a malformed one |
| GET | `/api/v1/library/search?q=` | Movies, shows and videos whose title holds `q`, then those with a cast member or director whose name holds it, then episodes by title; at most 50 |
| PUT | `/api/v1/library/items/{id}/play-state` | Record where playback stopped, or mark an item, season or show watched or unwatched |
| GET | `/api/v1/library/items/{id}/subtitles/{index}` | A text subtitle track as WebVTT, see [play library files](#play-library-files) |
| POST | `/api/v1/vod-sessions` | Play a library movie, episode or video, see [play library files](#play-library-files) |
| GET | `/api/v1/vod-sessions/{id}` | Read a library playback session |
| PATCH | `/api/v1/vod-sessions/{id}` | Renew it, and with `startMs` restart a converted stream there |
| DELETE | `/api/v1/vod-sessions/{id}` | Release it; returns 204 |
| GET | `/media/vod/{id}/{epoch}/{secret}/{name}` | The session's file with byte ranges, or its HLS playlist and segments |
| GET | `/artwork/{id}/{role}` | An item's `poster` or `backdrop`, public, see [media libraries](#media-libraries) |
| GET | `/artwork/{id}/scrub/{sheet}` | A sheet of an item's scrub thumbnails, public, see [media libraries](#media-libraries) |
| GET | `/discover.json`, `/lineup.json`, `/lineup_status.json`, `/device.xml` | HDHomeRun device as Plex reads it, see [HDHomeRun emulation](#hdhomerun-emulation) |
| GET, POST | `/lineup.post` | Accepts Plex's channel-scan request; answers 200 |
| GET | `/auto/v{number}` | MPEG-TS stream of the channel with that lineup number |
| GET, POST | `/admin`, `/admin/*` | The [admin portal](#admin-portal-routes), HTML |

Request bodies are limited to 64 KiB, except a policy write through `PUT /api/v1/admin/policy` or the portal's group and provider edits (4 MiB). A larger body answers `413 REQUEST_TOO_LARGE` before the token is checked. Every response carries an `X-Request-Id` header with the id the server's log lines for that request carry. Responses under `/api/` and `/admin`, the M3U playlist, the XMLTV guide and `/lineup.json` are gzip- or deflate-compressed when the request's `Accept-Encoding` allows it; media, streams, logos and artwork never are. The live TV fields are defined in the [contract](../../contract/src/commonMain/kotlin/dev/jamesrich/sofaking/contract/), which the app compiles against too, and the admin fields in the [server's models](../server/src/main/kotlin/dev/jamesrich/sofaking/backend/api/); see [shared types](#shared-types).

Channel IDs are lineup entry IDs. A channel's `displayNumber`, `name` and `tags` (the group) come from its entry; `logo` is the channel's logo as the server-relative path `/logos/KEY` (see [logos](#logos)), which a client resolves against the base URL it uses for the API, a proxy included (the server cannot be served under a sub-path); `legacyGuideId` is the mapped XMLTV channel ID; `guideStatus` is `unassigned`, `empty`, `available` or `stale`; `playbackEligible` is true when any of the entry's sources is selectable. Channels are listed in lineup order.

Each provider status reports `lastSuccessAt`, `errorCode` (`IMPORT_FAILED` when the last import kept the previous snapshot), `channels` and `sources` from the last successful import, `skippedEntries`, `skipReasons` and `notes`. Skip reasons are counts by short key, for example `duplicate-source=1, missing-name=3`: `missing-name`, `invalid-url`, `invalid-uuid`, `duplicate-source` (the same source key twice, see [source identity](live-tv.md#source-identity)), `bad-protocol` and `no-extinf`. Notes count imported entries whose identity was derived, for example `merged-key=2, url-identity=724`: `url-identity` (no `channel-id`, `tvg-id` or `channel-uuid`, so the URL is the identity) and `merged-key` (a published identity repeated with another name or number, kept as an alternative source of the first entry). Both are null when empty.

## Query the guide

Replace `CHANNEL_UUID` with a catalog ID. Set `FROM_UTC` and `TO_UTC` to ISO 8601 UTC timestamps (`YYYY-MM-DDTHH:MM:SSZ`):

```sh
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://SERVER_HOST:9193/api/v1/guide/query \
  -H 'Content-Type: application/json' \
  --data '{
    "channelIds": ["CHANNEL_UUID"],
    "from": "FROM_UTC",
    "to": "TO_UTC"
  }'
```

Request 1-200 distinct channels and a positive window of at most 24 hours. Programs overlap when `startsAt < to && endsAt > from`. A disabled or unknown entry, one a client's catalog still lists after the lineup changed, gets a row with status `removed` and no programs instead of failing the query; the client reads the catalog again.

Each program has `id`, `channelId`, `startsAt`, `endsAt`, `title` and `description`, and the metadata its guide source provides: `subTitle` (the episode title), `episode` (a label such as `S2 E5` or `E5`, or the source's on-screen numbering), `isNew` (a first airing), `rating` (such as `TV-14`), `icon` (an image URL on the source's host) and `categories` (the source's genres, such as `Movies` or `Sports`, each once). A field the source does not provide is null, false for `isNew` or empty for `categories`. Clients should ignore fields they do not know.

To page through a consistent guide, pass the returned `revision` in subsequent requests. A changed revision (any guide re-imported, or the lineup changed) returns `409 RESYNC_REQUIRED`; query again without the old revision.

## Open and release playback

Create a session with a catalog channel ID and a unique idempotency key of 1-128 characters:

```sh
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://SERVER_HOST:9193/api/v1/playback-sessions \
  -H 'Content-Type: application/json' \
  --data '{
    "channelId": "CHANNEL_UUID",
    "intent": "preview",
    "idempotencyKey": "UNIQUE_REQUEST_KEY"
  }'
```

The server picks the entry's first selectable source, in the [selection order](source-health.md#source-selection). The response contains `id`, `expiresAt`, a `descriptor` with `protocol`, `mimeType`, relative `url`, and `epoch`, and the `providerId` of the source the server picked. Resolve the URL against the server address and give it to the player.

The descriptor's `protocol`, `mimeType` and URL suffix (`.m3u8` or `.ts`) come from the source's protocol, not from the upstream path. That protocol is the one a probe or media fetch detected in the stream body and stored in the database; a source without a detection yet uses the import's protocol. If a media fetch finds the other protocol (an M3U behind an extensionless URL, or MPEG-TS behind a `.m3u8` path), the server stores it, increments the epoch and answers `410 STREAM_CHANGED`; fetch the session and open the new URL. Every later session on that source, including after a restart, starts with the detected protocol.

A watch session expires 90 seconds after its last renewal, a preview after 45 seconds. When `SOFA_MAX_SESSIONS` sessions are active, a new open evicts the oldest preview session. With no preview, it evicts the idle external session (an M3U player or HDHomeRun session with no media request in flight) that expires first. An evicted session's media requests end, and later calls on it answer `410 SESSION_EXPIRED`. With neither to evict, the open answers `429 CAPACITY_EXHAUSTED`. Renew and promote a preview without replacing its stream:

```sh
curl --fail -X PATCH \
  -H "Authorization: Bearer $SOFA_API_TOKEN" \
  http://SERVER_HOST:9193/api/v1/playback-sessions/SESSION_ID \
  -H 'Content-Type: application/json' \
  --data '{"intent":"watch"}'
```

Send `{}` to renew without changing intent. Fetch the session periodically; if its descriptor epoch changes, reopen the returned media URL. When the current source stops being selectable, the fetch moves the session to the entry's next selectable source with a new epoch.

A session serves at most 12 media requests at once; one more answers `429 CAPACITY_EXHAUSTED`. A media request for a session whose source is no longer selectable answers `503 SOURCE_UNAVAILABLE` and ends the session's other media requests; fetch the session to move it to the next source. A media request whose provider source fails to resolve moves the session to the channel's next selectable source and answers `410 STREAM_CHANGED`, so the player re-reads the session; with no other source it answers `502 UPSTREAM_UNAVAILABLE`. A media URL from an older epoch answers `410 STREAM_CHANGED`, and one for a session that ended answers `410 SESSION_EXPIRED`.

Release the session when playback ends:

```sh
curl --fail -X DELETE \
  -H "Authorization: Bearer $SOFA_API_TOKEN" \
  http://SERVER_HOST:9193/api/v1/playback-sessions/SESSION_ID
```

Release is idempotent and cancels active upstream requests; a media request still waiting for its stream to start answers `410 SESSION_EXPIRED`. Repeating an open request with the same key returns the same session while it remains active, and `410 SESSION_EXPIRED` once it has ended; open a new session with a new key. A reused key with different parameters returns `409 IDEMPOTENCY_CONFLICT`.

## Pair a client

A viewer need not type the API token into a TV: the admin portal's [Devices page](admin.md#devices) (or `POST /api/v1/admin/pairing-codes`) issues a six-digit code, live for ten minutes and good for one claim; a new code replaces the live one, and five wrong claims void it. The client posts the code with its own id and label, and receives an `api` [token](#tokens) of its own:

```sh
curl -X POST http://SERVER_HOST:9193/api/v1/pair -H 'Content-Type: application/json' \
  -d '{"code":"123456","clientId":"3f0b5f2e-6c1d-4f8a-9e2b-7a1c5d4e3b2a","name":"Living room TV","platform":"android-tv"}'
```

The answer is `{"apiToken": "...", "serverId": "..."}`; a server running without an API token answers an empty one. Pairing the same `clientId` again revokes the token it held before, and forgetting the device revokes the one it holds. A wrong, spent or expired code answers `403 PAIRING_INVALID`. `clientId`, `name` and `platform` are optional; with a `clientId` the server lists the device on the Devices page from then on. The portal's QR code carries the same code as `sofaking://pair?server=URL&code=DIGITS`, which the Android and iOS apps open.

## Viewer preferences

Each client keeps one document under its own id, so a reinstall or a second device of the same viewer can take it over, and the portal can show what a device keeps. `clientId` is 1 to 64 letters, digits, dots, dashes or underscores. The body of a `PUT` is:

| Field | Contents |
| --- | --- |
| `hidden` | Channel ids the client leaves out of its guide and channel list |
| `favorites` | Starred channel ids, in the viewer's order |
| `recent` | Channels watched last, newest first |
| `name`, `platform` | How the device calls itself; an empty value keeps the stored one |

Each list holds at most 1,000 distinct ids. The server sets `updatedAt` and answers the stored document; a client compares that stamp to the one it last synced to decide whether the server's copy is newer than its own. The app pushes a change a few seconds after it settles and takes the server's copy over at start when another device wrote it since.

## Media libraries

`library` in the bootstrap capabilities says the server has the routes below. Items are what a [library scan](libraries.md) found, with what TMDB knows filled in; a `.nfo` beside a file wins over TMDB's text, and the library's own images over TMDB's. `kind` is `movie`, `show`, `season`, `episode` or `video`. A show's `children` counts its seasons (or its episodes when it has no season folders) and `unwatched` its unwatched episodes; an episode names its show in `showId` and `showTitle`. `videoHeight` and `hdr` come from FFprobe, for a 4K, HD or HDR badge.

A library's items come sorted by `sort`: `title` (the default, ignoring a leading "The", "A" or "An"), `added`, `year`, `rating` or `watched`, the newest or highest first; `watched` puts the most recently played first (a show by its latest episode) and what was never played after it, by title. `unwatched=true` keeps only unwatched movies and videos and shows with an unwatched episode, and `genre` only those in that genre, in any case. Every page's `genres` lists all the library's genres, for a filter. The first page in `title` order also has `letters`: runs of `letter` (an upper-case letter, or `#` for a digit or symbol, after a leading article) and `count`, in the items' order, so the counts added up give where each letter starts, for a client's letter jump. `limit` is 1 to 500 (default 100), and `next` is the `cursor` for the page after. An item's page lists a show's seasons in order (Specials, season 0, first) or a season's episodes, and for a show `next`: the episode last played if it stopped part way, a rewatch included, else the first unwatched one after it, never a special. An episode's page lists in `segments` the spans a player offers to skip, with `startMs` and `endMs` in the file, once [intro detection](libraries.md#intros) has found them: `kind` `intro` for the intro and `credits` for the end credits. A client ignores a kind it does not know. A movie's or show's page lists its `extras`, items of kind `extra` with `extra` naming theirs (`trailer`, `featurette`, `behind_the_scenes`, `deleted_scene`, `interview`, `scene`, `short` or `other`; a client shows a kind it does not know as `other`), trailers first; an extra plays like any file and has a page of its own, but is in no grid, search or home row. A movie's, show's or video's page also lists in `related` up to 12 others in its library that share its genres, the most shared first and then the best rated. A movie's, episode's or video's page has `scrub` once [scrub thumbnails](libraries.md#scrub-thumbnails) are made from its file: `sheets`, the paths of JPEG sheets of `columns` by `rows` tiles, each `tileWidth` by `tileHeight` pixels, left to right and then down; tile `n` overall, on sheet `n / (columns * rows)`, shows the file at `n * intervalMs`, and there are `frames` in all. It is `null` before then, for a file under five minutes, and while a changed file waits for its new ones.

`PUT /api/v1/library/items/{id}/play-state` takes `positionMs`, `durationMs`, `watched` and `dismissed`, and answers the item. A stop in the first 5% leaves the item where it was, and one at 90% or later marks it watched and back at the start. `watched` set by hand wins over the position; it is the only field a show or season takes, and it marks every episode in it. Watch state belongs to the household, not one device. Continue watching lists, newest first, the movies, videos and episodes stopped part way, rewatches included (a rewatch keeps `watched` and carries the position), and the next episode of each show whose last-played episode was finished, one entry per show. `dismissed: true` alone takes a movie, video or episode off Continue watching and leaves its place and `watched` as they are (the item's `playState.dismissed` says so); `dismissed: false` puts it back, and the next play-state write with a position clears it too.

`poster` and `backdrop` are server-relative `/artwork/ID/ROLE?v=VERSION` paths a client resolves against its API base URL. A season without its own poster, and a season or episode without its own backdrop, name their show's. The route takes no token, as image loaders fetch with none, and serves only the image the library holds for that item; the version changes with the image, so a client may cache it for good. TMDB's images are downloaded once into `SOFA_DATA_DIR/artwork`, which can be deleted at any time. Scrub thumbnail sheets are served the same way under `/artwork/ID/scrub/N?v=VERSION`.

## Play library files

`POST /api/v1/vod-sessions` takes the item's `itemId`, the player's `profile`, `startMs` (where to start, for a resume) and `audioIndex` (the audio stream's index from the item's `audio` tracks; the file's default when absent). A stream other than the default is never played directly, since the stream has to carry it. The profile lists the `containers` (`mp4`, `mkv`, `webm`, `ts`), `videoCodecs` (`h264`, `hevc`, `vp9`, `av1`, `mpeg2video`) and `audioCodecs` (`aac`, `ac3`, `eac3`, `mp3`, `opus`, `flac`, `dts`, `truehd`) the player decodes, its `maxHeight`, `hdr` when it shows HDR, and `deepColor`, the video codecs it also decodes at more than 8 bits a sample (such as `hevc` for HEVC Main 10). Without `deepColor` the server takes every listed codec but `h264` as decoded at any depth. The server answers `201` with a session whose `mode` says how it plays:

| Mode | When | What the player gets |
| --- | --- | --- |
| `direct` | The player takes the container, the video and the audio | The file itself (`protocol` `file`), with byte ranges; the player seeks on its own |
| `remux` | It takes the codecs but not the container | HLS with MPEG-TS segments, the streams copied |
| `audio` | It takes the video but not the audio | HLS with the video copied and the audio converted to stereo AAC |
| `transcode` | Anything else, an HDR file on an SDR player, a picture subtitle to draw, or a file FFprobe could not read | HLS with H.264 at up to 1080 lines and stereo AAC |

An HLS session's playlist grows as FFmpeg writes it (an EVENT playlist) and ends when the file does. Its media time 0 is `startMs` into the item, and `durationMs` is the item's whole length. To seek past what the playlist holds, `PATCH` the session with the new `startMs`: FFmpeg restarts there, the epoch changes, and the player loads the new descriptor's URL. A player whose scrubber cannot do that, such as the Apple TV's, sends `wholePlaylist: true`: a `transcode` session then serves a VOD playlist of the whole item in four-second segments, with `startMs` 0, and the player seeks to where it wants to start. A segment FFmpeg is not about to write restarts it at that segment, and the segments already written are kept for the session, so seeking back plays at once; `PATCH` with `startMs` only renews it. Copy modes keep the growing playlist, since they cut at the file's own key frames. A copied stream starts at the key frame before `startMs`, so it can begin a few seconds early. At most `SOFA_MAX_TRANSCODES` sessions convert or remux at once (direct play is not counted); one more answers `503 CAPACITY_EXHAUSTED`. A session lasts five minutes past its last renewal or media request; the player renews it with `PATCH` while paused and releases it with `DELETE`. Its files are under `SOFA_DATA_DIR/transcode` and are deleted when it ends. A missing file answers `404 ITEM_MISSING`. Copied HEVC is written as fragmented MP4 tagged `hvc1` (`init.mp4` and `seg0.m4s` on), other streams as MPEG-TS; an HDR file converted for an SDR player is tone-mapped. Streams carry no subtitle tracks; `subtitleIndex` in the request names a picture subtitle track (PGS, DVD or DVB, inside the file or beside it) that FFmpeg draws into the picture, scaled to its size, which makes the session `transcode`. A text track there answers `400 INVALID_REQUEST`, since the player draws it, and so does one on a recording.

An item's `subtitles` list the subtitle streams of its file by stream index, then the subtitle files beside it from index 1000. A subtitle file is one named for the video with an optional language, `forced`, `default` or `sdh` before the extension, as in `Movie (2020).en.forced.srt`: SubRip (`.srt`), WebVTT (`.vtt`), ASS or SSA (`.ass`, `.ssa`), PGS (`.sup`) or VobSub (`.idx` with its `.sub`). The files are read each time an item's page is, so one added after a scan shows at once.

`GET /api/v1/library/items/{id}/subtitles/{index}` returns a text subtitle track from the item's `subtitles` (SubRip, ASS, SSA, MP4 text or WebVTT) as WebVTT, with times in the item's time, so a player draws it over any stream mode by the item's position. The first read extracts the track, which reads the whole file, and up to three tracks are extracted at once; the copy under `SOFA_DATA_DIR/transcode/subtitles` serves later reads until the file changes. A picture track (PGS, DVD, DVB) answers `415 SUBTITLE_NOT_TEXT`; ask for it in a session's `subtitleIndex` instead. One FFmpeg cannot read answers `502 SUBTITLE_FAILED`.

A [recording](recordings.md#play-a-recording) plays the same way: send its `recordingId` instead of `itemId`, and the session echoes `recordingId` with an empty `itemId`. A finished recording's `durationMs` is the file's length. One still recording is never played directly: it gets HLS that follows the file as it grows, with `durationMs` the length its window will have, and the stream ends a minute after the file stops growing. A recording with nothing written yet answers `409 RECORDING_NOT_READY` (retryable), and one that is only scheduled, failed or unknown `404 RECORDING_NOT_FOUND`.

## Hooks

Sonarr, Radarr and other tools call these when they add, rename or delete a file, so the library shows it without waiting for the six-hourly scan. Each takes a `hook` [token](#tokens), or `SOFA_API_TOKEN`, and answers `202` with `{"libraries"}`, the ids of the libraries whose scan it queued: none when no library holds the path or the event changes no file. [Use with Sonarr, Radarr and Bazarr](integrations.md) has the setup.

| Method | Path | Notes |
| --- | --- | --- |
| POST | `/api/v1/hooks/servarr` | The Webhook connection's payload. `Download`, `Rename`, `EpisodeFileDelete`, `MovieFileDelete`, `SeriesDelete` and `MovieDelete` queue a scan of the library holding `series.path` or `movie.folderPath`; `Test` and the other events queue nothing. The token goes as a bearer or as the Basic password |
| POST | `/Library/Media/Updated` | The Emby / Jellyfin connection's refresh, `{"Updates": [{"Path", "UpdateType"}]}`: queues a scan of the libraries holding each `Path`. The token goes in `X-MediaBrowser-Token`, as that connection sends it, or `X-Emby-Token` |
| GET | `/System/Configuration`, `/Items` | What the same connection reads first: its Test button checks the token on `/System/Configuration`, which answers `{}`, and a refresh looks the series or movie up in `/Items`, which is always empty, so the connection sends the folder it knows |
| POST | `/Notifications/Admin` | The same connection's Send Notifications option; answers `204` and does nothing |

The Emby-style routes answer under `/emby` and `/mediabrowser` too. Paths are mapped through `SOFA_LIBRARY_PATH_MAP` when Sonarr or Radarr sees the media under another folder than the server.

## Recordings

[Recordings](recordings.md) describes the schedule and what a recording does while it runs. A recording carries the guide's `title`, `episodeTitle`, `description`, `season`, `episode`, `movie`, `year` and `image` as they were when it was scheduled; `startsAt` and `endsAt` are the program's times, and the recording runs `startPaddingSeconds` before and `endPaddingSeconds` after them. `state` is `scheduled`, `recording`, `completed` or `failed`, with `reason` when one failed or was cut short. `path` is relative to the recordings folder and `bytes` is the file's size. `conflict` marks a scheduled recording that overlaps more recordings than the server has playback slots. `commercials` is `scanning`, `done` or `failed` once the server looks for [commercial breaks](recordings.md#commercial-breaks), and `breaks` lists the ones it found as `startMs` and `endMs` from the start of the file. `storage` in the list holds the folder's disk `freeBytes` and `totalBytes`, and the `usedBytes` of running and kept recordings.

A rule has a `title`, an optional `channelId`, `newOnly`, `keep` (0 keeps all), `startPaddingSeconds`, `endPaddingSeconds` and `priority`; [record a series](recordings.md#record-a-series) lists their defaults and ranges.

## Dashboard summary

`GET /api/v1/status` is what a dashboard such as [Homepage](deploy.md#homepage-dashboard) shows. It takes a `read` token, the API token or a device token:

```json
{
  "revision": "0123abc",
  "uptimeSeconds": 86400,
  "channels": { "total": 120, "enabled": 96, "eligible": 94 },
  "sessions": 2,
  "recordings": { "running": 1, "scheduled": 7, "conflicts": 0, "next": { "title": "News", "channelName": "Channel One", "startsAt": "2026-10-25T18:00:00Z" } },
  "libraries": { "libraries": 2, "movies": 410, "shows": 37, "episodes": 2210, "videos": 0 },
  "updateAvailable": false
}
```

`channels` counts the lineup's entries, those turned on, and those with a source that can play. `sessions` counts live and library playback open now. `recordings.conflicts` counts the scheduled recordings that conflict, and `next` is the next to start. `updateAvailable` is null until the update check has an answer, and `recordings` and `libraries` are null on a server without them.

## Player outputs

`/output/*` serves the lineup to players that only speak HTTP, such as M3U and XMLTV players. They send no headers, so these routes take `SOFA_OUTPUT_TOKEN` as `?token=` in the URL, or as a bearer. The API token is accepted as a bearer only, never in a query. A non-loopback server always has an output token, generated at the first start unless `SOFA_OUTPUT_TOKEN` sets one. On loopback without one, the routes answer `401 AUTH_REQUIRED` to anything but the API bearer, and with neither token configured (a loopback development server) they are open. [Security](security.md#credentials-and-the-routes-they-open) explains why the output token is separate, and [operations](operations.md#rotating-tokens) how to rotate it.

`GET /output/playlist.m3u` lists the enabled lineup in order, `audio/x-mpegurl`:

```text
#EXTM3U url-tvg="http://HOST/output/guide.xml?token=TOKEN" x-tvg-url="http://HOST/output/guide.xml?token=TOKEN"
#EXTINF:-1 tvg-id="CHANNEL_UUID" tvg-chno="5" tvg-name="Station" tvg-logo="https://..." group-title="News",Station
http://HOST/output/stream/CHANNEL_UUID.m3u8?token=TOKEN
```

`tvg-id` is the channel ID, the same value the XMLTV uses, so players match guide and channel without a name lookup. `HOST` is the `Host` header of the request, so the URLs work through a port mapping; `X-Forwarded-Proto: https` switches the scheme. The stream suffix follows the protocol of the source the channel would play now: `.m3u8` for HLS, `.ts` for MPEG-TS and for a channel without an eligible source, which lists but fails at play time. After a source's protocol is detected (a probe or a fetch), the suffix changes on the player's next playlist refresh.

For a player that takes one stream format for the whole playlist, such as Channels DVR, `?format=ts` lists every stream as `.ts` with `&format=ts`, and such a stream URL serves MPEG-TS whatever the source: an HLS source is remuxed through FFmpeg in the request, as the [HDHomeRun emulation](#hdhomerun-emulation) does, and the session ends when the player disconnects.

Two attributes are for Channels DVR, and other players ignore them: a station with a `gracenoteId` in the lineup policy carries `tvc-guide-stationid="ID"`, so Channels takes its guide from Gracenote, and any other channel with a logo carries `tvc-guide-art` with the logo URL, the artwork Channels shows for its programs.

`GET /output/guide.xml` is XMLTV: one `<channel id="CHANNEL_UUID">` per enabled channel with the name and the number as `<display-name>` values and the logo as `<icon>`, then `<programme>` elements from three hours ago to `days` ahead (`SOFA_XMLTV_DAYS`, default 3, or the value saved in the portal's [runtime settings](admin.md#settings); `?days=1`-`14` overrides). Times are `YYYYMMDDHHMMSS +0000`.

Each program carries the metadata its guide source provides, in XMLTV order: `<title>`, `<sub-title>`, `<desc>`, `<date>`, `<category>`, `<icon>`, every `<episode-num>` the source gave with its own `system` (`xmltv_ns`, `onscreen`, `dd_progid` and others), `<previously-shown>` with its `start` when known, `<new/>` and `<rating>` with its `system` when known. `<title>`, `<sub-title>`, `<desc>` and the source's categories carry `lang` when the source named the title's language. `<icon>` is the source's own image URL, not one served from this server. A source XMLTV keeps at most 8 categories, 8 episode numbers and the first rating per program.

The categories are the source's own, with any kind Plex sorts by (`Movie`, `Sports`, `News`, `Children`, `Series`) first. A program whose source gave none carries its channel's group instead, with `lang="en"`: first the kind Plex sorts by, then the group's own name when it differs. The kind is `Movie` for Movies, `Sports` for Sports, `News` for News, Local and Local News (US), `Children` for Kids & Family, and `Series` for anything else.

A program that is not a movie and has no episode numbering from its source gets `<episode-num system="original-air-date">` with its start time in UTC, `YYYY-MM-DD HH:MM:SS`, since Plex files a program without episode numbering as a movie. A program counts as a movie when its channel is in Movies or a category is `Movie`. A channel with no program in the window, guide mapping or not, gets hourly placeholder programs titled with its name, with the group's categories and that air date, so Plex lists and records it like the rest. The body is streamed.

`tvg-logo` and `<icon>` name the channel's logo at `http://HOST/logos/KEY`, never the logo's source host.

`GET /output/stream/{channelId}.m3u8|.ts` opens a watch session for the channel and plays it. An HLS source answers `302` to the session's playlist URL under `/media/sessions/`; the player follows it and fetches playlists, segments and keys through the relay. An MPEG-TS source streams `video/mp2t` in this response through the fan-out. Players never renew, so these sessions live differently from API sessions. A request in flight holds the session, and it is released 60 seconds after its last media request (the player stopped) or when a TS player disconnects. Two players on the same HLS channel share one session; each TS request is its own. At `SOFA_MAX_SESSIONS` the oldest preview session yields first, then an idle player session (a zap left behind), and otherwise the request answers `429 CAPACITY_EXHAUSTED`.

When an unprobed source's descriptor says MPEG-TS and the body is HLS, the request switches to the redirect itself instead of answering `410`. The reverse, an HLS descriptor with an MPEG-TS body, reaches the player as `410` on the media URL once; after that the stored protocol is right. A disabled or unknown channel answers `404 CHANNEL_NOT_FOUND`, and a channel with no eligible source `503 SOURCE_UNAVAILABLE`.

`GET /output/recordings.ics` is the scheduled and running recordings as an iCalendar feed for a calendar app to subscribe to, `text/calendar`: one event per recording over the window the DVR records, paddings included, with the channel as its location. It takes the output token or a `read` token as `?token=`, or either or the API token as a bearer; the [recordings page](recordings.md#subscribe-in-a-calendar) has the setup.

## Logos

Every channel logo is served from this server as `/logos/KEY`, where `KEY` is the lowercase hex SHA-256 of the logo's source URL. A channel's logo source is its entry's own `logo`, else the first of its sources' `tvg-logo` values, else the mapped guide channel's `<icon>`. `/api/v1/channels` names the path alone; the M3U `tvg-logo` and the XMLTV `<icon>` name it with the request's `Host`, since players fetch them as absolute URLs. The route takes no token: players, Plex and browsers fetch logos with no credentials, and a key is only ever the hash of a URL the lineup names, so nothing else is fetched or served through it.

A cached logo answers `200` with its type (PNG, JPEG, WebP, GIF or SVG, at most 2 MiB), `ETag` (the content hash; `If-None-Match` answers `304`), `Cache-Control: public, max-age=86400` and `X-Content-Type-Options: nosniff`. An SVG carries a `Content-Security-Policy` that forbids scripts. An unknown key, a refused source or a source that has never answered an image answers `404 RESOURCE_NOT_FOUND`; the listing keeps the URL, the player shows its placeholder, and the server tries the source again an hour later. A logo is refreshed a week after its last fetch, and a refresh that fails keeps the stored file. A changed source URL is a new key, so the outputs switch at once. [Architecture](architecture.md#logos) describes how the server fetches, checks, scales and stores logos.

The admin API (`/api/v1/admin/lineup`, `/api/v1/admin/candidates`) keeps the source URLs, which admins edit.

## HDHomeRun emulation

The root serves what a Plex server reads from an HDHomeRun tuner, unless `SOFA_HDHR_ENABLED=false`. Nothing here takes a token, because Plex sends none. Only the addresses in `SOFA_HDHR_ALLOW` and those allowed at run time on the portal's Plex page or through the [admin API](#admin-api) (client IPs or CIDR blocks) get in; everyone else gets `403`, and the server keeps the last ten refused addresses so the portal can offer to allow them. With neither list, a loopback bind admits every address and any other bind admits none. Absolute URLs derive from the request's `Host` header like the player outputs.

| Path | Body |
| --- | --- |
| `/discover.json` | `FriendlyName` (`SOFA_HDHR_NAME`), `Manufacturer` `Silicondust`, `ManufacturerURL` `https://www.silicondust.com/`, `ModelNumber` `HDTC-2US`, `FirmwareName` `hdhomeruntc_atsc`, `FirmwareVersion`, `DeviceID`, `DeviceAuth`, `BaseURL`, `LineupURL`, `TunerCount` (`SOFA_HDHR_TUNERS`) |
| `/lineup.json` | One `{"GuideNumber", "GuideName", "URL"}` per enabled lineup entry in lineup order, with the entry number, name and `BASE/auto/vNUMBER`; a second entry with the same number is left out |
| `/lineup_status.json` | `{"ScanInProgress":0,"ScanPossible":0,"Source":"Cable","SourceList":["Cable"]}` |
| `/lineup.post` | `200` with no body; there is nothing to scan |
| `/device.xml` | UPnP root description with the same name, model, `DeviceID` as `serialNumber`, and a `UDN` |
| `/auto/v{number}` | `video/mp2t` for the enabled entry with that number; `404` when none |

The first device, on `SOFA_PORT`, has `SOFA_HDHR_DEVICE_ID` as its `DeviceID`, or eight hex digits derived from the server identity, and the server identity as its `UDN`. Each device on a port from `SOFA_HDHR_EXTRA_PORTS` is named `SOFA_HDHR_NAME 2`, `SOFA_HDHR_NAME 3` and so on. Its `DeviceID` is derived from the first device's and its position, and its `UDN` is a UUID derived from the server identity and its position. All of them stay the same across restarts.

`/auto/v{number}` opens an external session like a player stream URL. An MPEG-TS source goes through the fan-out. An HLS source is remuxed to MPEG-TS with stream copy: one video stream (the highest resolution) and one audio stream (the most channels), with subtitle and data tracks dropped, so CEA-608 captions inside the video pass through. The stream ends when Plex disconnects or the session ends. While `SOFA_HDHR_TUNERS` streams are open, or when the session cap or the source refuses one, the request answers `503` with `X-HDHomeRun-Error: 805 All Tuners In Use`, which Plex reports as a busy tuner. A protocol detected on the first fetch of an MPEG-TS descriptor switches to the remux within the request. [Architecture](architecture.md#hdhomerun-emulation) has the FFmpeg command and how the server manages its processes.

## Admin API

JSON only, under `/api/v1/admin`. With `SOFA_ADMIN_TOKEN` set, send that token; otherwise the API token applies. No response carries a stream URL.

| Method | Path | Response or action |
| --- | --- | --- |
| GET | `/api/v1/admin/status` | One summary of the server, described [below](#server-status) |
| GET | `/api/v1/admin/candidates?q=&provider=&limit=&offset=` | Candidates with their sources (`id`, `protocol`, `status`, `manuallyDisabled`) and the lineup entries using them; `q` matches name, exact number or `tvg-id`, case-insensitively; `limit` 1-1000 (default 100) |
| GET | `/api/v1/admin/sessions` | Live playback sessions: `id`, `channelId`, `intent`, `external` (an M3U or HDHomeRun player), `busy` (a media request in flight), `providerId` of the source playing, `expiresAt`, `startedAt`, `client` (the player's address, once it made a request), `bytes` sent to the player so far, `offeringId` of the source playing, `bitrateKbps` (the rate over the last 10 seconds or so), `stalls` (media requests the upstream refused or dropped before anything was answered), `segmentsServed` (HLS segments relayed) and `sourceSwitches` (moves to another source, on a failure or by hand). An empty list means nothing is playing |
| POST | `/api/v1/admin/sessions/{id}/switch-source` | Move a live session to its channel's next source, see [source selection](source-health.md#source-selection); answers the session, or `409 NO_OTHER_SOURCE` |
| GET | `/api/v1/admin/guides` | Guide import status: `lastSuccessAt`, `errorCode`, `channels`, retained `programs` |
| GET | `/api/v1/admin/guides/{id}/channels?q=&limit=` | XMLTV channels of a guide (`channelId`, `displayName`, `icon`) |
| GET | `/api/v1/admin/lineup` | Every entry in lineup order |
| POST | `/api/v1/admin/lineup` | Create an entry; returns 201 |
| GET | `/api/v1/admin/lineup/{id}` | One entry |
| PATCH | `/api/v1/admin/lineup/{id}` | Update an entry; with a policy, writes the station override instead and applies (`If-Match` optional) |
| DELETE | `/api/v1/admin/lineup/{id}` | Remove an entry; returns 204. With a policy, writes `enabled: false` into the station override instead |
| PUT | `/api/v1/admin/lineup/order` | Reorder: `{"ids": [...]}` listing every entry once; returns the lineup |
| POST | `/api/v1/admin/lineup/adopt` | `{"providerId": "...", "enabled": true}` adds every channel of the provider whose sources no entry uses; returns `added` and `skipped` |
| GET | `/api/v1/admin/policy` | The [lineup policy](lineup-policy.md) the server runs with (`SOFA_POLICY`, or `policy.json` in the data directory), as `policy` with `revision` (the file's SHA-256, also the `ETag`) and `path`; 404 `POLICY_NOT_CONFIGURED` without one |
| PUT | `/api/v1/admin/policy` | Replace the policy with the body, up to 4 MiB; `If-Match: <revision>` required (428 `PRECONDITION_REQUIRED` without it, 409 `POLICY_CHANGED` when stale or the file changed on disk, 400 `INVALID_POLICY` with every error). Writes atomically, applies, and returns `document` and `apply` |
| GET | `/api/v1/admin/policy/preview` | What a run would change against the served lineup; writes nothing. Fields are [below](#policy-results) |
| GET | `/api/v1/admin/source-file` | The source file as written: `providers` and `guides` as [the source file](live-tv.md#providers) holds them, `${NAME}` references unexpanded; an empty document without a file |
| PUT | `/api/v1/admin/source-file` | Replace the source file with the body and restart. Validated the way the start validates it (400 `INVALID_SOURCES` with the reason, nothing written), written atomically to `SOFA_SOURCES` or `sources.json` in the data directory; answers `{"path", "restarting": true, "policyPath"}` and ends the process once the response is sent, so the container's restart policy starts it again. `?starterPolicy=true` also writes the [starter policy](starter-lineup.md) for the body's providers before that one restart, and `policyPath` names it; it answers `409 POLICY_EXISTS` when a policy is configured and `409 NO_SOURCES` without providers |
| POST | `/api/v1/admin/policy/starter` | Write the [starter policy](starter-lineup.md) for the configured providers and restart; 201 with `{"path", "restarting"}`. 409 `POLICY_EXISTS` when a policy is configured or the file exists, 409 `NO_SOURCES` with no providers |
| PUT | `/api/v1/admin/tmdb-token` | `{"token": "..."}`: asks TMDB whether it takes the token, stores it as `tmdbToken` in `secrets.json` in the data directory and restarts, answering a `SetupResult` with that path. 400 `INVALID_REQUEST` for a value under 32 characters or with spaces, 400 `TMDB_REJECTED` when TMDB refuses it, 502 `TMDB_UNREACHABLE` when TMDB cannot be asked, 409 `TMDB_FROM_ENVIRONMENT` when `SOFA_TMDB_TOKEN` sets it, 500 `SECRETS_UNWRITABLE` when `secrets.json` cannot be read or written; nothing is written then |
| POST | `/api/v1/admin/policy/apply` | Run the policy now: writes the lineup when it differs, logs each change, and writes assigned numbers and learned aliases back to the file. Fields are [below](#policy-results) |
| GET | `/api/v1/admin/policy/findings` | Open findings of a run over the current candidates. Fields are [below](#policy-results) |
| GET | `/api/v1/admin/policy/changes?limit=` | What policy runs changed, newest first, as `{"id", "appliedAt", "change"}`: `change` has the station `key`, `entryId`, `entryName`, `field`, `before`, `after` and the run's cause in `reason`. `limit` 1-1000 (default 100); the server keeps the newest 10,000 |
| GET | `/api/v1/admin/policy/guide-matches` | Guide channels proposed by name for each station no guide lists, with a `confidence` from 0 to 1 ([guide matches](lineup-policy.md#guide-matches)) |
| POST | `/api/v1/admin/policy/guide-matches/accept` | `{"station", "guideId", "channelId"}`: writes the channel as the station's guide and applies; returns the apply result. 404 `GUIDE_CHANNEL_NOT_FOUND` |
| POST | `/api/v1/admin/policy/guide-matches/accept-all` | `{"minConfidence"}` (optional): accepts each station's best proposal at that confidence or more, else at `guideMatches.acceptAbove`, and applies once; returns `{"accepted", "applied"}` |
| POST | `/api/v1/admin/policy/guide-matches/dismiss` | `{"station", "guideId", "channelId"}`: never proposes the channel for the station again, and applies |
| GET | `/api/v1/admin/stations/{key}` | One station key: the station the policy builds, its pins, and every candidate that names the key or joins it, each `taken`, `skipped` or `claimed` with a `reason` |
| GET | `/api/v1/admin/plex` | The last [Plex sync](live-tv.md#keep-plex-in-step): `syncMinutes`, `lastSyncAt`, `lastCause` (`startup`, `policy`, `interval` or `api`), `lastError`, `dvrs` whose guide it reloaded, `devices` (`deviceId`, `device`, `plexKey`, `dvr`, `mapped`, `added`), `missingDevices` and `findings`. It survives a restart; before any sync, `lastSyncAt` is null. 404 `PLEX_NOT_CONFIGURED` when `SOFA_PLEX_URL` is unset |
| POST | `/api/v1/admin/plex/sync` | Sync Plex now; returns the same status. A failure is reported in `lastError`, not as an error response |
| GET | `/api/v1/admin/hdhr/access` | Who may use the tuners: `enabled`, `open` (every address is admitted), `configured` (from `SOFA_HDHR_ALLOW`), `allowed` (from the portal or this API) and `refused`, up to ten addresses refused since the start that are still refused, newest first, each with `address`, `lastAt` and the `path` it asked for |
| PUT | `/api/v1/admin/hdhr/access` | `{"allowed": ["PLEX_SERVER_IP"]}` replaces the addresses allowed at run time, at most 64 IP literals or CIDR blocks, and returns the same status. It applies at once, without a restart, and is kept in the database; `SOFA_HDHR_ALLOW` still applies beside it. 400 `INVALID_REQUEST` names an entry that does not parse, and nothing changes |
| GET | `/api/v1/admin/viewers` | Every client that paired or saved preferences, most recently seen first: `clientId`, `name`, `platform`, `firstSeenAt`, `lastSeenAt`, `preferences` (null until the client saves some) and `ownToken` (false for a client on `SOFA_API_TOKEN`) |
| DELETE | `/api/v1/admin/viewers/{clientId}` | Forget a client and its preferences and revoke its token; returns 204. A client on `SOFA_API_TOKEN` keeps working and reappears on its next save |
| GET | `/api/v1/admin/tokens` | The [tokens](#tokens) the server issued, oldest first and without secrets: `id`, `label`, `scope` (`api`, `read` or `hook`), `clientId` (for a device's token), `createdAt` and `lastUsedAt` (to the minute) |
| POST | `/api/v1/admin/tokens` | Make a token from `{"label", "scope"}`, `scope` `read` or `hook`; 201 with `{"token", "secret"}`, the only time the secret is shown. 400 for an empty label or `api`, which only pairing issues |
| DELETE | `/api/v1/admin/tokens/{id}` | Revoke a token; 204, or 404 `TOKEN_NOT_FOUND` |
| POST | `/api/v1/admin/pairing-codes` | Issue a [pairing code](#pair-a-client): `{"code", "expiresAt"}`, 201. It replaces the live code |
| GET | `/api/v1/admin/libraries` | Every [media library](libraries.md): `id`, `name`, `kind` (`movies`, `shows` or `videos`), `path`, `createdAt`, `items` (present items by kind: `movie`, `show`, `season`, `episode`, `video`) and the last scan's `scannedAt`, `scanOk` and `scanNote` |
| GET | `/api/v1/admin/libraries/folders` | `{"roots", "folders"}`: `SOFA_MEDIA_DIR` and the folders under it, two levels down, that no library uses |
| POST | `/api/v1/admin/libraries` | Add a library from `{"name", "kind", "path"}`; 201 with the library, and its first scan starts. 400 when the folder is outside `SOFA_MEDIA_DIR`, links outside it or does not exist; 409 `LIBRARY_EXISTS` when another library uses it |
| GET, PATCH, DELETE | `/api/v1/admin/libraries/{id}` | One library; rename it with `{"name"}`; remove it (204), forgetting its items and their watch progress but never touching its files |
| POST | `/api/v1/admin/libraries/scan` | Queue a scan of the library whose folder holds `{"path"}`, a file or folder, or of every library inside it when it names a folder above them; 202 with `{"libraries"}`, their ids, at once. The path is mapped through `SOFA_LIBRARY_PATH_MAP` first. 404 `LIBRARY_NOT_FOUND` when no library holds it |
| POST | `/api/v1/admin/libraries/{id}/scan` | Scan now and answer when done: `files`, `added`, `updated`, `missing`, `removed`, and `unavailable` when the folder was missing or empty, in which case nothing changed |

While a lineup policy is in use, `POST /lineup`, `PUT /lineup/order` and `POST /lineup/adopt` answer `410 Gone` with code `POLICY_MANAGED`; see [lineup policy](lineup-policy.md#api). Without it, the `/policy/*` routes answer `404 POLICY_NOT_CONFIGURED`.

### Server status

`GET /api/v1/admin/status` answers one JSON document, defined in the [server's status model](../server/src/main/kotlin/dev/jamesrich/sofaking/backend/api/Status.kt):

| Field | Contents |
| --- | --- |
| `revision` | The commit the image was built from, from `SOFA_REVISION`; `unknown` outside the image |
| `startedAt`, `uptimeSeconds` | When the server started, and the seconds since |
| `schemaVersion` | The newest database migration applied |
| `channels` | Lineup entries: `total`, `enabled`, and `eligible`, the enabled ones with a source eligible for playback |
| `sources` | Sources: `total`, `byStatus` with a count for each health status, and `manuallyDisabled` |
| `providers`, `guides` | `total`, `pending` (waiting for a first import), `lastImportAt` (the newest successful import), and `failing`, one entry per failed import with `id`, `errorCode` and `lastSuccessAt` |
| `policy` | With a lineup policy: its `revision`, and `lastRunAt` and `lastRunChanged` of the last apply that built the lineup. Null without one |
| `plex` | With Plex sync: `lastSyncAt`, `mapped` (channels mapped across all devices) and `lastError`. Null without it |
| `activeSessions` | Live playback sessions |

```sh
curl --fail -H "Authorization: Bearer $SOFA_ADMIN_TOKEN" http://SERVER_HOST:9193/api/v1/admin/status
```

### Policy results

The [server's policy models](../server/src/main/kotlin/dev/jamesrich/sofaking/backend/api/Policy.kt) define these responses.

| Response | Fields |
| --- | --- |
| Preview | `generatedAt`, `revision`, `stations`, `enabledStations`, `added`, `removed`, `changes`, `merges`, `splits`, `counts`, `findings`, `suggestedAliases`, `learned`, `devicesNeeded`, `devicesConfigured`, `unmappedCategories` |
| Apply | `appliedAt`, `revision`, `changed`, `stations`, `enabledStations`, `changes`, `assigned`, `findings`, `suggestedAliases`, `learned`, `devicesNeeded`, `devicesConfigured`, `unmappedCategories` |
| Findings | `generatedAt`, `revision`, `findings`, `suggestedAliases`, `unmappedCategories` |

- `stations` and `enabledStations` count the stations the run builds and those it turns on.
- In a preview, `added` lists the new station keys. `removed` lists the entries the run drops, each as `{"key", "entryId", "name"}`. `changes` lists each field that differs, as `{"key", "entryId", "field", "before", "after"}`. `merges` and `splits` list entries that join one station or spread over several, as `{"keys", "entryIds"}`, and `counts` maps a name to a count.
- In an apply, `changed` says whether the lineup was written, `changes` is the number of fields that changed, and `assigned` maps each newly numbered station key to its number.
- `learned` maps the aliases the run found from matching schedules, from key to station key.
- `devicesNeeded` and `devicesConfigured` are set with HDHomeRun range devices, and null otherwise.
- `unmappedCategories` lists the raw categories no group mapping names, each as `{"provider", "category", "stations", "example"}`.

An entry has `id`, `position`, `number`, `name`, `group`, `logo`, `enabled`, `guide` (`{"guideId", "channelId"}` or null), `offerings`, `guideStatus` and `createdBy` (`policy` for an entry the policy built, else `manual`). `offerings` lists the entry's sources in failover order, each with `id`, `providerId`, `channelName`, `protocol`, `status` and `manuallyDisabled`. `status` is a health state, or `WITHDRAWN` when no provider lists the source now. While the policy holds a station off for want of an eligible source, the entry also has `autoDisabledAt` and `autoDisabledReason` (`unavailable`). With a policy, `gracenoteId` is the station's [Gracenote station id](lineup-policy.md#station-overrides).

Create from a candidate, copying its number, name, logo, group, sources and (when its `tvg-id` is listed by a configured guide) guide mapping:

```sh
curl --fail -H "Authorization: Bearer $SOFA_ADMIN_TOKEN" http://SERVER_HOST:9193/api/v1/admin/lineup \
  -H 'Content-Type: application/json' \
  --data '{"channelId": "CANDIDATE_ID"}'
```

Any field in the body overrides the copied value: `id` (a UUID), `number`, `name`, `group`, `logo`, `enabled` (true when absent), `offerings` (source IDs in failover order), `guide`, `position` (insertion index). Without `channelId`, `number` and `name` are required.

Update fields with `PATCH`; absent fields keep their value, `clearGroup`, `clearLogo` and `clearGuide` remove optional values, `position` moves the entry. With a policy, `gracenoteId` sets the station's Gracenote station id and `clearGracenoteId` removes it; without one they answer `400`. Sending `enabled` clears `autoDisabledAt` and `autoDisabledReason`.

```sh
curl --fail -X PATCH -H "Authorization: Bearer $SOFA_ADMIN_TOKEN" http://SERVER_HOST:9193/api/v1/admin/lineup/ENTRY_ID \
  -H 'Content-Type: application/json' \
  --data '{"number": "501", "offerings": ["SOURCE_ID", "FALLBACK_SOURCE_ID"], "guide": {"guideId": "gracenote", "channelId": "XMLTV_ID"}}'
```

Validation: `number` 1-32 characters without spaces, `name` 1-256, `group` 1-128, `logo` an HTTP(S) URL, at most 64 sources per entry, every source ID known to a configured provider, `guideId` a configured guide. An active session keeps its current source until that source stops being selectable or the session ends.

## Admin portal routes

The [admin portal](admin.md) is HTML under `/admin`. Every route but the sign-in page, the sign-in form, the icon and the stylesheet needs the `sofa_admin` session cookie. A page request without one is redirected to `/admin`, and an action answers `401`. Every `POST` but the sign-in also needs the session's CSRF token, as the form field the pages include or the `X-CSRF-Token` header, and answers `403` without it.

| Method | Path | Page or action |
| --- | --- | --- |
| GET | `/admin` | The sign-in page, or the home page once signed in |
| POST | `/admin/login` | Sign in with the form field `token`. 10 attempts a minute per client address, then `429` |
| POST | `/admin/logout` | Sign out |
| GET | `/admin/static/icon.svg`, `/admin/static/admin.css`, `/admin/static/htmx.min.js` | The portal's icon and stylesheet (public) and htmx (signed in) |
| GET | `/admin/lineup` | Channels, filtered and sorted by the query (`q`, `group`, `state`, `guide`, `required`, `override`, `changed`, `sort`, `dir`, `page`) |
| GET | `/admin/lineup/{id}` | One channel |
| POST | `/admin/lineup/{id}/visibility`, `/admin/lineup/{id}/edit` | Hide or show a channel; rename, renumber or regroup it |
| POST | `/admin/lineup/{id}/sources/{offeringId}/disable`, `/admin/lineup/{id}/sources/{offeringId}/probe` | Disable (`disabled=true`, after `confirm=yes`) or enable a source; queue a probe of it now |
| POST | `/admin/lineup/bulk` | Hide, show or regroup the selected channels (`op` of `hide`, `show` or `group`, `id` for each, `target` for the group), after `confirm=yes` |
| POST | `/admin/sessions/{id}/stop` | End a playback session, after `confirm=yes` |
| POST | `/admin/sessions/{id}/switch` | Move a playback session to its channel's next source |
| GET | `/admin/merges` | Suggested and learned aliases |
| POST | `/admin/merges/accept`, `/admin/merges/dismiss`, `/admin/merges/never` | Accept a suggestion, dismiss it, or keep a learned pair apart |
| GET | `/admin/required` | Required stations |
| GET, POST | `/admin/providers`, `/admin/providers/edit` | Provider entries and their edits |
| GET, POST | `/admin/groups`, `/admin/groups/edit` | Groups and their edits |
| GET, POST | `/admin/plex`, `/admin/plex/reveal` | Plex and the device addresses; reveal the output token |
| POST | `/admin/plex/allow`, `/admin/plex/disallow` | Allow an address onto the tuners, or remove one the portal allowed |
| GET | `/admin/viewers` | The devices that paired or saved preferences, and the live pairing code with its QR code |
| POST | `/admin/viewers/pair`, `/admin/viewers/cancel` | Issue a pairing code (replacing the live one); cancel it |
| POST | `/admin/viewers/{clientId}/forget` | Forget a device and its preferences, and revoke its token |
| POST | `/admin/settings/tokens/create`, `/admin/settings/tokens/{id}/revoke` | Make a `read` or `hook` token (`label`, `scope`), shown once; revoke a token |
| GET | `/admin/library` | The media libraries and the form that adds one |
| POST | `/admin/library` | Add a library (`name`, `kind`, `path`) |
| POST | `/admin/library/{id}/scan`, `/admin/library/{id}/rename`, `/admin/library/{id}/remove` | Queue a scan; rename (`name`); remove, after `confirm=yes` |
| POST | `/admin/library/tmdb` | Check and save the TMDB token, then restart |
| GET | `/admin/settings?advanced=&changes=` | Settings; `advanced=1` opens Advanced, and `changes` sets how many of the newest policy changes it lists, 50 by default |
| POST | `/admin/settings/reveal-api-token` | Reveal the API token |
| POST | `/admin/settings/runtime`, `/admin/settings/runtime/check` | Save the runtime settings (`op` of `save` or `reset`); say whether the form holds a change |
| POST | `/admin/policy/apply` | Apply the lineup policy now |
| POST | `/admin/sources/services` | Save the services form (`svc` for each service on, `opt.SERVICE.KEY` for its options), after `confirm=yes`; writes the source file and restarts |
| POST | `/admin/sources/playlist`, `/admin/sources/remove` | Add an M3U playlist (`id`, `url`, `guide`) or remove a provider (`id`), after `confirm=yes`; each writes the source file and restarts |
| POST | `/admin/policy/starter` | Write the starter policy, after `confirm=yes`, and restart |
| POST | `/admin/attention/ignore`, `/admin/attention/show` | Ignore an overview item (`key`, `fingerprint`) until its text changes; show it again (`key`) |
| POST | `/admin/theme` | Pin the color scheme: `theme` of `light`, `dark` or `auto`, then back to the portal path in `back` |
| GET | `/admin/system/jobs` | Background jobs with their last run and next due time |
| POST | `/admin/system/jobs/{id}/run` | Run a job now |
| GET | `/admin/system/events?level=&q=` | The newest 500 log lines at INFO and above; `level` of `warn` or `error` narrows them, `q` matches the logger or message |
| GET | `/admin/system/about` | Whether an update is available, the server, the status and the support snapshot |
| POST | `/admin/system/update/check`, `/admin/system/update/request` | Check the registry for a newer image now; ask the host's updater to update, after `confirm=yes`, by writing `update-requested` in the data directory |
| GET | `/admin/system/snapshot.json` | The support snapshot as a JSON download |
| GET | `/admin/api`, `/admin/api/openapi.json` | The API reference, and the OpenAPI 3.1 document it is rendered from |

## Discovery

Off loopback, unless `SOFA_MDNS=false`, the server advertises itself over mDNS (DNS-SD) on the LAN, so clients find it without an address:

| Record | Value |
| --- | --- |
| Service type | `_sofaking._tcp` (`Wire.Discovery.SERVICE_TYPE`) |
| Instance name | `SOFA_HDHR_NAME`, `Sofa King` by default; a name another server already holds gets a number added |
| Host | `sofa-king.local` on each advertised address, with a number added when another device holds the name |
| Port | `SOFA_PORT`, where every route above answers |
| TXT `version` | The server's product version, `SOFA_VERSION`, or `unknown` outside the image |

A client builds the server address as `http://HOST:PORT` from the resolved address and port. The advertisement says nothing about tokens: a client still [pairs](#pair-a-client) or takes the API token. The server withdraws it when it stops.

## Shared types

The live TV, recording and library requests and responses above (`/api/v1/bootstrap`, `/api/v1/channels`, `/api/v1/guide/query`, `/api/v1/playback-sessions`, `/api/v1/recordings`, `/api/v1/recording-rules`, `/api/v1/libraries`, `/api/v1/library`, `/api/v1/vod-sessions`) and the error body are Kotlin types in the [contract](../../contract/README.md) build, which the server and the Sofa King app both compile against. `Wire` in the same package names the intents, guide statuses, error codes, guide limits, library item kinds and the discovery service type clients act on.

The server keeps clients older and newer than itself working: a response keeps every field of the frozen fixtures in `contract/src/jvmTest/resources/wire`, a field no client reads has a default, and the guide query and playback session requests ignore fields the server does not know. Admin requests reject unknown fields.

## Errors

Errors contain `code`, `message` and `retryable`:

```json
{
  "code": "SOURCE_UNAVAILABLE",
  "message": "No eligible source",
  "retryable": true
}
```

| Status | Codes |
| --- | --- |
| 400 | `INVALID_REQUEST` (a malformed body or path), `INVALID_QUERY` (a guide query or `days` out of range), `INVALID_POLICY` (a policy write that fails validation, with every error in `message`), `INVALID_SOURCES` (a source file write that fails validation) |
| 401 | `AUTH_REQUIRED` |
| 403 | `PAIRING_INVALID` (a wrong, spent or expired pairing code), `LOOPBACK_ONLY` (a server with no API token answers only requests from its own machine, see [security](security.md)) |
| 404 | `CHANNEL_NOT_FOUND`, `SOURCE_NOT_FOUND`, `LINEUP_ENTRY_NOT_FOUND`, `GUIDE_NOT_FOUND`, `PROVIDER_NOT_FOUND`, `RESOURCE_NOT_FOUND` (an unknown route, logo or media resource), `POLICY_NOT_CONFIGURED`, `PLEX_NOT_CONFIGURED`, `VIEWER_NOT_FOUND`, `TOKEN_NOT_FOUND`, `PROGRAM_NOT_FOUND`, `RECORDING_NOT_FOUND`, `RULE_NOT_FOUND`, `RECORDING_MISSING` (a recording whose file is gone), `LIBRARY_NOT_FOUND`, `LIBRARIES_NOT_AVAILABLE`, `ITEM_NOT_FOUND`, `ITEM_MISSING` (a library item whose file is gone) |
| 409 | `RESYNC_REQUIRED`, `IDEMPOTENCY_CONFLICT`, `LINEUP_ENTRY_EXISTS`, `POLICY_CHANGED` (a stale `If-Match` or a policy file changed on disk), `STATION_CONFLICT` (a merge of two stations that both have overrides), `POLICY_EXISTS`, `NO_SOURCES` (a starter policy over an existing policy or no providers), `PROGRAM_ENDED`, `RECORDING_NOT_READY` (nothing written yet; retryable), `LIBRARY_EXISTS` |
| 410 | `SESSION_EXPIRED`, `STREAM_CHANGED`, `POLICY_MANAGED` |
| 413 | `REQUEST_TOO_LARGE`, answered before the token is checked |
| 422 | `FORMAT_UNSUPPORTED` (an HLS playlist with variables, content steering or a non-HTTP resource) |
| 428 | `PRECONDITION_REQUIRED` (a policy write without `If-Match`) |
| 429 | `CAPACITY_EXHAUSTED` (no session slot is free or can be freed, or a session already has 12 media requests in flight) |
| 500 | `INTERNAL_ERROR` |
| 502 | `UPSTREAM_UNAVAILABLE`, `UPSTREAM_REJECTED`, `INVALID_MANIFEST` |
| 503 | `NOT_READY`, `SOURCE_UNAVAILABLE`, `SETUP_UNAVAILABLE`, `CAPACITY_EXHAUSTED` (every library playback slot, `SOFA_MAX_TRANSCODES`, is converting), `TRANSCODER_UNAVAILABLE` (FFmpeg could not start) |

The admin portal shows its errors on its pages. A sign-in over its limit answers `429 Too Many Requests` with no error body.
