# Lineup policy

A lineup policy is one JSON file that, together with `sources.json`, decides the whole lineup: its stations, their IDs, numbers, groups, logos, guides and sources. The same two files give the same lineup on any server, so a lineup can be rebuilt from them after a data loss or moved to another host.

Save it as `policy.json` in the data directory, or set `SOFA_POLICY` to its path, to turn it on. The server then builds the lineup from the policy. Without either, the server serves the lineup stored in its database, which changes only through the [admin API](api.md#admin-api).

## Setup

Keep the policy in a directory the server can write, not a single-file bind mount: the server writes numbers and API edits back with a rename in that directory. With the [Compose file](deploy.md), that is the config folder: save the policy as `deploy/config/policy.json` and restart. The portal's home page writes a [starter policy](starter-lineup.md) there and restarts for you while there is none.

The server reads and validates the file at start, and any error stops it. The file is never `${VAR}`-expanded.

A minimal policy, which takes every channel of two services, Samsung first:

```json
{
  "providers": [{ "id": "samsung" }, { "id": "pluto" }],
  "groups": { "default": "Entertainment" }
}
```

## Stations and keys

A station is one channel as the viewer sees it. Candidates join a station by their **key**, a normalized form of their name:

1. Unicode NFKD without combining marks, lowercase; `&` becomes `and` and `+` becomes `plus`.
2. Every run of characters other than letters and digits becomes one space; letters of any script survive.
3. A leading `the` and a trailing `hd` are dropped.
4. Every space is removed.

`names.version` selects the normalization: version 2, the default, takes all four steps; version 1 stops after step 3 and keeps the spaces. Under version 2 `The Bob Ross Channel` and `Bob Ross Channel` share the key `bobrosschannel`, `BBC News HD` joins `BBC News`, and `Weather Spy` joins `WeatherSpy`. A trailing `tv` or `channel` always stays, so `Tennis Channel`, `Tennis TV` and `Tennis+` are three stations, as are `NFL Network` and `NFL Channel`. A key depends on the name alone, never on what else the providers carry.

When two spellings name one station, join them with an alias in `names.aliases`. Both sides are normalized keys:

```json
"names": { "version": 2, "aliases": { "grio": "thegrio" } }
```

The station key is the normalized name through `names.aliases`, then through `names.learned` (see [Learned aliases](#learned-aliases)). An alias always wins over a learned alias of the same name. The findings route suggests aliases for live stations whose keys differ only by a trailing ` tv` or ` channel` or only by spaces (under version 1), and for stations whose names match loosely when no schedule can tell them apart.

### Learned aliases

Each run looks for stations that are probably one channel under two names, and merges them when their guides agree. Two stations pair up when one of their channels' names match loosely: the base words without `tv`, `channel`, `network`, `live`, `the` and `hd` and without a trailing `by ...`, with the spaces removed. So `TYT` and `TYT Network`, `The Hill` and `The Hill TV`, and `Modern Marvels` and `Modern Marvels by HISTORY` pair up, and so do `NFL Network` and `NFL Channel`.

For each pair where both stations have a guide, the run compares their programs over the next twelve hours. Programs match when their titles agree (lowercase, letters and digits only) and they start within five minutes of each other. With at least three matches, covering at least 60% of the smaller schedule, the two are one station. The one with fewer sources joins the one with more, and on a tie the key that sorts first wins. `NFL Network` and `NFL Channel` air different programs, so they stay apart. A pair with no schedule to compare stays apart and is listed as a suggested alias.

The run builds with what it learns, and an apply writes each one to `names.learned` as `{"from": "to"}`, with the assigned numbers, so a rebuild from the file merges the same stations without any guide. When the merged-away key had an override holding only a number the server assigned, that override goes with it; a station whose number was set by hand, or whose override holds anything more, is never merged away, and the findings say so.

`names.never` lists pairs of keys that never merge, learned or suggested:

```json
"names": { "version": 2, "never": [["nflnetwork", "nflchannel"]] }
```

A learned alias for a pair in `names.never` is ignored and removed at the next write.

## Fields

| Field | Default | Meaning |
| --- | --- | --- |
| `version` | `1` | Schema version; must be `1` |
| `names.version` | `2` | Normalization version, `1` or `2` |
| `names.aliases` | `{}` | Normalized key to station key. An alias may not point at another alias |
| `names.learned` | `{}` | Key to station key, written by runs from matching schedules; may not chain |
| `names.never` | `[]` | Pairs of keys, `[["a", "b"]]`, that never merge |
| `providers` | `[]` | Provider entries in priority order, see below |
| `groups.map` | `{}` | Lowercase raw group, or `provider:raw group` for one provider's category, to group; see [Groups](#groups). [Unmapped categories](#unmapped-categories) list what is missing |
| `groups.weak` | `[]` | Lowercase raw groups, or `provider:raw group` keys, that vote only when no other category does |
| `groups.patterns` | `[]` | `{"name": regex, "rawGroup": regex, "group": "..."}`, first match wins; give `name`, `rawGroup` or both |
| `groups.default` | unset | Group for a station nothing else groups; unset keeps the raw group |
| `groups.builtIn` | `true` | Consult the group map the server ships ([starter groups](starter-lineup.md#2-generate-the-policy)) for a category that `groups.map` and the `rawGroup` patterns leave out, and treat its weak list as weak too; `false` uses the policy's own alone |
| `numbering.floor` | `1000` | First number handed out when a station's own number is taken or not numeric |
| `numbering.keepProviderNumbers` | `true` | Use the leading channel's own number when it is free; ignored while `numbering.groups` is set |
| `numbering.groups` | `{}` | Group to the first number of its block, such as `{"News": 100, "Sports": 200}` |
| `numbering.assigned` | `[]` | Kept by the server: the station keys whose numbers it wrote. Only these numbers are reclaimed when a station has been gone for `keepMissingDays`; a number set or changed by hand leaves the list |
| `numbering.groupSize` | `100` | Numbers in each group's block; 1 to 100000 |
| `numbering.followGroup` | `false` | Move a station whose key is in `numbering.assigned` into its group's block when its number lies outside it |
| `health.disableAfterHours` | `24` | Hours without an eligible source before a station turns off; 1 to 720 |
| `health.enableOn` | `healthy` | `healthy`: a `HEALTHY` source turns it back on; `eligible`: any selectable source does |
| `health.keepMissingDays` | `7` | Days a station whose channels vanished stays, disabled, before it leaves |
| `health.requireVerified` | `true` | A station stays off until one of its sources has qualified healthy once |
| `maxSourcesPerStation` | `16` | Source cap per station; 1 to 64 |
| `stations` | `{}` | Overrides by station key, see below |
| `guideMatches.ignorePrefixes` | `[]` | Text, such as `US:`, that comes off the start of a station's and a guide channel's name before [guide matching](#guide-matches) compares them; case-insensitive, at most 100 |
| `guideMatches.ignoreSuffixes` | `[]` | The same for the end of a name, such as `FHD` |
| `guideMatches.acceptAbove` | `0.9` | The confidence, 0 to 1, Accept all takes a station's best proposal at |
| `guideMatches.dismissed` | `[]` | Written by the server: `{"station", "guideId", "channelId"}` proposals a person dismissed, never proposed again |

### Provider entries

Each entry is `{"id": "...", "include": FILTER, "exclude": FILTER, "enabled": true, "group": "..."}`. A channel takes the first entry whose `id` is its provider and whose filters pass it; no `include` admits everything. A filter has `groups` and `names` (exact, case-insensitive) and `groupPatterns` and `namePatterns` (case-insensitive regexes); any match passes. `enabled` is the default for stations that entry leads, and `group`, when set, is their group (see [Groups](#groups)); a station another entry leads keeps its own, so an entry at the end of the order that only fills in for other services never regroups their stations. A provider may appear more than once with different filters:

```json
"providers": [
  { "id": "localnow", "include": { "groups": ["My City"] } },
  { "id": "localnow", "include": { "groups": ["More Cities"] }, "enabled": false },
  { "id": "localnow" },
  { "id": "vizio", "exclude": { "groups": ["Local channels"] } }
]
```

A provider's narrow entries take the channels they match; end with a plain entry for the rest, or those channels match nothing. A provider with no entry contributes only through pins.

### Station overrides

`stations.<key>` takes:

| Field | Meaning |
| --- | --- |
| `id` | A UUID; otherwise the ID is derived from the key |
| `number` | The station number |
| `name`, `group`, `logo` | Override the leading channel's values |
| `guide` | `{"guideId": "...", "channelId": "..."}` |
| `gracenoteId` | The station's Gracenote (TMS) station id, 1 to 10 digits; the M3U output passes it to [Channels DVR](live-tv.md#channels-dvr) |
| `enabled` | Override the leading entry's default |
| `required` | Keep the station whatever its sources do, see below |
| `sources` | Pins: `{"provider": "...", "tvgId": "...", "name": "..."}` |
| `prefer`, `exclude` | Provider IDs that order or remove the station's unpinned sources |

A pin joins a provider's channel to the station by `tvg-id`, or by name when either side has no `tvg-id`, whatever the channel is called, and no other station takes it. A pin works whether or not its provider has an entry.

Validation rejects duplicate numbers and IDs, an override keyed by an alias or a learned alias, aliases or learned aliases that chain, a `names.never` entry that is not two different keys, and a pin claimed by two stations.

## Groups

A station's group is the first of:

1. Its override's `group`.
2. The `group` of the first pattern whose `name` matches the station's name.
3. The `group` of the provider entry that leads the station.
4. The vote of its sources. Each source's raw category maps through `groups.map`, trying `provider:raw group` before `raw group`, and then through the first pattern whose `rawGroup` matches it. Each provider counts once per group, and the group with the most providers wins; a tie goes to the group voted for first, in leading order. Categories in `groups.weak` vote only when no other category does, so a broad bucket such as `TV`, `Featured` or `Movies and Series` decides a station only when nothing more specific names it. With `groups.builtIn`, a category the policy leaves out is looked up in the shipped group map, and its weak list applies too; the policy's own entries always win. A category nothing maps casts no vote.
5. `groups.default`.
6. The leading channel's raw category.

```json
"groups": {
  "map": { "news": "News", "reality": "Lifestyle", "movies and series": "Movies", "plex:classics": "Movies" },
  "weak": ["movies and series", "featured"],
  "patterns": [{ "name": "\\bnoticias\\b", "group": "News" }],
  "default": "Entertainment"
}
```

## How a run works

A run is deterministic: everything sorts on values, never on provider or file order.

1. **Channels.** Each provider entry takes the live channels that pass its filters and that no earlier entry took. Pinned channels go to their station first. Channels with no current source never start or join a station.
2. **Stations.** Channels group by key. The leading channel, the lowest by (entry, channel number, channel ID), gives the name, logo and raw group.
3. **Learned aliases.** Stations whose names match loosely are compared by schedule, and those that match merge, as [Learned aliases](#learned-aliases) describes. The steps below run over the merged stations.
4. **Sources.** Pinned sources first, then the rest by `prefer`, entry, channel number and channel ID, without excluded providers, up to the cap.
5. **Fields.** Overrides first, then the leading channel; the group comes from the override, a name pattern, the sources' vote or the default, as [Groups](#groups) describes.
6. **IDs.** The override `id`, else one derived from the key.
7. **Numbers.** Reserved first: every number in the file (including a station that just left) and the override numbers.
   - With `numbering.groups`, a station takes the next free number in its group's block (`groupSize` numbers, or up to the next block's start). A full or missing block falls back to the next free number from the floor. Stations the policy keeps off (`enabled: false`, or an override) always number from the floor, so the blocks hold what viewers can see.
   - Without it, a station gets the leading channel's number when it is free and `keepProviderNumbers` is set, else the next free number from the floor.
   - Each number given is written back as `stations.<key>.number`, so the file holds the whole numbering, and its key joins `numbering.assigned` (the sorted list of keys the server numbered). A numbered station is never renumbered.
   - With `numbering.followGroup`, an enabled, verified station in `numbering.assigned` that lies outside its block takes the next free number in it, and the file takes the new number. Its old number stays reserved until the next run, and a full block keeps the station's number. Numbers set by hand never move.
   - A station with `health.requireVerified` that no source has verified gets a floor number for that run only, unwritten; once verified it takes its block number, which is written.
   - A station gone for `health.keepMissingDays` releases its number if its key is in `numbering.assigned`: the number and key leave the file. A number set by hand or by `PATCH` is never released, and a `PATCH` that sets a number removes its key from `numbering.assigned`.
8. **Guide.** The override, else a guide candidate from each source in station order. A source's candidate is its own provider's guide when it lists the channel, else any configured guide listing the source's `tvg-id` that is not another provider's. Of these candidates the station takes the first with programs ahead, else the first. While a station's guide has nothing ahead, the server keeps programs for all its candidates, so the next run can move it to one that has. A station that moved keeps its first candidate's programs too, so it moves back once that guide fills again. Each station's candidates are listed as `guideCandidates`.
9. **Availability.** With `requireVerified`, a station stays off, with reason `unverified`, until one of its sources has qualified healthy once; the run records when as the station's `verifiedAt`. After that, and always without `requireVerified`, a station with no eligible source for `disableAfterHours` turns off until a source qualifies. A station whose channels vanish stays, disabled, for `keepMissingDays`; a key an alias joins into another station merged and does not linger.
10. **Write.** The lineup is written in one transaction, in number order, only when it differs from the served one. Each change goes to the [change log](#api) with the station key, the field, before and after, and the cause. A station that fails validation is reported and left as it was. Numbers and learned aliases are then written back to the policy file in one revision-checked write.

Runs follow every provider import, guide import and health change, after ten quiet seconds, and every 900 seconds. Until some provider has a live channel, a run leaves the lineup alone.

## Probing

With a policy, the probe worker runs whatever the providers' `probe` flags say, and probes only the sources the last apply's stations reference. The order, tiers included, is in [source health](source-health.md#automatic-checks). While stations wait, Home's Providers tile estimates how long the first two tiers take from the rate probes finished over the last ten minutes; a new lineup of about a thousand stations takes around an hour. Without a policy, only providers with `probe` set are probed.

## Required stations

`"required": true` keeps a station in the lineup whatever happens to its sources:

- A required station with no live source stays, disabled, and the run reports `required station has no live source`.
- A pinned source missing from its provider is reported as `required source missing`. When the provider still lists the channel without a current source, the station keeps the sources that pin last resolved to, as long as the provider still has them, and says so.
- A required station never leaves the lineup for being missing.
- With `requireVerified`, a required station waits for a verified source like any other, in the lineup and disabled.

## Findings

Each run reports findings, each starting with the station key; the `groups:` summary of unmapped categories is the exception. Findings cover required or pinned sources missing, stations over the source cap, missing stations, and stations left as they were. They also cover schedule matches the policy kept from merging, because of an override holding more than a number or a `names.never` pair. With HDHomeRun range devices, a lineup that needs more devices than are configured adds an `hdhr:` finding. `GET /api/v1/admin/policy/findings` returns the open findings of a run over the current candidates, and the suggested aliases. With [Plex sync](live-tv.md#keep-plex-in-step) configured, it also carries the last sync's `plex:` findings.

### Guide matches

A station takes its guide from the guides that list one of its sources' `tvg-id`. For a station that has none, guide matching proposes guide channels by name. Both names lose the `guideMatches` prefixes and suffixes, then compare in their loose form: the name normalization without the words tv, channel, network, live, the and hd, and without spaces. A guide channel's ID before its first `.`, as in `CNN.us`, counts as a second name. Equal loose names score a confidence of 1. A name that shares a word with the station's scores its letter-pair (Dice) similarity, at most 0.95, and is listed from 0.6. Each station lists its best three, best first. Schedules do not count: a station with no guide has no programs to compare.

Accepting a proposal writes it as the station's `guide` override, the same field `PATCH /lineup/{id}` writes, and applies. Accept all does so in one write for every station whose best proposal reaches `acceptAbove`, except a station whose best two proposals tie on different channels. Dismissing a proposal adds it to `guideMatches.dismissed`. The portal lists proposals on the Guide matches tab; the API is [below](#api).

### iptv-org metadata

The [iptv-org](https://github.com/iptv-org/database) community database lists channels with their names, countries, categories and logos, and a blocklist of channels it asks clients not to list. With `SOFA_IPTV_ORG=on` (off by default, switched on the Settings page or with the MCP tool `set_runtime_setting`), the server downloads `channels.json`, `logos.json`, `blocklist.json` and `timezones.json` from `iptv-org.github.io/api` when it is switched on and then weekly, and uses them under a lineup policy. It downloads metadata only, never `streams.json`; the community streams are a separate, also optional, [provider](providers.md#iptv-org-community-streams). The files are kept in `iptv-org/` in the data directory and requested with `If-None-Match` and `If-Modified-Since`, each capped at 64 MiB, through the same address policy as every outbound request. A failed download, or a `channels.json` with fewer than half the channels the last good copy had, is refused and the job's note says so; the last good copy stays in use.

A lineup channel matches an iptv-org channel in this order:

1. Its `tvg-id` equals an iptv-org id such as `CNN.us`.
2. Its name, normalized like station keys (version 2, no spaces), equals exactly one iptv-org channel's name in a country the channel is tagged with.
3. Its name equals an iptv-org name with no country to check, or an alternative name (`alt_names`).

Only the first two apply on their own. A match of the third kind, and a name that fits several iptv-org channels, is never guessed: it is listed on the portal's iptv-org tab and as a finding, `<key>: iptv-org candidates ...`, until you accept one channel or dismiss the station. The choice is kept in the database, and the policy runs again. `POST /api/v1/admin/iptv-org/matches` does the same.

A match fills only what the source left empty: the logo (through the logo cache, like any logo), and, for a channel with no category at all, the first iptv-org category and the country as its tags, which then go through `groups.map` like a provider's own. An explicit logo, category or station override is never replaced.

A station whose match is on the blocklist is turned off with the reason `blocklist`, and its finding names the blocklist's reason and reference. Nothing is deleted. Dismissing the station's match on the iptv-org tab lists it again, as does turning the station on with `enabled: true` in its override.

### Unmapped categories

Each run lists the raw categories that no `groups.map` entry, `rawGroup` pattern or, with `groups.builtIn`, shipped mapping names and that neither `groups.weak` nor the shipped weak list holds, in `unmappedCategories`, in preview, apply and findings. Every source of a station counts, not only the leading one. There is one entry per provider and category, `{"provider", "category", "stations", "example"}`, with the most stations first, then provider and category. `category` is the lowercase key `groups.map` takes, and `example` is the first station name in sorted order. Stations with a group override, a matching name pattern or a leading provider entry with a `group` are not listed, and neither are sources with no raw category. When the list is not empty, the findings carry one `groups:` line with the category and station counts.

To map a category, add its `category` to `groups.map` with the group it belongs in, then apply. A category that says nothing about the genre goes in `groups.weak`.

## API

All routes are under `/api/v1/admin` and take the admin token.

The routes under `/api/v1/admin/policy` (read, replace, preview, apply, findings, changes, guide matches), `/stations/{key}` and `/lineup/{id}` are in the [admin API](api.md#admin-api). `PATCH /lineup/{id}` writes `name`, `number`, `group`, `logo`, `guide`, `gracenoteId`, `enabled` and `offerings` (as pins by provider and `tvg-id`) into the station's override, then applies; `DELETE` writes `enabled: false`.

Every write holds one lock, writes a temp file in the policy's directory, syncs it to disk and renames it over the policy. When the file changed on disk since the server read it, the server reloads it and the write fails with `409 POLICY_CHANGED`; read the policy again and retry. `PATCH` and `DELETE` accept `If-Match` too.

While a policy is configured, routes that edit the lineup by hand answer `410 Gone` with code `POLICY_MANAGED`: `POST /lineup`, `PUT /lineup/order` and `POST /lineup/adopt`.

## HDHomeRun devices

With a policy, the HDHomeRun devices split the lineup by range instead of by hash: the first `SOFA_HDHR_DEVICE_SIZE` enabled channels (default 250) in number order go on the first device, the next on the second, and so on. Adding a channel moves only the channels after it in its range. There are `SOFA_HDHR_EXTRA_PORTS` plus one devices; channels beyond their capacity go on the last device, and the server logs it and reports an `hdhr:` finding. Preview and apply report `devicesNeeded`, the enabled channels divided by `SOFA_HDHR_DEVICE_SIZE` and rounded up, beside `devicesConfigured`; add ports until the two agree, then add the new devices in Plex.

## Generate a policy

`scripts/generate-policy.py` writes a policy from the running server, with the starter group map, the number blocks and the aliases. [Build a starter lineup](starter-lineup.md#2-generate-the-policy) has the command, the blocks, turning it on and rolling back.
