# Lineup policy

A lineup policy is one JSON file that, together with `sources.json`, decides the whole lineup: its stations, their IDs, numbers, groups, logos, guides and sources. The same two files give the same lineup on any server, so a lineup can be rebuilt from them after a data loss or moved to another host.

Set `SOFA_POLICY` to the file's path to turn it on. The server then builds the lineup from the policy, and the [curation](curation.md) engine does not run. Without `SOFA_POLICY` the server keeps the curated lineup exactly as before.

## Setup

Keep the policy in a directory the server can write, not a single-file bind mount: the server writes numbers and API edits back with a rename in that directory. With Docker Compose:

```yaml
    environment:
      SOFA_POLICY: /config/policy/policy.json
    volumes:
      - ./policy:/config/policy
```

The server reads and validates the file at start, and any error stops it. The file is never `${VAR}`-expanded.

A minimal policy, which takes every channel of two services, Samsung first:

```json
{
  "providers": [{ "id": "samsung" }, { "id": "pluto" }],
  "groups": { "default": "Entertainment" }
}
```

## Stations and keys

A station is one channel as the viewer sees it. Feed channels join a station by their **key**, a normalized form of their name:

1. Unicode NFKD without combining marks, lowercase; `&` becomes `and` and `+` becomes `plus`.
2. Every run of characters other than letters and digits becomes one space; letters of any script survive.
3. A leading `the` and a trailing `hd` are dropped.
4. Every space is removed.

`names.version` selects the normalization: version 2, the default, takes all four steps; version 1 stops after step 3 and keeps the spaces. Under version 2 `The Bob Ross Channel` and `Bob Ross Channel` share the key `bobrosschannel`, `BBC News HD` joins `BBC News`, and `Weather Spy` joins `WeatherSpy`. A trailing `tv` or `channel` always stays, so `Tennis Channel`, `Tennis TV` and `Tennis+` are three stations, as are `NFL Network` and `NFL Channel`. A key depends on the name alone, never on what else the feeds carry.

When two spellings name one station, join them with an alias in `names.aliases`. Both sides are normalized keys:

```json
"names": { "version": 2, "aliases": { "grio": "thegrio" } }
```

The station key is the normalized name through `names.aliases`, then through `names.learned` (see [Learned aliases](#learned-aliases)). An alias always wins over a learned alias of the same name. The findings route suggests aliases for live stations whose keys differ only by a trailing ` tv` or ` channel` or only by spaces (under version 1), and for stations whose names match loosely when no schedule can tell them apart.

### Learned aliases

Each run looks for stations that are probably one channel under two names, and merges them when their guides agree. Two stations pair up when one of their channels' names match loosely: the base words without `tv`, `channel`, `network`, `live`, `the` and `hd` and without a trailing `by ...`, with the spaces removed. So `TYT` and `TYT Network`, `The Hill` and `The Hill TV`, and `Modern Marvels` and `Modern Marvels by HISTORY` pair up, and so do `NFL Network` and `NFL Channel`.

For each pair where both stations have a guide, the run compares their programmes over the next twelve hours. Programmes match when their titles agree (lowercase, letters and digits only) and they start within five minutes of each other. With at least three matches, covering at least 60% of the smaller schedule, the two are one station: the one with fewer sources joins the one with more (on a tie, the key that sorts first). `NFL Network` and `NFL Channel` air different programmes, so they stay apart. A pair with no schedule to compare stays apart and is listed as a suggested alias.

The run builds with what it learns, and an apply writes each one to `names.learned` as `{"from": "to"}`, with the assigned numbers, so a rebuild from the file merges the same stations without any guide. When the merged-away key had an override holding only a number, that override goes with it; a station whose override holds anything more is never merged away, and the findings say so.

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
| `groups.map` | `{}` | Lowercase raw group to group; [unmapped categories](#unmapped-categories) list what is missing |
| `groups.patterns` | `[]` | `{"name": regex, "rawGroup": regex, "group": "..."}`, first match wins; give `name`, `rawGroup` or both |
| `groups.default` | unset | Group for a station nothing else groups; unset keeps the raw group |
| `numbering.floor` | `1000` | First number handed out when a station's own number is taken or not numeric |
| `numbering.keepProviderNumbers` | `true` | Use the leading channel's own number when it is free; ignored while `numbering.groups` is set |
| `numbering.groups` | `{}` | Group to the first number of its block, such as `{"News": 100, "Sports": 200}` |
| `numbering.assigned` | `[]` | Kept by the server: the station keys whose numbers it wrote. Only these numbers are reclaimed when a station has been gone for `keepMissingDays`; a number set or changed by hand leaves the list |
| `numbering.groupSize` | `100` | Numbers in each group's block; 1 to 100000 |
| `health.disableAfterHours` | `24` | Hours without an eligible source before a station turns off; 1 to 720 |
| `health.enableOn` | `healthy` | `healthy`: a `HEALTHY` source turns it back on; `eligible`: any selectable source does |
| `health.keepMissingDays` | `7` | Days a station whose channels vanished stays, disabled, before it leaves |
| `health.requireVerified` | `true` | A station stays off until one of its sources has qualified healthy once |
| `maxSourcesPerStation` | `16` | Source cap per station; 1 to 64 |
| `stations` | `{}` | Overrides by station key, see below |

### Provider entries

Each entry is `{"id": "...", "include": FILTER, "exclude": FILTER, "enabled": true}`. A channel takes the first entry whose `id` is its provider and whose filters pass it; no `include` admits everything. A filter has `groups` and `names` (exact, case-insensitive) and `groupPatterns` and `namePatterns` (case-insensitive regexes); any match passes. `enabled` is the default for stations that entry leads. A provider may appear more than once with different filters:

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
| `enabled` | Override the leading entry's default |
| `required` | Keep the station whatever its sources do, see below |
| `sources` | Pins: `{"provider": "...", "tvgId": "...", "name": "..."}` |
| `prefer`, `exclude` | Provider IDs that order or remove the station's unpinned sources |

A pin joins a provider's channel to the station by `tvg-id`, or by name when either side has no `tvg-id`, whatever the channel is called, and no other station takes it. A pin works whether or not its provider has an entry.

Validation rejects duplicate numbers and IDs, an override keyed by an alias or a learned alias, aliases or learned aliases that chain, a `names.never` entry that is not two different keys, and a pin claimed by two stations.

## How a run works

A run is deterministic: everything sorts on values, never on feed or file order.

1. **Channels.** Each provider entry takes the live channels that pass its filters and that no earlier entry took. Pinned channels go to their station first. Channels with no current source never start or join a station.
2. **Stations.** Channels group by key. The leading channel, the lowest by (entry, channel number, channel ID), gives the name, logo and raw group.
3. **Learned aliases.** Stations whose names match loosely are compared by schedule, and those that match merge, as [Learned aliases](#learned-aliases) describes. The steps below run over the merged stations.
4. **Sources.** Pinned sources first, then the rest by `prefer`, entry, channel number and channel ID, without excluded providers, up to the cap.
5. **Fields.** Overrides first, then the group map, patterns and default, then the leading channel.
6. **IDs.** The override `id`, else one derived from the key.
7. **Numbers.** Override numbers are reserved first. With `numbering.groups`, the rest each get the next free number in their group's block, which runs `groupSize` numbers or to the next block's start, whichever comes first, or the next free number from the floor once the block is full or the group has none. Stations the policy keeps off (an entry with `enabled: false`, or an override) always number from the floor, so the blocks hold what viewers can see. Without it, they get the leading channel's number when it is free and `keepProviderNumbers` is set, else the next free number from the floor. Every number in the file is reserved, including a station's that has just left. Each number given this way is written back to the policy file as `stations.<key>.number`, so the file holds the whole numbering, and its key joins `numbering.assigned`, the sorted list of keys the server numbered. A station with a number in the file is never renumbered. A station with `health.requireVerified` that no source has verified yet gets a floor number for the run only, which is not written; once it verifies, it takes its block number and that is written. A station gone for `health.keepMissingDays` releases its number when its key is in `numbering.assigned`: the number and its key leave the file, and the number is free for the next station. A number set by hand, or by a `PATCH` that sets one, is never released, and a `PATCH` that sets a number removes its key from `numbering.assigned`.
8. **Guide.** The override, else the guide of the leading source's own provider when it lists the channel, else any configured guide listing a source's `tvg-id` that is not another provider's.
9. **Availability.** With `requireVerified`, a station stays off, with reason `unverified`, until one of its sources has qualified healthy once; the run records when as the station's `verifiedAt`. After that, and always without `requireVerified`, a station with no eligible source for `disableAfterHours` turns off until a source qualifies. A station whose channels vanish stays, disabled, for `keepMissingDays`; a key an alias joins into another station merged and does not linger.
10. **Write.** The lineup is written in one transaction, in number order, only when it differs from the served one. Each change goes to the change log with the station key, the field, before and after, and the cause. A station that fails validation is reported and left as it was. Numbers and learned aliases are then written back to the policy file in one revision-checked write.

Runs follow every feed import, guide import and health change, after ten quiet seconds, and every 900 seconds. Until some feed has a live channel, a run leaves the lineup alone.

## Probing

With a policy, the probe worker runs whatever the providers' `probe` flags say, and probes only the sources the last apply's stations reference; a source no station references is never probed. It takes them in three tiers, at `SOFA_PROBE_PARALLELISM` at a time: first the first source of each unverified station, then those stations' other sources, then every other referenced source by the usual order (suspect and quarantined first, then unverified, then healthy rechecks). A station the policy turns off is never in the first two tiers. Without a policy, only providers with `probe` set are probed.

A source whose provider configures `headers` other than `User-Agent`, `Accept`, `Accept-Language`, `Origin` and `Referer` is never probed, so with `requireVerified` a station whose only sources come from such a provider stays off.

## Required stations

`"required": true` keeps a station in the lineup whatever happens to its sources:

- A required station with no live source stays, disabled, and the run reports `required station has no live source`.
- A pinned source missing from its feed is reported as `required source missing`. When the feed still lists the channel without a current source, the station keeps the sources that pin last resolved to, as long as the feed still has them, and says so.
- A required station never leaves the lineup for being missing.
- With `requireVerified`, a required station waits for a verified source like any other, in the lineup and disabled.

## Findings

Each run reports findings, each starting with the station key (the `groups:` summary of unmapped categories is the exception): required or pinned sources missing, stations over the source cap, missing stations, stations left as they were, and schedule matches the policy kept from merging (an override holding more than a number, or a `names.never` pair). With HDHomeRun range devices, a lineup that needs more devices than are configured adds an `hdhr:` finding. `GET /api/v1/admin/policy/findings` returns the open findings of a run over the current feeds, and the suggested aliases. With [Plex sync](live-tv.md#keep-plex-in-step) configured, it also carries the last sync's `plex:` findings.

### Unmapped categories

A station whose leading channel has a raw category that `groups.map` and `groups.patterns` do not name takes `groups.default`, or keeps the raw category when there is none. Each run lists these in `unmappedCategories`, in preview, apply and findings: one entry per provider and category, `{"provider", "category", "stations", "example"}`, with the most stations first, then provider and category. `category` is the lowercase key `groups.map` takes, and `example` is the first station name in sorted order. Stations with a group override are not listed, and neither are stations with no raw category. When the list is not empty, the findings carry one `groups:` line with the category and station counts.

To map a category, add its `category` to `groups.map` with the group it belongs in, then apply. A category that should stay in the default group needs no entry.

```json
"groups": { "map": { "news": "Headlines", "lifestyle": "Lifestyle" }, "default": "Entertainment" }
```

## API

All routes are under `/api/v1/admin` and take the admin token.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/policy` | The policy with its `revision` (the file's SHA-256, also the `ETag`) and `path` |
| PUT | `/policy` | Replace the policy; `If-Match: <revision>` is required. Validates, writes, applies, and returns the document and the apply result |
| GET | `/policy/preview` | What a run would change against the served lineup, the aliases it would learn (`learned`), the `unmappedCategories`, and with range devices `devicesNeeded` and `devicesConfigured`; writes nothing |
| POST | `/policy/apply` | Run now and write the result; reports `enabledStations`, the numbers it `assigned`, the aliases it `learned`, the `unmappedCategories`, and with range devices `devicesNeeded` and `devicesConfigured` |
| GET | `/policy/findings` | Open findings, suggested aliases and `unmappedCategories` |
| POST | `/policy/check` | The cutover check, see below |
| GET | `/stations/{key}` | One station: its pins and every channel that names the key or joins it, with why |
| PATCH | `/lineup/{id}` | Writes `name`, `number`, `group`, `logo`, `guide`, `enabled` and `offerings` (as pins by provider and `tvg-id`) into the station's override, then applies |
| DELETE | `/lineup/{id}` | Writes `enabled: false` into the station's override, then applies |

Every write holds one lock, writes a temp file in the policy's directory, syncs it to disk and renames it over the policy. When the file changed on disk since the server read it, the server reloads it and the write fails with `409 POLICY_CHANGED`; read the policy again and retry. `PATCH` and `DELETE` accept `If-Match` too.

While a policy is configured, routes that edit the lineup by hand answer `410 Gone` with code `POLICY_MANAGED`: `POST /lineup`, `PUT /lineup/order`, `POST /lineup/adopt`, `POST /lineup/import`, and every `/curation/*` route except `GET /curation/changes`, which also lists policy changes (`kind: "policy"`).

## HDHomeRun devices

With a policy, the HDHomeRun devices split the lineup by range instead of by hash: the first `SOFA_HDHR_DEVICE_SIZE` enabled channels (default 250) in number order go on the first device, the next on the second, and so on. Adding a channel moves only the channels after it in its range. There are `SOFA_HDHR_EXTRA_PORTS` plus one devices; channels beyond their capacity go on the last device, and the server logs it and reports an `hdhr:` finding. Preview and apply report `devicesNeeded`, the enabled channels divided by `SOFA_HDHR_DEVICE_SIZE` and rounded up, beside `devicesConfigured`; add ports until the two agree, then add the new devices in Plex.

## Cut over from a curated lineup

1. **Export** the channels the policy must keep, as `{"channels": [{"name", "number", "group", "sources": [{"provider", "channelName", "tvgId"}]}]}`, typically the channels whose only sources are aggregator playlists no native service carries.
2. **Generate** a policy from the running server:

   ```sh
   scripts/generate-policy.py --server http://127.0.0.1:8080 \
     --admin-token "$SOFA_ADMIN_TOKEN" --api-token "$SOFA_API_TOKEN" \
     --legacy legacy.json --replacements replacements.json \
     --starter-groups deploy/curation-starter.json --out policy/policy.json
   ```

   It writes `names.version` 2, `health.requireVerified`, the native providers in priority order (`--providers` changes it), the starter group map, group blocks that each run to the next block's start, at most 400 numbers (`Local` from 1, `News` 100, `Sports` 300, `Regional Sports` 600, `Entertainment` 700, `Movies` 1200, `Comedy` 1500, `Crime` 1600, `Documentary` 1700, `Lifestyle` 1850, `Food & Travel` 2100, `Kids & Family` 2200, `Music` 2350, `Game Shows` 2500, `Faith` 2550, `Shopping` 2580, `Ambiance` 2600) with the floor at 3000, where stations the policy keeps off are numbered, an alias for every enabled entry whose native sources carry other keys than its name, and a required station for each exported channel, pinned to its sources, with its name and group. Required stations are numbered in their group blocks like any other; `--keep-legacy-numbers` keeps their exported numbers instead, as overrides reserved before any block fills. `--replacements` maps an export channel name to pins that intentionally replace its sources, as a list of pins or `{"name": "...", "sources": [...]}` when the station should be keyed by another name. It prints every alias and station for review; review them.
3. **Check** the policy on a copy of the server's data with `SOFA_POLICY` set:

   ```sh
   jq -n --slurpfile export legacy.json --slurpfile repl replacements.json \
     '{channels: $export[0].channels, replacements: $repl[0]}' |
   curl --fail -H "Authorization: Bearer $SOFA_ADMIN_TOKEN" -H 'Content-Type: application/json' \
     --data @- http://127.0.0.1:8080/api/v1/admin/policy/check
   ```

   A channel passes when exactly one station carries every one of its sources, or every one of its replacement pins, counting live sources and the station's pins. The check fails overall when any channel fails or two stations share a number or an ID. Fix the policy until `pass` is `true`.
4. **Apply** on the live server after a backup of `SOFA_DATA_DIR`, then rescan the Plex devices and remap their channels. With `requireVerified`, stations come on as the probe worker verifies a source of each, first sources first; a station none of whose sources has ever qualified healthy waits, disabled. Compare `devicesNeeded` with `devicesConfigured` in the apply result before the rescan.
5. **Roll back** by restoring the backup and unsetting `SOFA_POLICY`. The policy rewrites the lineup but leaves the curation rules and settings in the database.
