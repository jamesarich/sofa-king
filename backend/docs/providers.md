# Native providers

A native provider is a client for one free streaming service built into the server. It is configured in the source file as `{"type": "native", "service": "NAME"}`, next to the M3U providers. It lists the service's channels as candidates, carries the service's guide, and resolves a channel to a playable stream URL when a session, a probe or the HDHomeRun remux needs one. Everything downstream (lineup, health, failover, logos, player outputs, Plex) works as it does for an M3U provider.

The clients are unofficial. They call the same public endpoints each service's own web or TV client calls, with no account and no credentials, and they stop working whenever the service changes them. Using them may be against a service's terms of service. The clients send ad-insertion identifiers that are random per install, not a device's identifiers.

The server supports 17 streaming services, the free news feeds of a dozen broadcasters (`direct`) and HDHomeRun tuners. Each one is listed below with its endpoints and its known fragility.

## Configuration

The portal's Sources page writes this file: every service is a switch, with its options as fields ([setup](admin.md#setup)). By hand, it is:

```json
{
  "providers": [
    { "id": "whale", "type": "native", "service": "whale" },
    { "id": "vizio", "type": "native", "service": "vizio" },
    { "id": "lg", "type": "native", "service": "lg" },
    { "id": "freelivesports", "type": "native", "service": "freelivesports" },
    { "id": "hallmark", "type": "native", "service": "hallmark" },
    { "id": "ballysports", "type": "native", "service": "ballysports" },
    { "id": "adultswim", "type": "native", "service": "adultswim" },
    { "id": "pbs", "type": "native", "service": "pbs", "options": { "zip": "ZIP_CODE" } },
    { "id": "samsung", "type": "native", "service": "samsung", "options": { "region": "us" } },
    { "id": "plex", "type": "native", "service": "plex" },
    { "id": "stirr", "type": "native", "service": "stirr" },
    { "id": "tcl", "type": "native", "service": "tcl" },
    { "id": "roku", "type": "native", "service": "roku" },
    { "id": "localnow", "type": "native", "service": "localnow" },
    { "id": "xumo", "type": "native", "service": "xumo" },
    { "id": "pluto", "type": "native", "service": "pluto" },
    { "id": "tubi", "type": "native", "service": "tubi" },
    { "id": "direct", "type": "native", "service": "direct" },
    { "id": "antenna", "type": "native", "service": "hdhomerun", "options": { "url": "http://HDHOMERUN_IP" } }
  ]
}
```

| Field | Default | Description |
| --- | --- | --- |
| `type` | `m3u` | `native` selects a service client; `playlistUrl` must then be absent |
| `service` | Required | One of `whale`, `vizio`, `lg`, `freelivesports`, `hallmark`, `ballysports`, `adultswim`, `pbs`, `samsung`, `plex`, `stirr`, `tcl`, `roku`, `localnow`, `xumo`, `pluto`, `tubi`, `direct`, `hdhomerun` |
| `options` | `{}` | Service options, string values: `zip` or `stations` for `pbs`, `region` for `samsung`, `dma` and `market` for `localnow`, `market` for `xumo`, `url` (required) and `transcode` for `hdhomerun` |
| `refreshSeconds` | `3600` | Channel list refresh for a native provider (900 for a playlist); minimum 60 |
| `guideUrl` | Service default | An XMLTV to use instead of the service's own guide; `samsung` defaults to the i.mjh.nz XMLTV for its region, `roku` to the i.mjh.nz Roku XMLTV |
| `probe` | `true` | Probes resolve the channel and qualify the resolved URL like any source. A lineup policy ignores it ([probing](lineup-policy.md#probing)) |

`headers`, `defaultProtocol`, `allowEmpty` and `renewableQuery` apply to playlists only. Every native source is HLS, except `hdhomerun`, whose sources are MPEG-TS. `direct` has no guide of its own; map its channels onto a configured XMLTV guide, as the [direct](#direct-broadcasters-own-news-feeds) section shows.

A native provider with a guide of its own appears in `/api/v1/admin/guides` under the provider ID, refreshed hourly. Its channel IDs are the service's own, and a lineup entry created from a candidate maps onto that guide automatically. The guide holds every channel in its index, but programs only for channels the lineup maps. A service whose guide is fetched per channel (Whale, Hallmark, Adult Swim, PBS, Plex, STIRR, Pluto TV) is asked only about mapped channels.

Programs carry the metadata the guide responses already hold, with no extra requests:

| Service | Program metadata beyond title and description |
| --- | --- |
| `pluto` | Episode name as sub-title, season and episode number, genre and sub-genre as categories, rating, poster; a `film` series is a movie |
| `plex` | Episode title as sub-title under the series title, season and episode number, genres, content rating, image, original air date or year, premieres as new; a `movie` item is a movie |
| `vizio` | Sub-title, season and episode number when positive |
| `pbs` | Episode title as sub-title |
| `adultswim` | Series name as title, episode name as sub-title |
| `xumo` | The Gracenote asset ID as `dd_progid` numbering; a movie or sports asset gets that category |
| `samsung`, `roku` | Whatever the XMLTV carries, read as for any XMLTV guide |

From the other services' guides the server reads titles, descriptions and times only.

## How a native source plays

A native source's URL is `svc://SERVICE/CHANNEL_ID`: stable, never fetched, and what the lineup refers to. When a session opens the channel, the relay asks the provider to resolve it. The provider fills the service's ad macros (device ID, session ID, cache buster, privacy defaults) or follows the service's redirect, and the relay fetches the master playlist with the headers the service's own client sends. The resolution is cached per source until a minute before it expires, and for at most six hours: an hour for macro-filled URLs, the token lifetime for tokens, and six hours for static URLs. Every session, probe and remux on that source shares it. Nothing a player sees names the service. Media playlists and segments are the relay's opaque session paths, as for any HLS source. An MPEG-TS service (`hdhomerun`) resolves to the stream the relay fans out between players, as for any MPEG-TS source.

A service that cannot answer (its API is down, or a token is refused twice) fails that one resolution. The session answers `502 UPSTREAM_UNAVAILABLE`, Plex sees a busy tuner, and a probe records provider evidence, which never strikes. A channel missing from the service's list is evidence against that source and strikes like a 404. One provider failing never affects another.

For each provider, the server keeps one random device ID and any session token in the `provider_state` table, so a restart neither re-identifies the install nor re-authenticates. Logs carry provider IDs, channel counts, failure classes and reason text, never tokens or URLs.

## Services

### whale (Whale TV+)

The web client at watch.whaletvplus.com, backed by the rlaxx API. An access token comes from `auth/access` with the web client's public key and lasts about 24 hours; the server stores it and reuses it for 23. The channel list is the category listing for `US`/`en`, with each channel once, under the first themed category as its group, with numbers and the white channel icon. Streams are Ottera, Amagi, MediaTailor and SoFast ad-stitched HLS templates. Their `[did]`, `[session_id]`, `[cachebuster]` and other `[...]` macros are filled per resolve, with unknown ones blanked, and requests carry the web client's `Origin` and `Referer`. The guide is the 7-day EPG endpoint, ten channels per request, for mapped channels only.

Fragility: the public API key is the web client's and changes with it. A 401 or 403 drops the stored token and retries once. Channel IDs are the service's numeric IDs.

### vizio (WatchFree+)

The SmartCast guide API, which needs no authentication. Channels with a `tokenUrl` (the NFL channel) need a device entitlement and are skipped. Stream URLs are clear HLS on Amagi and others, with `{ADID}`-style macros present percent-encoded in the payload. The known macros are filled with the anonymous mobile client's values and the install's device ID, and unknown ones are blanked. The guide is one bulk `airings` request for the next 24 hours, matched to channels by `airingsKey`.

Fragility: `FEATURED` channel IDs are rotating promo slots (tagged `featured` in the candidate's tags), so the program in them changes while the ID stays. The service has hundreds of channels, and their `channelNumber` values can collide with other providers until renumbered.

### lg (LG Channels)

One `schedulelist` document from the lgchannels.com web API, sent with the web client's device headers (`US`, `en`, `WEB`). It is served as base64 of a zlib stream under `text/plain`, and plain JSON is accepted too. It carries every category, channel, logo and number, the static HLS template with `[DEVICE_ID]`-style macros, and the next hours of programs, which is the whole guide.

Fragility: the guide horizon is short, hence the hourly refresh. The document encoding has changed before.

### freelivesports (FreeLiveSports.tv)

One public catalog document from the Unreel/POWR platform, listing every channel with its HLS template (`[DEVICE_ID]`, `[CB]` and privacy macros) and its embedded schedule. The catalog path carries the site's public key segment plus `__site=freelivesports&__source=web`. The bare `public/` path answers 403.

Fragility: the key segment is baked into the client and changes if the site re-keys. The `catalogUrl` option overrides it.

### hallmark (Hallmark+)

Four fixed channels (Non-Stop Hallmark Hits, Rom-Coms, Christmas and Mysteries) with static clear HLS on `crwn-*.akamaized.net`, numbered 9001 to 9004. The hallmarkplus.com catalog supplies logos and each channel's current content ID, found by its stable `channelIdentifier`. The per-channel schedule API covers 48 hours. An unreachable catalog still lists the channels, without logos, and fails only the guide.

Fragility: the content IDs in the catalog change, so only the identifiers are trusted. The CDN hosts are hard-coded.

### ballysports (Bally Sports free channels)

The Next.js channel page (`/channels/milb`, after its redirect) embeds React Server Components chunks with `channelData` (UUID, name, order, logo and public CDN URL) and `epgData`. Only channels on `*.channels.ballys.tv` are listed. Streams are static HLS.

Fragility: this parses a framework payload out of HTML, so a site redesign ends it.

### adultswim (Adult Swim marathons)

The streams page's `__NEXT_DATA__` state lists the marathon streams with a video ID and a marathon ID. The video API names each one's `/live/` HLS asset, looked up on first resolve and cached for six hours. The marathon schedule API carries the guide with episode names. Channels are numbered in page order.

Fragility: the page state shape and the video API fields. Some marathons are region-restricted and may not play. Segments are AES-128, and the CDN sometimes gzip-encodes the key, which the relay inflates before the player sees it.

### pbs (PBS member stations)

`zip` selects the member stations for a ZIP code's area at pbs.org. `stations` lists station IDs directly, comma-separated. Each station's `livestream_feeds` are listed only when they expose a clear `non_drm_url`. Feeds that carry only `drm_hls_url` never appear. Names have the form `PBS Create (KDIN)`, and IDs have the form `STATION_ID:PROFILE`. The guide is the station's livestream schedule for today and tomorrow in the station's time zone, matched by feed CID. Streams are `urs.pbs.org` redirects that the relay follows.

Fragility: which livestream feeds are clear is the station's decision, and it changes. The schedule API is the station's.

### samsung (Samsung TV Plus)

Matt Huisman's published mirrors (i.mjh.nz): the channel list `.channels.json.gz` by `region` (default `us`), with channels that have a `license_url` left out as DRM. The XMLTV `REGION.xml.gz` is the provider's implicit guide, through the ordinary guide pipeline and refreshed with the provider, hourly by default. Set `guideUrl` to override it. A resolve requests `jmp2.uk/stvp-ID` without following, takes its redirect target, fills `{PSID}`, `{TARGETOPT}` and `{US_PRIVACY}`, and blanks the other braces. The relay follows the remaining hop to the CDN.

Fragility: a third party's mirror and redirector. Both can lag or go away. Some targets are CMAF with DRM despite having no `license_url`, and they do not decode.

### plex (Plex free live TV)

The watch.plex.tv client's API. An anonymous token comes from plex.tv's `users/anonymous`, with the install's device ID as the client identifier. It is kept until Plex refuses it: a 401 or 403 replaces it and retries once. The channel list is the US `plex` lineup on epg.provider.plex.tv, keyed by `gridKey`. Channels whose media says `drm` are left out. The group is the first themed guide filter (genre) that lists the channel, with Bingeworthy and Global used only for channels nothing else lists. Plex has no channel numbers. The guide is the per-channel `grid` for today and tomorrow (UTC), for mapped channels only. A resolve fetches the channel's `library/parts` master, which Plex serves directly with the token in its query. The URL goes to the relay for an hour.

Fragility: the compound part ID carries a server prefix that Plex rotates. Only `gridKey` is trusted as the channel ID, and the part key is re-read from the lineup. The anonymous token is rate-limited per token.

### stirr (STIRR)

The stirr.com web API. The channel list is one `videos/list` document of every live channel (`content_type=4`), keyed by `videoid`, with the channel number, the first category as the group and the logo (the square thumb when there is none). Channels marked `drm_protected` are left out. The guide is the per-channel `epg` endpoint in UTC, for mapped channels only. A resolve POSTs to `v2/videos/ID/playable`, which answers with an Aniview, Wurl or other ad-stitched HLS URL. That URL carries a session and a `vx_token` that lasts about 12 hours. `[vx_nonce]` is blanked, and the URL goes to the relay for an hour with the web client's `Origin` and `Referer`.

Fragility: the list document is over a megabyte, with rows nested by category, so rows are found by shape (`videoid` and `title`). Each channel's stream comes from a different ad stitcher, so one channel can fail while the rest play.

### tcl (TCL TV+)

The tcltv.plus web API on gateway-prod.ideonow.com, sent with the install's device ID. The `livetab` lists the categories. One `programlist/by/category` request per category (the personal `recommend` row is skipped) carries every channel in it, keyed by `bundle_id`, with its logo, its ad-stitched HLS template and its programs from an hour ago to eleven hours ahead. The channel list and the guide come from the same requests, cached for 55 minutes. The group is the first themed category that lists the channel. The curated rows (TCL TOP 15, Featured, Bingeable, TV Favorites) group only what nothing else does. TCL has no channel numbers. Channels with `drm` are left out. A resolve posts the template to `format-stream-url`, as the TCL client does. That call fills its own macros and percent-encodes literal brackets. The brace macros it leaves (`{DEVICE_ID}` and the like) are filled with the device ID and privacy defaults or blanked, and the URL goes to the relay for an hour.

Fragility: about 35 category requests per refresh. Seasonal rows are themed as far as the client can tell, and they group the channels they list first. Upstream streams come from Xumo, Amagi, Wurl, Frequency and others, each with its own ad macros.

### roku (The Roku Channel)

The channel list is Matt Huisman's mirror (i.mjh.nz `Roku/.channels.json.gz`), keyed by Roku's station ID with number, logo and first group. The mirror leaves out the channels that need DRM, so it lists only part of what the web client shows. Its XMLTV (`Roku/all.xml.gz`, with the same IDs) is the provider's implicit guide, through the ordinary guide pipeline. Set `guideUrl` to override it. A resolve runs the therokuchannel.roku.com web client's steps: an anonymous session (the home page's cookies and the `csrf` token, kept 55 minutes), the content proxy for the channel's `playId` (kept six hours), and a POST to `playback`. The POST answers with an `osm.sr.roku.com` HLS URL that the relay uses for five hours. A playback answer that names DRM fails as the channel's fault. A 401 or 403 starts a new session and retries once.

Fragility: CloudFront in front of the web client sometimes answers 403 for a while. That fails resolves as provider evidence. The channel list depends on a third party's mirror.

### localnow (Local Now)

The localnow.com web client. The page's `__NEXT_DATA__` runtime config carries the data-store host and a public app token (`DSP_TOKEN`, a JWT valid about a month, re-read every 12 hours or on a 401 or 403). One `live/epg/US/website` request lists the channels for the configured `dma` and `market`. A `dma` and `market` pair selects a market's local channels. Without them the national lineup comes back. Channels are keyed by `video_id`, with number, logo, first genre as group, and the next five programs, which are the whole guide. The snapshot is kept 15 minutes. Channels whose `subscription_access` is not `unlocked` are left out. A resolve asks `video/play/ID/1920/1080` and hands its MediaTailor `video_m3u8` to the relay for an hour. An answer with a `drm_config` fails as the channel's fault.

Fragility: five programs per channel cover as little as an hour on some channels, so the guide is only as deep as the last refresh. The app token is the web client's and changes with it.

### xumo (Xumo Play)

The play.xumo.com web API on valencia-app-mds.xumo.com, sent with the install's device ID. The `market` option selects the Xumo market, `10006` by default. The channel list is the market's `proxy/channels/list`, keyed by the channel's GUID, with number, first genre as group and the color logo. Live channels whose call sign ends in `-DRM` or `DRM-CMS` are Widevine and are left out, as are channels not marked live. The guide is the EPG in six-hour blocks (`epg/MARKET/DATE/BLOCK.json`, 50 channels a page), from the current block for 18 hours. Programs are kept only for mapped channels. A block not yet published (HTTP 400) ends the guide. A resolve reads the channel's `broadcast` document for the current hour and takes its `ssaiStreamUrl`, or else the live asset's HLS source. It fills the `[IFA]`, `[PLATFORM]`, `[CCPA_Value]` and similar macros, blanking the rest, follows the one-hop JSON pointer a few channels use instead of a manifest, and hands the URL to the relay for an hour.

Fragility: the DRM filter is a call-sign convention, not a flag, so a DRM channel named otherwise lists and fails to decode. The guide takes about ten requests per block.

### pluto (Pluto TV)

The pluto.tv web client's own API. `boot.pluto.tv/v4/start`, with the install's device ID as `clientID`, answers a session JWT (valid a day, kept four hours, replaced on a 401 or 403), the channels and stitcher hosts, and the stitcher query parameters. The channel list is the channels service's `v2/guide/channels`, keyed by Pluto's channel ID, with number, color logo and the name of the channel's first category from `v2/guide/categories`. Channels with `entitlements` need a Pluto account and are left out. The guide is `v2/guide/timelines` for mapped channels, 25 per request, covering twelve hours from the current hour. A resolve is the stitcher host, `/v2` and the channel's stitched path, with the boot's parameters, the JWT and `masterJWTPassthrough`. The URL goes to the relay for an hour.

Fragility: the boot's `appVersion` is the web client's and may be refused when Pluto retires it. The stitcher parameters carry the location Pluto derives from the server's address.

### tubi (Tubi)

tubitv.com/live as a signed-out visitor sees it. The page's `window.__data` state carries the `tubitv_us_linear` containers and their channel IDs. The public `oz/epg/programming` endpoint, 50 IDs a request, answers each channel's title, thumbnail, HLS manifest (with a token valid about three days) and about two days of programs, which are the guide. The snapshot is kept 30 minutes. The group is the first themed container that lists the channel. Featured, Recommended, Recently Added and Favorites group only what nothing else does. Tubi has no channel numbers. Channels with `needs_login`, or a resource with a `license_server`, are left out. A resolve asks `programming` for that one channel and hands its manifest to the relay for an hour.

Fragility: the channel IDs come from the page's server-rendered state. A page served without them fails the import as provider evidence, and the last complete snapshot stays.

### hdhomerun (HDHomeRun tuner)

An HDHomeRun on the LAN, read the way Plex and the HDHomeRun app read it. `options.url` is the device's address, such as `http://192.168.1.50`. `lineup.json` lists the scanned channels: the channel ID and number are the `GuideNumber`, the name is the `GuideName`, the group is `Local` and each channel is tagged `ota`. Channels the device marks DRM (the `DRM` flag or a `drm` tag) are left out, as are channels without a stream URL. The list is kept 55 minutes. A resolve answers the channel's own MPEG-TS stream URL on the device (`/auto/vN.N`), which the relay's fan-out shares between players; `options.transcode` appends a `transcode=` profile (`heavy`, `mobile`, `internet720` and the rest), which only EXTEND models honor. The guide is SiliconDust's XMLTV for the device's lineup at `api.hdhomerun.com/api/xmltv`, fetched with the `DeviceAuth` from `discover.json`, three days deep without an account. It is refreshed about once a day, at an interval between 20 and 28 hours chosen per install, as the guide service asks, and its channel IDs are mapped onto the lineup numbers by suffix or display name. `options.guideApi` points the guide at another XMLTV endpoint that takes `DeviceAuth`.

Fragility: the tuner has as many concurrent streams as it has tuners; past that, a play fails and Plex sees a busy tuner. An unscanned device has an empty lineup, which fails the import as provider evidence. The guide stops with `DeviceAuth`, which changes every few hours, so each fetch reads `discover.json` first.

### direct (broadcasters' own news feeds)

A fixed table of free live feeds that broadcasters publish on their own sites and apps without an account: the HLS URL each site's player loads, on the broadcaster's own CDN. Nothing is fetched to list them. Each channel is numbered from 9101 in table order, grouped `News` or `Weather`, and tagged with its country. A resolve is the table entry, sent with a browser `User-Agent`, and never expires. The feeds carry no logos: a channel's logo comes from the guide it is mapped onto.

| Channel | Country | Feed |
| --- | --- | --- |
| CBS News 24/7, and the CBS News local streams for Baltimore, the Bay Area, Boston, Chicago, Colorado, Detroit, Los Angeles, Miami, Minnesota, New York, Philadelphia, Pittsburgh, Sacramento and Texas | US | The ad-stitched `dai.google.com` event each station's live page on cbsnews.com plays. A resolve reads that page, once per feed per six hours, and takes the event URL it names; a page that names none, or does not answer, falls back to the table's URL (CBS's own CDN for the national stream, the last known event for a local one) |
| Bloomberg TV, Bloomberg Originals | US | bloomberg.com/media-manifest |
| Cheddar | US | hls.livecdn.io |
| Newsmax | US | nmx1ota.akamaized.net |
| Fox Weather | US | 247wlive.foxweather.com |
| Al Jazeera English | Qatar | getaj.net |
| DW English | Germany | dwamdstream103.akamaized.net |
| France 24 English | France | live.france24.com (a media playlist, which the relay serves as any playlist) |
| NHK World-Japan | Japan | masterpl.hls.nhkworld.jp |
| ABC News (Australia) | Australia | abc-news-dmd-streams-1.akamaized.net |
| i24NEWS English | Israel | encoders.immergo.tv |

There is no service guide. The `epg_ripper_US2` guide on epgshare01.online lists most of the US channels, under `CBS.News.National.Stream.us2`, `Bloomberg.HD.us2`, `Cheddar.us2`, `Newsmax.TV.HD.us2`, `NHK.World.TV.us2` and `i24.News.English.us2`; a lineup policy maps a station onto whichever configured guide carries it, and `PATCH /api/v1/admin/lineup/{id}` with `{"guide": {"guideId", "channelId"}}` maps one by hand.

Fragility: every URL is hard-coded, so a broadcaster that moves or retires a feed, or starts requiring a token, fails that one channel until the table is updated. A stale CDN can keep answering a master playlist whose variants are gone, which a resolve cannot see; the probe and the relay do, so a dead feed strikes like any 404 ([source health](source-health.md)). Feeds published for one country can be geo-blocked elsewhere. The CBS pages are HTML, so a redesign that drops the event URL leaves the fallbacks in place until the table is updated.

## Checking a provider

`scripts/provider-smoke.py --service NAME [--option key=value]` starts a disposable server with that one provider, waits for the import and adopts every channel. It then opens sessions on three channels, expecting an HLS master from each, decodes five seconds of two of them with FFmpeg, and prints the counts. It needs the built distribution and network access to the service. See [development](development.md#scripts) for the other scripts.
