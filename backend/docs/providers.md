# Native providers

A native provider is a client for one free streaming service built into the server. It is configured in the source file as `{"type": "native", "service": "NAME"}`, next to the M3U providers. It lists the service's channels as candidates, carries the service's guide, and resolves a channel to a playable stream URL when a session, a probe or the HDHomeRun remux needs one. Everything downstream (lineup, health, failover, logos, player outputs, Plex) works as it does for an M3U provider.

The clients are unofficial. They call the same public endpoints each service's own web or TV client calls, with no account and no credentials, and they stop working whenever the service changes them. Using them may be against a service's terms of service. The ad-insertion identifiers they send are random per install, not a device's.

The server supports 21 streaming services, the free feeds of about twenty broadcasters (`direct`) and HDHomeRun tuners. Each is listed below with what it lists and its known fragility.

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
| `service` | Required | One of `whale`, `vizio`, `lg`, `freelivesports`, `hallmark`, `ballysports`, `adultswim`, `pbs`, `samsung`, `plex`, `stirr`, `tcl`, `roku`, `localnow`, `xumo`, `pluto`, `tubi`, `cw`, `zeam`, `ognetwork`, `mansa`, `direct`, `hdhomerun` |
| `options` | `{}` | Service options, string values: `zip` or `stations` for `pbs`, `region` and `fallbackUrl` for `samsung`, `region` for `whale`, `region` and `forwardedFor` for `pluto` and `plex`, `dma` and `market` for `localnow`, `market` for `xumo`, `url` (required) and `transcode` for `hdhomerun` |
| `refreshSeconds` | `3600` | Channel list refresh for a native provider (900 for a playlist); minimum 60 |
| `guideUrl` | Service default | An XMLTV to use instead of the service's own guide; `samsung` and `roku` default to the i.mjh.nz XMLTV |
| `probe` | `true` | Probes resolve the channel and qualify the resolved URL like any source. A lineup policy ignores it ([probing](lineup-policy.md#probing)) |

`headers`, `defaultProtocol`, `allowEmpty` and `renewableQuery` apply to playlists only. Every native source is HLS, except `hdhomerun`, whose sources are MPEG-TS. `direct` has no guide of its own; map its channels onto a configured XMLTV guide, as the [direct](#direct-broadcasters-own-feeds) section shows.

A native provider with a guide of its own appears in `/api/v1/admin/guides` under the provider ID, refreshed hourly. Its channel IDs are the service's own, and a lineup entry created from a candidate maps onto that guide automatically. The guide holds every channel in its index, but programs only for channels the lineup maps; Whale, Hallmark, Adult Swim, PBS, Plex, STIRR and Pluto TV fetch a guide per channel and ask only about mapped ones.

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

### Other countries' lineups

Samsung TV Plus publishes each country's lineup, and Whale TV+ answers the country its request names, so for them another country is only its `region` (see [samsung](#samsung-samsung-tv-plus)). Whale TV+ serves 130 to 195 channels per country, most of which play from a US connection.

Pluto TV and Plex pick the lineup by the caller's address. For them, `region` is the two-letter code the channels are tagged with, and `forwardedFor` is an IP address in that country, sent as `X-Forwarded-For` on every request the client and the relay make for the provider's streams. Give each country its own provider beside the US one:

```json
{ "id": "pluto-gb", "type": "native", "service": "pluto", "options": { "region": "gb", "forwardedFor": "ADDRESS_IN_GB" } }
```

The i.mjh.nz Pluto TV and Plex channel lists carry one address per country they serve. Pluto TV's 14 addresses give 12 lineups, since Argentina and Mexico share one Latin American lineup and the Chile address answers with Canada's; Plex's 8 give 8. LG Channels answers any country but the US with an error, so `lg` takes no region. The services decide whether they honor the header and can stop at any time, and presenting an address that is not the server's departs further from their terms. A `forwardedFor` that is not an IP address fails the provider's imports and resolves, never the server's start.

## How a native source plays

A native source's URL is `svc://SERVICE/CHANNEL_ID`: stable, never fetched, and what the lineup refers to. When a session opens the channel, the relay asks the provider to resolve it: the provider fills the service's ad macros (device ID, session ID, cache buster, privacy defaults) or follows the service's redirect, and the relay fetches the master playlist with the headers the service's own client sends. The resolution is cached per source until a minute before it expires, for at most six hours, and every session, probe and remux on that source shares it. Nothing a player sees names the service. Media playlists and segments are the relay's opaque session paths, and an MPEG-TS service (`hdhomerun`) resolves to the stream the relay fans out between players.

A service that cannot answer (its API is down, or a token is refused twice) fails that one resolution: the session answers `502 UPSTREAM_UNAVAILABLE`, Plex sees a busy tuner, and a probe records provider evidence, which never strikes. A channel missing from the service's list is evidence against that source and strikes like a 404. One provider failing never affects another.

Each provider keeps one random device ID and any session token in the `provider_state` table, so a restart neither re-identifies the install nor re-authenticates. Logs carry provider IDs, channel counts, failure classes and reason text, never tokens or URLs.

## Services

Services that list per-category rows (Plex, TCL, Tubi) group a channel under the first themed row that lists it.

### whale (Whale TV+)

The web client at watch.whaletvplus.com. Lists each channel of the `region` (default `US`) once, in English, under its first themed category. The guide is 7 days, for mapped channels only. Streams are ad-stitched HLS templates whose macros are filled per resolve.

Fragility: the public API key is the web client's and changes with it. A 401 or 403 drops the stored token and retries once.

### vizio (WatchFree+)

The SmartCast guide API, which needs no authentication. Channels that need a device entitlement (the NFL channel) are skipped. Streams are clear HLS with macros filled with the anonymous mobile client's values. The guide is the next 24 hours.

Fragility: `FEATURED` channel IDs are rotating promo slots (tagged `featured` in the candidate's tags), so the program in them changes while the ID stays. The service has hundreds of channels, and their `channelNumber` values can collide with other providers until renumbered.

### lg (LG Channels)

One `schedulelist` document from the lgchannels.com web API, sent with the web client's device headers (`US`, `en`, `WEB`). It carries every category, channel, logo, number and stream template, and the next hours of programs, which are the whole guide.

Fragility: the guide horizon is short, hence the hourly refresh. The document encoding has changed before.

### freelivesports (FreeLiveSports.tv)

One public catalog document from the Unreel/POWR platform, listing every channel with its HLS template and embedded schedule. The `catalogUrl` option overrides the catalog path, which carries the site's public key.

Fragility: the key is baked into the client and changes if the site re-keys.

### hallmark (Hallmark+)

Four fixed channels (Non-Stop Hallmark Hits, Rom-Coms, Christmas and Mysteries) with static clear HLS, numbered 9001 to 9004. The hallmarkplus.com catalog supplies logos and each channel's current content ID, and the guide covers 48 hours. An unreachable catalog still lists the channels, without logos, and fails only the guide.

Fragility: the content IDs change, so only the channel identifiers are trusted. The CDN hosts are hard-coded.

### ballysports (Bally Sports free channels)

The channel page (`/channels/milb`, after its redirect) embeds the channel data and programs. Only channels on `*.channels.ballys.tv` are listed. Streams are static HLS.

Fragility: this parses a framework payload out of HTML, so a site redesign ends it.

### adultswim (Adult Swim marathons)

The streams page lists the marathon streams, numbered in page order. The guide carries episode names. The HLS asset is looked up on first resolve and cached for six hours.

Fragility: the page state shape and the video API fields. Some marathons are region-restricted and may not play. The segment key is AES-128, and the relay inflates it when the CDN gzip-encodes it.

### pbs (PBS member stations)

`zip` selects the member stations for a ZIP code's area at pbs.org; `stations` lists station IDs directly, comma-separated. A station's feeds are listed only when they expose a clear `non_drm_url`. Names have the form `PBS Create (KDIN)` and IDs the form `STATION_ID:PROFILE`. The guide is the station's schedule for today and tomorrow.

Fragility: which feeds are clear is the station's decision, and it changes.

### samsung (Samsung TV Plus)

Matt Huisman's published mirrors (i.mjh.nz): the channel list by `region` (default `us`), without channels that carry a `license_url` (DRM). When i.mjh.nz fails, the list comes from the mirror's GitHub repository (`fallbackUrl` overrides it), and when that fails too, the last list that loaded, kept in SQLite across restarts, stands in. Samsung publishes no channel API outside its own apps. The region's XMLTV is the implicit guide; `guideUrl` overrides it.

Fragility: a third party's mirror and redirector (`jmp2.uk`) can lag or go away, and the kept list bridges only an outage of the list. Some targets are CMAF with DRM despite having no `license_url`, and they do not decode.

### plex (Plex free live TV)

The watch.plex.tv client's API, with an anonymous token the server keeps until Plex refuses it. Lists the US lineup without DRM channels, grouped by the first genre filter that lists the channel (Bingeworthy and Global only for channels nothing else lists). Plex has no channel numbers. The guide is today and tomorrow (UTC), for mapped channels only.

Fragility: the part ID in a stream's path carries a server prefix that Plex rotates, so only the channel's `gridKey` is trusted and the part is re-read from the lineup. The anonymous token is rate-limited per token.

### stirr (STIRR)

The stirr.com web API. Lists every live channel with its number, first category as the group and logo, without `drm_protected` ones. The guide is per channel, in UTC, for mapped channels only. Each channel resolves to an ad-stitched HLS URL whose token lasts about 12 hours.

Fragility: the list document is over a megabyte, with rows nested by category. Each channel's stream comes from a different ad stitcher, so one channel can fail while the rest play.

### tcl (TCL TV+)

The tcltv.plus web API, sent with the install's device ID. One request per category (about 35 per refresh, the personal `recommend` row skipped) carries the channels, their stream templates and programs from an hour ago to eleven hours ahead; the list and the guide share them, cached for 55 minutes. The curated rows (TCL TOP 15, Featured, Bingeable, TV Favorites) group only what nothing else does. TCL has no channel numbers.

Fragility: seasonal rows count as themed as far as the client can tell, and group the channels they list first. Upstream streams come from Xumo, Amagi, Wurl, Frequency and others, each with its own ad macros.

### roku (The Roku Channel)

The channel list is Matt Huisman's mirror (i.mjh.nz), keyed by Roku's station ID with number, logo and first group. The mirror leaves out DRM channels, so it lists only part of what the web client shows. When it fails, the list is the web client's own live station list narrowed to the channels the mirror last listed, under the mirror's groups; when that fails too, the last list that loaded stands in, kept in SQLite across restarts. The mirror's XMLTV is the implicit guide; `guideUrl` overrides it. A resolve starts an anonymous web session, kept 55 minutes. A playback answer that names DRM fails as the channel's fault.

Fragility: CloudFront in front of the web client sometimes answers 403 for a while, which fails resolves as provider evidence. Before the mirror has ever loaded, the station list includes DRM channels, which fail on resolve as the channel's fault.

### localnow (Local Now)

The localnow.com web client. `dma` and `market` together select a market's local channels; without them the national lineup comes back. Each channel carries number, logo, first genre as group and the next five programs, which are the whole guide. Channels whose `subscription_access` is not `unlocked` are left out, and an answer with a `drm_config` fails as the channel's fault.

Fragility: five programs cover as little as an hour on some channels, so the guide is only as deep as the last refresh. The app token is the web client's and changes with it.

### xumo (Xumo Play)

The play.xumo.com web API, sent with the install's device ID. The `market` option selects the Xumo market, `10006` by default. Lists the market's live channels with number, first genre as group and the color logo, without Widevine channels (call sign ending `-DRM` or `DRM-CMS`). The guide covers 18 hours from the current six-hour block, for mapped channels only; a block not yet published ends the guide.

Fragility: the DRM filter is a call-sign convention, not a flag, so a DRM channel named otherwise lists and fails to decode. The guide takes about ten requests per block.

### pluto (Pluto TV)

The pluto.tv web client's own API, with a session token the server keeps and replaces on a 401 or 403. Lists the channels with number, color logo and first category, without channels that need a Pluto account. The guide covers twelve hours from the current hour, for mapped channels only. `region` and `forwardedFor` pick another country's lineup ([above](#other-countries-lineups)).

Fragility: the boot's `appVersion` is the web client's and may be refused when Pluto retires it. The stitcher parameters carry the location Pluto derives from the server's address.

### tubi (Tubi)

tubitv.com/live as a signed-out visitor sees it. Lists the linear channels without those that need login or use a license server; Featured, Recommended, Recently Added and Favorites group only what nothing else does. Tubi has no channel numbers. The guide is about two days, kept 30 minutes.

Fragility: the channel IDs come from the page's server-rendered state. A page served without them fails the import as provider evidence, and the last complete snapshot stays.

### zeam (Zeam)

The zeam.com/live page lists about 400 stations. The guide fetches only the wanted channels' schedules, six at a time; a schedule that answers 403 or 404 means that channel has no guide. The stream token lasts about 90 seconds, and the variant and key URLs answer only the User-Agent that fetched the master, so the client sends a fixed one. The `siteBase` option overrides the site.

Fragility: the lineup comes from a script blob in the page. Schedules are large, over a megabyte for some channels, and looped channels list multi-day blocks under one title. A channel is gone only when the page drops it.

### ognetwork (OG Network)

The site's catalog lists about 84 small channels. Tuning one opens a playback session on the Sotal cloud, valid about 10 minutes, and checks the master and first variant; a channel whose playlist has no segments fails as a gone offering. The guide is one call of about 10 MB. The `catalogUrl` and `apiBase` options override both endpoints.

Fragility: an unofficial API. Some channels serve empty playlists, the operator allows three playback sessions at once, and the catalog carries no channel numbers.

### cw (The CW)

One feed from the CW web player lists The CW's free channels (ION, Court TV, FilmRise and others) with clear HLS and programs from a few hours back to about 16 hours ahead. Channels marked `has_drm` or not HLS are left out, and "To Be Announced" filler is dropped from the guide. The `feedBase` option overrides the feed path.

Fragility: the API version is fixed in the path, so a version change on the CW's side ends it. The feed is one 1.4 MB document, and its guide is shorter than a day.

### mansa (Mansa)

One request to the Kanku playlist API lists Mansa's channels; only `FAST` entries are kept, since `VIRTUAL` ones are on-demand playlists. The guide is one shared feed of about a day, matched to channels by ID, then by normalized name. The `playlistUrl` and `guideUrl` options override both endpoints.

Fragility: an unofficial API that can change shape. Some channels' schedules stop updating, so a few channels have no guide at a time.

### iptv-org (community streams)

The [iptv-org](https://github.com/iptv-org/iptv) community lists thousands of public streams, with the channel database ([lineup policy](lineup-policy.md#iptv-org-metadata)) describing each channel. The server offers them as a provider with the ID `iptv-org`. It is off by default: `SOFA_IPTV_ORG_STREAMS=on` (the Settings page, or the MCP tool `set_runtime_setting`) turns it on, and it needs no entry in the source file. Switching it on downloads `channels.json`, `logos.json`, `blocklist.json`, `timezones.json` and `streams.json` from `iptv-org.github.io/api`, weekly after that, with the conditional requests, size cap, last good copy and halving guard the metadata uses. Switching it on later fetches `streams.json` at once, even right after a metadata download, and the import runs as soon as the file lands; a change of region imports again from the files already downloaded.

The streams are community submissions. Many are dead, geo-blocked or move without notice, and availability and legality vary by country: you choose what to enable. Every stream starts `UNVERIFIED` and is probed like any source, so the health policy drops the dead ones; the first-pass probing in [probing](lineup-policy.md#probing) applies.

`SOFA_IPTV_ORG_REGION` picks the countries. Unset, the server uses the country of its time zone from iptv-org's `timezones.json` (the zone's first country), else `US`; the iptv-org tab on the Sources page shows the region in force and whether it came from the setting or the time zone. Channels are chosen by `channels.json`'s `country`; a channel with no country, or in another country, is not imported. A channel on `blocklist.json`, with a `closed` date or marked `is_nsfw` is never imported, and no setting changes that.

Each stream is one source named by its channel's name, with the logo, the first category as its group, and its country and quality as tags. Several streams of one channel, and the same channel from another provider, merge into one station by name through the lineup policy's normal station keys. Each stream's `referrer` and `user_agent` are sent as `Referer` and `User-Agent` on its requests, and the upstream address policy applies to every stream URL. Switching streams off lists no channels at the next import, so the stations lose those sources.

Fragility: the community's streams change daily. Guide data is out of scope: map the channels onto an XMLTV guide as for [direct](#direct-broadcasters-own-feeds).

### hdhomerun (HDHomeRun tuner)

An HDHomeRun on the LAN, read the way Plex and the HDHomeRun app read it. `options.url` is the device's address, such as `http://192.168.1.50`. The scanned channels use the `GuideNumber` as ID and number and the `GuideName` as name, in the group `Local`, tagged `ota`; channels the device marks DRM or without a stream URL are left out. A resolve answers the channel's own MPEG-TS stream on the device, which the relay's fan-out shares between players. `options.transcode` appends a `transcode=` profile (`heavy`, `mobile`, `internet720` and the rest), which only EXTEND models honor.

The guide is SiliconDust's XMLTV for the device's lineup (`api.hdhomerun.com`), three days deep without an account. It is refreshed about once a day, at an interval of 20 to 28 hours chosen per install, as the guide service asks, and its channels are mapped onto the lineup numbers by suffix or display name. `options.guideApi` points it at another XMLTV endpoint that takes `DeviceAuth`.

Fragility: the tuner has as many concurrent streams as it has tuners; past that, a play fails and Plex sees a busy tuner. An unscanned device has an empty lineup, which fails the import as provider evidence. The guide depends on `DeviceAuth`, which changes every few hours, so each fetch reads `discover.json` first.

### direct (broadcasters' own feeds)

A fixed table of free live feeds that broadcasters publish on their own sites and apps without an account: the HLS URL each site's player loads, on the broadcaster's own CDN. Nothing is fetched to list them. Each channel is numbered from 9101 in table order, grouped by what it carries (`News`, `Weather`, `Entertainment` or `Documentary`), and tagged with its country, which is the broadcaster's, not a geofence. A resolve is the table entry, sent with a browser `User-Agent`, and never expires. The feeds carry no logos: a channel's logo comes from the guide it is mapped onto.

| Channel | Country | Feed |
| --- | --- | --- |
| CBS News 24/7, and the CBS News local streams for Baltimore, the Bay Area, Boston, Chicago, Colorado, Detroit, Los Angeles, Miami, Minnesota, New York, Philadelphia, Pittsburgh, Sacramento and Texas | US | The ad-stitched `dai.google.com` event each station's live page on cbsnews.com plays, read once per feed per six hours; a page that names none falls back to the table's URL |
| Bloomberg TV, Bloomberg Originals | US | bloomberg.com/media-manifest |
| Cheddar | US | hls.livecdn.io |
| Newsmax | US | nmx1ota.akamaized.net |
| Fox Weather | US | 247wlive.foxweather.com |
| Al Jazeera English | Qatar | getaj.net |
| DW English | Germany | dwamdstream103.akamaized.net |
| France 24 English | France | live.france24.com (a media playlist) |
| NHK World-Japan | Japan | masterpl.hls.nhkworld.jp |
| ABC News (Australia) | Australia | abc-news-dmd-streams-1.akamaized.net |
| i24NEWS English | Israel | encoders.immergo.tv |
| DW Deutsch | Germany | dwamdstream102.akamaized.net |
| Das Erste, ARD alpha | Germany | daserste-live.ard-mcdn.de and mcdn.br.de |
| France 24 French | France | live.france24.com (a media playlist) |
| La 1, Canal 24 Horas | Spain | rtvelivestream.rtve.es |
| 3CatInfo | Spain | directes-tv-int.3catdirectes.cat |
| Euronews, Africanews | France | cdn-euronews.akamaized.net |
| TRT World | Turkey | tv-trtworld.medya.trt.com.tr |
| Al Jazeera Arabic, Al Jazeera Mubasher | Qatar | getaj.net |
| CNA | Singapore | Mediacorp's CloudFront distribution |
| Arirang TV | South Korea | amdlive-ch01-ctnd-com.akamaized.net |
| CGTN | China | news.cgtn.com |

There is no service guide. The `epg_ripper_US2` guide on epgshare01.online lists most of the US channels, under `CBS.News.National.Stream.us2`, `Bloomberg.HD.us2`, `Cheddar.us2`, `Newsmax.TV.HD.us2`, `NHK.World.TV.us2` and `i24.News.English.us2`; a lineup policy maps a station onto whichever configured guide carries it, and `PATCH /api/v1/admin/lineup/{id}` with `{"guide": {"guideId", "channelId"}}` maps one by hand.

Fragility: every URL is hard-coded, so a broadcaster that moves or retires a feed, or starts requiring a token, fails that one channel until the table is updated. A stale CDN can keep answering a master playlist whose variants are gone; the probe and the relay see that, so a dead feed strikes like any 404 ([source health](source-health.md)). Feeds published for one country can be geo-blocked elsewhere. A redesign of the CBS pages that drops the event URL leaves the fallbacks in place.

## Checking a provider

`scripts/provider-smoke.py --service NAME [--option key=value]` starts a disposable server with that one provider, waits for the import and adopts every channel. It then opens sessions on three channels, expecting an HLS master from each, decodes five seconds of two of them with FFmpeg, prints the counts and releases each session. It needs the built distribution and network access to the service. See [development](development.md#scripts) for the other scripts.
