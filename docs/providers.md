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
    { "id": "plex", "type": "native", "service": "plex" }
  ]
}
```

| Field | Default | Description |
| --- | --- | --- |
| `type` | `m3u` | `native` selects a service client; `playlistUrl` must then be absent |
| `service` | Required | One of `whale`, `vizio`, `lg`, `freelivesports`, `hallmark`, `ballysports`, `adultswim`, `pbs`, `samsung`, `plex` |
| `options` | `{}` | Service options, string values: `zip` or `stations` for `pbs`, `region` for `samsung` |
| `refreshSeconds` | `3600` | Channel list refresh for a native provider (900 for a playlist); minimum 60 |
| `guideUrl` | Service default | An XMLTV to use instead of the service's own guide; `samsung` defaults to the i.mjh.nz XMLTV for its region |
| `probe` | `true` | Probes resolve the channel and qualify the resolved URL like any source |

`headers`, `defaultProtocol`, `allowEmpty` and `renewableQuery` apply to playlists only; every native source is HLS.

A native provider with a guide of its own appears in `/api/v1/admin/guides` under the provider id, refreshed hourly; its channel ids are the service's own, and a lineup entry created from a candidate maps onto it automatically. The guide holds every channel in its index and programmes only for channels the lineup maps, so a service whose guide is fetched per channel (Whale, Hallmark, Adult Swim, PBS, Plex) is asked only about mapped channels.

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

## Checking a provider

`scripts/provider-smoke.py --service NAME [--option key=value]` starts a disposable server with that one provider, waits for the import, adopts every channel, opens sessions on three channels and expects an HLS master from each, decodes five seconds of two of them with FFmpeg, and prints the counts. It needs the built distribution and network access to the service.
