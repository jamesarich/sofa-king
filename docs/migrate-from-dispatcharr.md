# Migrate from Dispatcharr

This guide moves a Dispatcharr profile into Sofa King Backend: its channels, numbers, groups, logos, failover order and guide mapping, and the curation its pipeline performed. Once the lineup is imported, this server serves it and Dispatcharr is no longer needed.

The export contains tokenized stream URLs. Keep it out of version control and delete it after the import.

## Configure the providers and guides

Configure this server with the same inputs Dispatcharr uses: each M3U account as a provider and each XMLTV source as a guide, as in the [quick start](../README.md#sofa-king-fast-setup). Start the server and wait until `/api/v1/providers` and `/api/v1/admin/guides` show `lastSuccessAt` for every source.

## Export the profile

The export runs inside the Dispatcharr container and reads its database only. Replace `dispatcharr` with the container name and `sofaking` with the profile name:

```sh
docker exec -i -e SOFA_PROFILE=sofaking -e SOFA_OUT=/tmp/sofaking.json \
  dispatcharr python manage.py shell < scripts/dispatcharr-export.py
docker cp dispatcharr:/tmp/sofaking.json ./sofaking.json
docker exec dispatcharr rm /tmp/sofaking.json
```

The export writes a `sofa-dispatcharr-export/1` file. Standard output shows counts only.

## Preview the import

```sh
SOFA_ADMIN_TOKEN='TOKEN' python3 scripts/dispatcharr-import.py --file sofaking.json --dry-run
```

Streams match sources by [source key](live-tv.md#source-identity), so renewed tokens do not matter, and entries without a `tvg-id` match like any other. A stream whose identity query changed still matches when exactly one source shares its path. A stream whose path the provider no longer lists, for example after the provider renamed its channel ids, is reported unmatched; refresh the M3U account in Dispatcharr or replace the entry's source after the import.

EPG sources match configured guides by URL (query ignored), then by a guide ID equal to the Dispatcharr source name. The report lists every EPG source that did not match and every entry with unmatched streams or an unmapped guide source. A channel whose streams all fail to match is skipped unless `--include-unmatched` is given. A channel without a number or name is skipped with an `error` and does not stop the rest. Overrides stored on Dispatcharr's channels win over the synced values, and a channel hidden from output exports as disabled.

## Map the EPG sources and apply

Map each EPG source the report flags, then apply:

```sh
SOFA_ADMIN_TOKEN='TOKEN' python3 scripts/dispatcharr-import.py --file sofaking.json --guide-map 4=gracenote
```

Entries keep Dispatcharr's channel uuid as their ID, so running the import again updates them in place. The mapped guides refresh, and `GET /api/v1/channels` shows the lineup.

## Clean up

Delete `sofaking.json`. Point clients at this server, and retire Dispatcharr's M3U and EPG output. If a pipeline curated Dispatcharr, move its rules over as in [cut over from the pipeline](#cut-over-from-the-pipeline).

## Import API

`POST /api/v1/admin/lineup/import` takes up to 16 MiB:

```json
{
  "export": { "format": "sofa-dispatcharr-export/1", "profile": "sofaking", "epgSources": [...], "channels": [...] },
  "guideMap": { "4": "gracenote" },
  "dryRun": false,
  "includeUnmatched": false
}
```

`export` is the file `scripts/dispatcharr-export.py` writes. Each `epgSources` item has `id`, `name` and `url`. Each channel has `uuid`, `number`, `name`, `group`, `logo`, `tvgId`, `epgSourceId`, `epgChannelId`, `enabled` and ordered `streams` (`url`, `name`, `provider`).

Streams match sources by [source key](live-tv.md#source-identity): the URL without the provider's renewable query parameters, tried under every provider's list. When no source matches that way and exactly one source shares the stream's `scheme://authority/path`, that path alone decides. An EPG source matches a configured guide by `scheme://authority/path` with the query ignored, then by a guide ID equal to its name, then by `guideMap` (`epgSourceId` to guide ID), which also overrides the automatic match. Entries take the channel `uuid` as their ID (a derived UUID when absent) and are created or updated in place. A channel with no matching stream is skipped unless `includeUnmatched` is true.

The report has `dryRun`, `created`, `updated`, `skipped`, `matchedStreams`, `unmatchedStreams`, `guideSources` (EPG source ID to guide ID or null) and `entries`. Each entry has `number`, `name`, `uuid`, `action` (`created`, `updated`, `skipped`), `matchedStreams`, `unmatchedStreams` (source keys under the default renewable list), `guide` (`mapped`, `unmapped-source`, `none`) and, for a channel skipped because its number or name is invalid, `error`.

## Curation mapping

The Dispatcharr pipeline made the decisions below. This table shows where each one lives in Sofa King Backend. Rules are described in [curation](curation.md#rules).

| Pipeline decision | Here |
| --- | --- |
| Group mapping: a provider's `group-title` maps to a genre through a lowercase lookup table. A one-letter group falls back to name patterns (`sport|espn|tennis|...` to Sports, `news|weather|cnn|cnbc` to News, else Entertainment). An explicit group wins | `group` rules: an exact lowercase match on the raw group, a regex on the raw group, or a regex on the entry name. The first matching rule in position order applies, and a pinned `group` wins. `POST /api/v1/admin/curation/import` takes the lookup table as `groupMap` |
| Language is not a genre (`Spanish Language` and `International` map to Entertainment) | The same `group` rules; the lookup table carries those rows |
| Name and logo corrections for stations a feed mislabels (`MLB Network` published as `MLB Channel`) | `correction` rules, matched on the candidate's exact name, `tvg-id` or provider, setting `name`, `group` or `logo` on the entry that carries the candidate. The import takes `sportsCorrections` (`beforeName` to `afterName`) |
| Spanish-station corrections and the NFL Channel and NFL Network stream split | Already in the lineup: they were Dispatcharr overrides and stream links, and the import carries the effective names, groups, guides and stream order. The import reports these records as `applied-by-import`; no rule is created. New candidates with those names are handled by `correction` rules |
| Aliases: duplicate channels (equal exact name and a shared guide identity or provider station id) are clustered, one winner keeps the others' streams as backups, and the losers leave the curated profile | `alias` rules: the alias entry's sources are appended to the canonical entry's failover order, and the alias entry is disabled by automation. Neither entry is deleted. The alias keeps its UUID and comes back when enabled or when the rule is removed. Candidate pairs that look like duplicates are listed as findings in the preview, never merged on their own |
| Equivalent-source attachment: a channel gains the stream of another channel with the same exact name after review | Automatic when the evidence is an exact lowercase name match and a shared `tvg-id` or guide channel. Name-only attachment needs an `equivalent` rule naming the entry and the candidate name. Attached sources are appended, and health ranks them at play time |
| Promotion: the fastest-starting stream that decoded moves to the front | Not implemented. Playback picks healthy sources before unverified before suspect, in lineup order within a rank. A reorder of `offerings` is pinned |
| Nightly playback sweep: every channel is decoded for two seconds through the proxy, and a channel that fails twice drops from the curated profile | Per-source probes ([source health](source-health.md)) with strikes and quarantine replace the channel probe. An entry whose sources are all ineligible for `disableAfterHours` is disabled by automation and re-enabled when a source is healthy again (`enableOn`). Unverified sources stay eligible |
| Health gate flag | `settings.enabled`. When off, the server previews and tracks availability but changes nothing |
| Empty feed: an export with no curated channels publishes an empty profile | A failed or empty provider import keeps the previous snapshot (`allowEmpty`). Withdrawn sources leave the entry in place and skip it for playback |
| Logo sourcing: logo rows served through a separate logo host | Every output serves `/logos/KEY` from the [logo cache](api.md#logos), which keeps a fetched copy when its host goes away. A channel's logo is the entry's `logo`, else the first source's feed logo, else the guide channel's icon. A host in `retiredLogoHosts` makes its logos count as absent |
| Numbering: Dispatcharr numbers, unique at export | Entries keep their numbers. An adopted candidate takes its own number when it is numeric and free, else the lowest free integer at or above `numberFloor`. Duplicate numbers among enabled entries are a preview finding |
| New channels: Dispatcharr synced them into a profile automatically | Nothing enters the lineup on its own. An `adopt` rule (provider, raw group, name pattern) creates entries for unreferenced candidates, disabled unless the rule says `enabled`. A candidate that is an equivalent of an existing entry is attached instead. A candidate adopted once is never adopted again |
| Generations, atomic symlink publish, rollback manifests | Not needed. The lineup is one SQLite transaction and every output renders from it on request. Automation writes a change log (`GET /api/v1/admin/curation/changes`) |
| Reference enrichment (country, languages, station ids, tags) | Not used. Clients read `tags` as the group only, and no output consumes the other fields |

## Pipeline data import

`POST /api/v1/admin/curation/import` takes the JSON files a pipeline keeps and creates rules from them:

```json
{
  "groupMap": { "us: sports": "Sports", "news & opinion": "News" },
  "aliases": { "ALIAS_UUID": "CANONICAL_UUID" },
  "equivalents": [ { "uuid": "ENTRY_UUID", "name": "Station Name" } ],
  "sportsCorrections": [ { "beforeName": "MLB Network", "afterName": "MLB Channel" } ],
  "corrections": { "relabelled": 8171, "correctGuides": { "8171": 3867 } },
  "dryRun": true
}
```

`groupMap` becomes one `group` rule per row, `aliases` one `alias` rule per pair whose two UUIDs are lineup entries, `equivalents` one `equivalent` rule per item, and `sportsCorrections` one `correction` rule per record with `beforeName` and `afterName`. Records without those fields and the whole `corrections` object are reported as `applied-by-import`. A rule equal to an existing one is reported as `exists`. The report lists every rule created and every item skipped, with its reason.

The import takes up to 4 MiB with any of `groupMap`, `aliases`, `equivalents`, `sportsCorrections`, `corrections` and `dryRun`. Fields the rules do not use are ignored.

## Cut over from the pipeline

1. Import the lineup and let the probes qualify the sources.
2. Post the pipeline's data files to the import endpoint with `dryRun` first, then without. Include `aliases` so that the duplicates it names are rules from the start. Until the alias rules exist, a new candidate for one of those stations matches two entries and is a finding instead of an attachment.
3. Read `GET /api/v1/admin/curation/preview` and pin whatever the plan would change that should stay as it is.
4. Set `settings.enabled` to `true`.
5. Point clients at this server. The Sofa King app reads `/api/v1`. M3U and XMLTV players read the [player outputs](live-tv.md#third-party-players), and Plex reads the [HDHomeRun routes](live-tv.md#plex-live-tv). Until then, clients keep reading the old pipeline output.
6. List the logo hosts the lineup names (`GET /api/v1/admin/lineup`, the `logo` fields). Put the hosts that are going away in `retiredLogoHosts`. Check the preview's `clear-logo` actions against entries whose sources and guide carry no logo of their own.
7. Put the guides fed by retiring services in `retiredGuides` and run. After the next guide refresh, compare each channel's `guideStatus` (`available`, `empty`, `unassigned`) with the count before. A channel still mapped to a retired guide has no source another guide lists.
8. Remove the feed-refresh schedule and whatever runs the playback refresh, then stop the old pipeline and Dispatcharr. The server reads nothing from them.
