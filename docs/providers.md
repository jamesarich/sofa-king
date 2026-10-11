# Native providers

A native provider is a client for one free streaming service built into the server, configured in `sources.json` as `{"type": "native", "service": "NAME"}` next to the M3U providers. It lists the service's channels as candidates, carries the service's guide, and resolves a channel to a playable HLS URL when a session, a probe or the HDHomeRun remux needs one. Everything downstream (lineup, health, failover, logos, player outputs, Plex) works as for an M3U provider.

These are unofficial clients. They call the same public endpoints each service's own web or TV client calls, with no account and no credentials, and they stop working whenever the service changes them. Using them may be against a service's terms of service; this server is for one household and sends ad-insertion identifiers that are random per install, not a real device's.

## Configuration

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
    { "id": "pbs", "type": "native", "service": "pbs", "options": { "zip": "50309" } },
    { "id": "samsung", "type": "native", "service": "samsung", "options": { "region": "us" } },
    { "id": "plex", "type": "native", "service": "plex" },
    { "id": "stirr", "type": "native", "service": "stirr" },
    { "id": "tcl", "type": "native", "service": "tcl" },
    { "id": "roku", "type": "native", "service": "roku" },
    { "id": "localnow", "type": "native", "service": "localnow", "options": { "dma": "679", "market": "iaDesMoines" } },
    { "id": "xumo", "type": "native", "service": "xumo" },
    { "id": "pluto", "type": "native", "service": "pluto" },
    { "id": "tubi", "type": "native", "service": "tubi" }
  ]
}
```

| Field | Default | Description |
| --- | --- | --- |
| `type` | `m3u` | `native` selects a service client; `playlistUrl` must then be absent |
| `service` | Required | One of `whale`, `vizio`, `lg`, `freelivesports`, `hallmark`, `ballysports`, `adultswim`, `pbs`, `samsung`, `plex`, `stirr`, `tcl`, `roku`, `localnow`, `xumo`, `pluto`, `tubi` |
| `options` | `{}` | Service options, string values: `zip` or `stations` for `pbs`, `region` for `samsung`, `dma` and `market` for `localnow` |
| `refreshSeconds` | `3600` | Channel list refresh for a native provider (900 for a playlist); minimum 60 |
| `guideUrl` | Service default | An XMLTV to use instead of the service's own guide; `samsung` defaults to the i.mjh.nz XMLTV for its region, `roku` to the i.mjh.nz Roku XMLTV |
| `probe` | `true` | Probes resolve the channel and qualify the resolved URL like any source |

`headers`, `defaultProtocol`, `allowEmpty` and `renewableQuery` apply to playlists only; every native source is HLS.

A native provider with a guide of its own appears in `/api/v1/admin/guides` under the provider id, refreshed hourly; its channel ids are the service's own, and a lineup entry created from a candidate maps onto it automatically. The guide holds every channel in its index and programmes only for channels the lineup maps, so a service whose guide is fetched per channel (Whale, Hallmark, Adult Swim, PBS, Plex, STIRR, Pluto TV) is asked only about mapped channels.

## How a native source plays

A native offering's URL is `svc://SERVICE/CHANNEL_ID`: stable, never fetched, and what the lineup refers to. When a session opens the channel the relay asks the provider to resolve it, fills the service's ad macros (device id, session id, cache buster, privacy defaults) or follows the service's redirect, and fetches the master playlist with the headers the service's own client sends. The resolution is cached per source until a minute before it expires (an hour for macro-filled URLs, the token lifetime for tokens, forever for static URLs) and shared by every session, probe and remux on that source. Nothing a player sees names the service: media playlists and segments are the relay's opaque session paths, as for any HLS source.

A service that cannot answer (its API down, a token refused twice) fails that one resolution: the session answers `502 UPSTREAM_UNAVAILABLE`, Plex sees a busy tuner, and a probe records provider evidence, which never strikes. A channel the service no longer lists is offering evidence and strikes like a 404. One provider failing never touches another.

Per provider, the server keeps one random device id and any session token in the `provider_state` table, so a restart neither re-identifies the install nor re-authenticates. Logs carry provider ids, channel counts, failure classes and reason text; never tokens or URLs.

## Services

### whale (Whale TV+)

The web client at watch.whaletvplus.com, backed by the rlaxx API. An access token comes from `auth/access` with the web client's public key and lives about 24 hours (stored, reused for 23). The channel list is the category listing for `US`/`en`, each channel once with the first themed category as its group, numbers and the white channel icon. Streams are Ottera, Amagi, MediaTailor and SoFast ad-stitched HLS templates whose `[did]`, `[session_id]`, `[cachebuster]` and other `[...]` macros are filled per resolve (unknown ones blanked), with `Origin` and `Referer` of the web client. The guide is the 7-day EPG endpoint, ten channels per request, for mapped channels only.

Fragility: the public API key is the web client's and changes with it; a 401 or 403 drops the stored token and retries once. Channel ids are the service's numeric ids.

### vizio (WatchFree+)

The SmartCast guide API, which needs no authentication. Channels with a `tokenUrl` (the NFL channel) need a device entitlement and are skipped. Stream URLs are clear HLS on Amagi and others with `{ADID}`-style macros, present percent-encoded in the payload; the known ones are filled with the anonymous mobile client's values and the install's device id, unknown ones are blanked. The guide is one bulk `airings` request for the next 24 hours, matched to channels by `airingsKey`.

Fragility: `FEATURED` channel ids are rotating promo slots (tagged `featured` in the candidate's tags), so the programme in them changes while the id stays. A few hundred channels import; expect `channelNumber` collisions with other providers until renumbered.

### lg (LG Channels)

One `schedulelist` document from the lgchannels.com web API, sent with the web client's device headers (`US`, `en`, `WEB`), served as base64 of a zlib stream under `text/plain` (plain JSON is accepted too). It carries every category, channel, logo, number, the static HLS template with `[DEVICE_ID]`-style macros, and the next hours of programmes (about 12 hours ahead), which is the whole guide.

Fragility: the guide horizon is short, hence the hourly refresh; the document encoding has changed before.

### freelivesports (FreeLiveSports.tv)

One public catalog document from the Unreel/POWR platform listing every channel with its HLS template (`[DEVICE_ID]`, `[CB]`, privacy macros) and the embedded schedule. The catalog path carries the site's public key segment plus `__site=freelivesports&__source=web`; the bare `public/` path answers 403.

Fragility: the key segment is baked into the client and changes if the site re-keys; the `catalogUrl` option overrides it.

### hallmark (Hallmark+)

Four fixed channels (Non-Stop Hallmark Hits, Rom-Coms, Christmas, Mysteries) with static clear HLS on `crwn-*.akamaized.net`, numbered 9001 to 9004. The hallmarkplus.com catalog supplies logos and each channel's current content id by its stable `channelIdentifier`; the per-channel schedule API covers 48 hours. An unreachable catalog still lists the channels (without logos) and fails only the guide.

Fragility: the content ids in the catalog have been renamed before; only the identifiers are trusted. The CDN hosts are hard-coded.

### ballysports (Bally Sports free channels)

The Next.js channel page (`/channels/milb`, after its redirect) embeds React Server Components chunks with `channelData` (uuid, name, order, logo, public CDN URL) and `epgData`. Only channels on `*.channels.ballys.tv` are listed; streams are static HLS.

Fragility: parsing a framework payload out of HTML; a site redesign ends it. Five channels.

### adultswim (Adult Swim marathons)

The streams page's `__NEXT_DATA__` state lists the marathon streams with a video id and a marathon id; the video API names each one's `/live/` HLS asset (looked up on first resolve, cached six hours); the marathon schedule API carries the guide with episode names. Numbered in page order.

Fragility: page state shape and the video API fields; some marathons are region-restricted and may not play. Segments are AES-128 and the CDN sometimes gzip-encodes the key, which the relay inflates before the player sees it.

### pbs (PBS member stations)

`zip` is localized to the member stations at pbs.org (`50309` gives Iowa PBS, KDIN); `stations` lists station ids directly, comma-separated. Each station's `livestream_feeds` are listed only when they expose a clear `non_drm_url`, which for Iowa PBS is Create and World; the main channel and PBS Kids are DRM (`drm_hls_url` only) and never appear. Names are `PBS Create (KDIN)`, ids `STATION_ID:PROFILE`. The guide is the station's livestream schedule for today and tomorrow in the station's time zone, matched by feed cid. Streams are `urs.pbs.org` redirects the relay follows.

Fragility: which feeds are clear is the station's decision and changes; the schedule API is the player's.

### samsung (Samsung TV Plus)

Matt Huisman's published mirrors (i.mjh.nz): the channel list `.channels.json.gz` by `region` (default `us`), channels with a `license_url` left out as DRM, and the XMLTV `REGION.xml.gz` as the provider's implicit guide through the ordinary guide pipeline (refreshed with the provider, hourly by default; set `guideUrl` to override). A resolve requests `jmp2.uk/stvp-ID` without following, takes its redirect target, fills `{PSID}`, `{TARGETOPT}` and `{US_PRIVACY}` and blanks the other braces; the relay follows the remaining hop to the CDN.

Fragility: a third party's mirror and redirector; both can lag or go away. Some targets are CMAF with DRM despite no `license_url` and do not decode.

### plex (Plex free live TV)

The watch.plex.tv client's API. An anonymous token comes from plex.tv's `users/anonymous` with the install's device id as client identifier and is kept until Plex refuses it (a 401 or 403 replaces it and retries once). The channel list is the US `plex` lineup on epg.provider.plex.tv, keyed by `gridKey`; channels whose media says `drm` are left out (none today). The group is the first themed guide filter (genre) that lists the channel, with Bingeworthy and Global only for channels nothing else lists; Plex has no channel numbers. The guide is the per-channel `grid` for today and tomorrow (UTC), for mapped channels only. A resolve fetches the channel's `library/parts` master, which Plex serves directly with the token in its query, and hands that URL to the relay for an hour.

Fragility: the compound part id carries a server prefix Plex rotates; only `gridKey` is trusted as the channel id and the part key is re-read from the lineup. The anonymous token is rate-limited per token.

### stirr (STIRR)

The stirr.com web API. The channel list is one `videos/list` document of every live channel (`content_type=4`), keyed by `videoid`, with the channel number, the first category as the group and the logo (the square thumb when there is none); channels marked `drm_protected` are left out (none today). The guide is the per-channel `epg` endpoint in UTC, for mapped channels only. A resolve POSTs to `v2/videos/ID/playable`, which answers an Aniview, Wurl or other ad-stitched HLS URL carrying a session and a `vx_token` that lives about 12 hours; `[vx_nonce]` is blanked and the URL is handed to the relay for an hour with the web client's `Origin` and `Referer`.

Fragility: the list document is over a megabyte with rows nested by category, so rows are found by shape (`videoid` and `title`). Each channel's stream comes from a different ad stitcher, so one channel can fail while the rest play.

### tcl (TCL TV+)

The tcltv.plus web API on gateway-prod.ideonow.com, sent with the install's device id. The `livetab` lists the categories; one `programlist/by/category` request per category (the personal `recommend` row is skipped) carries every channel in it, keyed by `bundle_id`, with its logo, its ad-stitched HLS template and its programmes from an hour ago to eleven hours ahead, so the channel list and the guide are the same requests, cached for 55 minutes. The group is the first themed category that lists the channel; the curated rows (TCL TOP 15, Featured, Bingeable, TV Favorites) only group what nothing else does. TCL has no channel numbers. Channels with `drm` are left out (none today). A resolve posts the template to `format-stream-url`, as the TCL client does, which fills its own macros and percent-encodes literal brackets; the brace macros it leaves (`{DEVICE_ID}` and the like) are filled with the device id and privacy defaults or blanked, and the URL is handed to the relay for an hour.

Fragility: about 35 category requests per refresh. Seasonal rows (Happy Halloween) are themed as far as the client can tell and group the channels they list first. Upstream streams come from Xumo, Amagi, Wurl, Frequency and others, each with its own ad macros.

### roku (The Roku Channel)

The channel list is Matt Huisman's mirror (i.mjh.nz `Roku/.channels.json.gz`), keyed by Roku's station id with number, logo and first group. The mirror leaves out the channels that need DRM: it lists about 260 of the roughly 840 the web client shows, ABC News Live (which it omits) is FairPlay, and a sample of five listed channels was clear or AES-128. Its XMLTV (`Roku/all.xml.gz`, the same ids) is the provider's implicit guide through the ordinary guide pipeline; set `guideUrl` to override. A resolve runs the therokuchannel.roku.com web client's steps: an anonymous session (the home page's cookies and the `csrf` token, kept 55 minutes), the content proxy for the channel's `playId` (kept six hours), and a POST to `playback`, which answers an `osm.sr.roku.com` HLS URL the relay uses for five hours. A playback answer that names DRM fails as the channel's fault; a 401 or 403 boots a new session and retries once.

Fragility: CloudFront in front of the web client sometimes answers 403 for a while, which fails resolves as provider evidence. The channel list depends on a third party's mirror.

### localnow (Local Now)

The localnow.com web client. The page's `__NEXT_DATA__` runtime config carries the data-store host and a public app token (`DSP_TOKEN`, a JWT valid about a month, re-read every 12 hours or on a 401 or 403). One `live/epg/US/website` request lists every channel for the configured `dma` and `market` (`679` and `iaDesMoines` add Local Now Des Moines and the Des Moines station channels; without them the national lineup comes back), keyed by `video_id`, with number, logo, first genre as group and the next five programmes, which are the whole guide; the snapshot is kept 15 minutes. Channels whose `subscription_access` is not `unlocked` are left out (none today). A resolve asks `video/play/ID/1920/1080` and hands its MediaTailor `video_m3u8` to the relay for an hour; an answer with a `drm_config` fails as the channel's fault.

Fragility: five programmes per channel cover as little as an hour on some channels, so the guide is only as deep as the last refresh. The app token is the web client's and changes with it.

### xumo (Xumo Play)

The play.xumo.com web API on valencia-app-mds.xumo.com, market `10006`, sent with the install's device id. The channel list is the market's `proxy/channels/list`, keyed by the channel's guid, with number, first genre as group and the color logo; live channels whose call sign ends in `-DRM` or `DRM-CMS` are Widevine and left out (18 of 451 today), as are channels not marked live. The guide is the EPG in six-hour blocks (`epg/MARKET/DATE/BLOCK.json`, 50 channels a page) from the current block for 18 hours, keeping programmes only for mapped channels; a block not yet published (HTTP 400) ends it. A resolve reads the channel's `broadcast` document for the current hour and takes its `ssaiStreamUrl`, or else the live asset's HLS source, fills the `[IFA]`, `[PLATFORM]`, `[CCPA_Value]` and similar macros (blanking the rest), follows the one-hop JSON pointer a few channels use instead of a manifest, and hands the URL to the relay for an hour.

Fragility: the DRM filter is a call-sign convention, not a flag; a DRM channel named otherwise lists and fails to decode. About ten guide requests per block.

### pluto (Pluto TV)

The pluto.tv web client's own API. `boot.pluto.tv/v4/start`, with the install's device id as `clientID`, answers a session JWT (valid a day, kept four hours, replaced on a 401 or 403), the channels and stitcher hosts and the stitcher query parameters. The channel list is the channels service's `v2/guide/channels`, keyed by Pluto's channel id, with number, color logo and the name of the channel's first category from `v2/guide/categories`; channels with `entitlements` (today 29, all `Registered`) need a Pluto account and are left out. The guide is `v2/guide/timelines` for mapped channels, 25 per request, twelve hours from the current hour. A resolve is the stitcher host, `/v2` and the channel's stitched path, with the boot's parameters, the JWT and `masterJWTPassthrough`, handed to the relay for an hour.

Fragility: the boot's `appVersion` is the web client's and may be refused when Pluto retires it. The stitcher parameters carry the location Pluto derives from the server's address.

### tubi (Tubi)

tubitv.com/live as a signed-out visitor sees it. The page's `window.__data` state carries the `tubitv_us_linear` containers and their channel ids (176 today); the public `oz/epg/programming` endpoint, 50 ids a request, answers each channel's title, thumbnail, HLS manifest (with a token valid about three days) and about two days of programmes, which are the guide; the snapshot is kept 30 minutes. The group is the first themed container that lists the channel; Featured, Recommended, Recently Added and Favorites only group what nothing else does. Tubi has no channel numbers. Channels with `needs_login` or a resource with a `license_server` are left out (none today). A resolve asks `programming` for that one channel and hands its manifest to the relay for an hour.

Fragility: the channel ids come from the page's server-rendered state; a page served without them fails the import as provider evidence and the last complete snapshot stays.

## Checking a provider

`scripts/provider-smoke.py --service NAME [--option key=value]` starts a disposable server with that one provider, waits for the import, adopts every channel, opens sessions on three channels and expects an HLS master from each, decodes five seconds of two of them with FFmpeg, and prints the counts. It needs the built distribution and network access to the service.
