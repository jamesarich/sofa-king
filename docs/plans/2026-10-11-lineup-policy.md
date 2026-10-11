# Lineup policy: a recoverable, provider-agnostic lineup

## Goal

Rebuild the whole lineup from two files, `sources.json` and a lineup policy, on any server: the same two files give the same stations, IDs, numbers, groups and sources. No rule refers to a database row. The default policy works for anyone who only sets their market.

Required stations (sources that exist only on one aggregator) are never dropped silently: when their sources move or vanish, the server keeps the last known ones and says so.

## Model

A **station** is one channel as the viewer sees it, identified by a **key**. Candidates join a station by key, or by an explicit source pin.

### Keys and normalization

`baseName(name)`, versioned (`names.version`), conservative, and a function of the name alone (never of the catalog, so a key cannot change because another channel appeared):

1. Unicode NFKD, drop combining marks, lowercase; `&` becomes `and`, `+` becomes ` plus`.
2. Collapse every run of non-letter, non-digit characters to one space (letters in any script survive).
3. Drop a leading `the` and a trailing `hd`.
4. Version 2 only: remove every space.

Version 2 is the default; version 1 (steps 1 to 3) stays selectable. Under version 2 spellings that differ only by spaces meet (`WeatherSpy` / `Weather Spy`, `123GO!` / `123 GO!`, `ESPN 8` / `ESPN8`). A trailing `tv` or `channel` always stays: `Tennis Channel`, `Tennis TV` and `Tennis+` are three keys, as are `Bloomberg TV` / `Bloomberg TV+`, `MLB` / `MLB Channel` and `NFL Network` / `NFL Channel`; `The Bob Ross Channel` meets `Bob Ross Channel` and `BBC News HD` meets `BBC News`. Other spellings that name one station meet through an alias, written by hand or learned from schedules. The preview and findings list **suggested aliases** as `<key>: possible alias of <other key>`: under version 1, pairs of live station keys that differ only by a trailing ` tv` or ` channel` or only by spaces (a dropped leading `the` counts); under either version, loose pairs with no schedule to compare.

The key is the normalized name through `aliases`, then through `learned`; an alias wins over a learned alias of the same name, and a learned target that is itself an alias resolves through it. Changing the normalization bumps `names.version`. Golden tests pin each version over a committed corpus of real candidate names (the current catalog dump), including the known traps: Tennis Channel / Tennis TV / Tennis+, Bloomberg TV / Bloomberg TV+, Pet Collective / Pet Collective +, MLB / MLB Channel, NFL Network / NFL Channel, The Weather Channel, non-Latin names. The policy generator ports the normalization and is tested against both golden files.

### Learned aliases

Under name-only keys, dozens of stations differ only by filler words (`TYT` / `TYT Network`, `NHRA TV` / `NHRA`, `The Hill TV` / `The Hill`, `Ghost Hunters` / `Ghost Hunters Channel`, `Modern Marvels` / `Modern Marvels by HISTORY`), and nearly all carry guide data, while `NFL Network` and `NFL Channel` are different channels. The schedule decides, not the name.

Each run, after keys and before the stations are built, pairs live stations whose channels share a **loose name**: base words without `tv`, `channel`, `network`, `live`, `the` and `hd` and without a trailing `by <words>`, spaces removed. For each pair where both stations have a guide mapping with programmes, it compares the stored programmes of the next twelve hours: titles lowercase alphanumeric, starts within five minutes, each programme matched once. Three or more matches covering at least 60% of the smaller schedule make one channel. Matches merge by connected component into the station with the most sources (ties: the key that sorts first), and the run builds with the merge. A pair with no schedule to compare is a suggested alias, never a merge; a pair whose schedules differ is neither.

An apply writes each learned alias to `names.learned` (`{from: to}`, targets resolved so none chains) through the revision-checked writer, in the same write as the assigned numbers, so a rebuild from the files reproduces it without any guide. The merged-away key's override goes with it when it holds only a number; a station whose override holds more is never merged away, and the findings say why. `names.never` lists key pairs that never merge (validated as two different keys); a learned entry for such a pair is ignored and removed at the next write.

### Source pins

A station override may list `sources`: `{ "provider": "...", "tvgId": "...", "name": "..." }`. A pinned source joins that station by provider plus `tvg-id` (name is a fallback when `tvg-id` is absent) regardless of its name, and is never claimed by another station. Legacy aggregator channels are always pinned this way. A pin works whether or not its provider has a `providers` entry.

### Required stations

`"required": true` on a station override:

- If a pinned source is missing from the current feed, the run reports `required source missing` with the station and provider. When the pin still matches a retained ghost channel, the station keeps the offerings that pin last resolved to, as long as the feed snapshot still has those offering rows (the sticky snapshot); otherwise the finding is all there is. The pin-to-offerings map lives in `station_availability`, not the policy file, which holds pins only.
- If a required station resolves to no live source at all, it stays in the lineup, disabled, and the finding repeats every run; `/api/v1/admin/policy/findings` lists open findings for monitoring.
- A required station's source set never shrinks without a finding.

Ghost channels (retained by a feed with no current offering) never create or join a station; they only back the sticky snapshot of a required pin.

## Policy file

`SOFA_POLICY` names a JSON file in a writable directory (compose mounts `./policy/` at `/config/policy/`, not a single-file bind). The server loads it at start. Writes from the API hold one mutex shared by every writer, require `If-Match: <revision>` (the file's SHA-256), write a temp file in the same directory, fsync, and rename. If the file changed on disk since it was loaded, the server reloads it and rejects the write with `409`. The policy is never `${VAR}`-expanded.

```json
{
  "version": 1,
  "names": {
    "version": 2, "aliases": { "grio": "thegrio" }, "learned": { "ghosthunterschannel": "ghosthunters" },
    "never": [["nflnetwork", "nflchannel"]]
  },
  "providers": [
    { "id": "localnow", "include": { "groups": ["My City"] } },
    { "id": "localnow", "include": { "groups": ["More Cities"] }, "enabled": false },
    { "id": "whale" }, { "id": "samsung" },
    { "id": "vizio", "exclude": { "groups": ["Local channels"] } }
  ],
  "groups": { "map": { "crime tv": "Crime" }, "patterns": [ { "name": "sport|espn", "group": "Sports" } ], "default": "Entertainment" },
  "numbering": { "floor": 2000, "groups": { "Local": 1, "News": 100, "Sports": 200 }, "groupSize": 100 },
  "health": { "disableAfterHours": 24, "enableOn": "healthy", "keepMissingDays": 7, "requireVerified": true },
  "maxSourcesPerStation": 16,
  "stations": {
    "nflnetwork": {
      "number": "245", "group": "Sports", "required": true,
      "sources": [ { "provider": "masqueradarr", "tvgId": "NFLNetwork.us", "name": "NFL Network" } ]
    },
    "qvc": { "enabled": false }
  }
}
```

- `providers` is ordered: provider priority. A provider may appear more than once with different filters; a candidate takes the first entry that matches it (`include` / `exclude` with `groups`, `groupPatterns`, `names`, `namePatterns`; no `include` means everything). `enabled` (default true) is the default for stations led by that entry.
- `stations.<key>` overrides: `id`, `number`, `name`, `group`, `logo`, `guide` (`{guideId, channelId}`), `enabled`, `required`, `sources` (pins), `prefer` / `exclude` (provider IDs ordering or removing that station's unpinned sources).
- Station keys in `stations` are post-alias keys, and both sides of an alias are normalized keys (`The Grio` normalizes to `grio`). Validation rejects duplicate numbers, duplicate IDs, aliases or learned aliases that chain or cycle, overrides keyed by an alias or an applied learned alias, `never` entries that are not two different keys, and pins claimed by two stations.
- `numbering.groups` maps a group to the first number of its block; `numbering.groupSize` (default 100) sizes every block. While it is set, `keepProviderNumbers` is ignored.
- `health.requireVerified` (default true) keeps a station off until one of its sources has qualified healthy once.

## Each run (deterministic)

Everything below sorts on total orders, never on map iteration or playlist order.

1. **Candidates.** For each provider entry in order, take its feed's live channels (ghosts excluded) that pass the filters and that no earlier entry claimed. Pinned candidates go to their pinned station first.
2. **Stations.** Group by key. The **leading candidate** is the lowest (provider entry index, channel number as a number, channel id); it gives the raw name, logo and raw group. Loose pairs are then compared by schedule (see Learned aliases), and the run continues over the merged stations.
3. **Sources.** Pinned sources first in pin order, then the rest by (provider entry index, `prefer`, channel number, channel id), with `exclude` applied, capped at `maxSourcesPerStation` (at most 64), and the cap reported as a finding.
4. **Fields.** Group: override, else group map / patterns / default. Name and logo: override, else leading candidate. Enabled: override, else the leading provider entry's `enabled` (a pure function, no creation history).
5. **IDs.** Override `id`, else `stableId("station:" + key)`. An alias that joins station A into B keeps B's key and ID; the preview lists every ID that changes and why.
6. **Numbers.** Pass one reserves every override number (validated unique). Pass two assigns the rest in order (provider entry index, provider channel number, key). With `numbering.groups`: the next free number in the station's group block, else (block full, or group without a block) the next free from `floor`. Without: the provider's number when free and `keepProviderNumbers`, else the next free from `floor`. Every number assigned in pass two is written back to the policy as `stations.<key>.number`, so the file holds the whole numbering and a rebuild needs nothing else. Numbers in the file never change. No database table.
7. **Guide.** Override, else the leading source's provider guide when it lists the channel, else any guide listing a source's `tvg-id` that is not another provider's own.
8. **Availability.** With `requireVerified`, a station is disabled with reason `unverified` until one of its sources is `HEALTHY` or has a `lastQualifiedAt`; the first run that sees it records `verifiedAt` for the key. Required stations wait the same way and never leave. Once verified (or without `requireVerified`), a station with no eligible source for `disableAfterHours` turns off until one qualifies, tracked by key. A station whose candidates vanish (provider removed or feed dropped the channel) stays, disabled, for `keepMissingDays` before it leaves the lineup; required stations never leave; a key an alias now joins into another station merged and does not linger. Availability persists in the `station_availability` table (key, JSON, `updated_at`), replaced whole by each apply.
9. **Order.** By number (numeric), then key.
10. **Write.** Through the lineup service in one transaction, only when the lineup differs, each entry with `createdBy: "policy"`; each change goes to the change log (`curation_change`, `kind: "policy"`) as `{key, field, before, after, cause}`. A station that fails validation becomes a finding and is left as it was; one bad station never aborts the run. Assigned numbers and learned aliases then go to the policy file in one revision-checked write. A run is skipped while no feed has a live channel.

Runs follow feed imports, guide imports and health changes (debounced ten seconds) and an interval of 900 seconds. With `SOFA_POLICY` set the curation engine does not start; unset, the server behaves as before.

`GET /api/v1/admin/policy/preview` runs 1-9 and returns the diff (stations added, removed, IDs and numbers changed, merges and splits, findings, aliases it would learn) without writing.

## Probing

With a policy, the probe worker runs regardless of the providers' `probe` flags and probes only offerings the last apply's stations reference, in tiers: (0) the first source of each unverified station, (1) those stations' other sources, (2) every other referenced offering by the existing due order. A station the policy disables is never in tiers 0 or 1. Parallelism stays `SOFA_PROBE_PARALLELISM`. Without a policy, `probe` holds as before.

## HDHomeRun devices

With a policy, devices split the lineup by number range instead of an ID hash: `SOFA_HDHR_DEVICE_SIZE` enabled channels per device in lineup order (default 250), each range served on its own port. The device count stays `SOFA_HDHR_EXTRA_PORTS` plus one; channels beyond their capacity go on the last device, with a log line and an `hdhr:` finding asking for more ports. Preview and apply report `devicesNeeded` (enabled channels over `SOFA_HDHR_DEVICE_SIZE`, rounded up) beside `devicesConfigured`. Without a policy the hash split stays.

## API

- `GET` / `PUT /api/v1/admin/policy` (`If-Match` required, else `428`), `GET /api/v1/admin/policy/preview`, `POST /api/v1/admin/policy/apply`, `GET /api/v1/admin/policy/findings` (findings and suggested aliases), `POST /api/v1/admin/policy/check` (the cutover gate).
- `GET /api/v1/admin/stations/{key}`: candidates, pins, and why each candidate was taken, skipped or claimed elsewhere.
- `PATCH /api/v1/admin/lineup/{id}` writes the station override (`name`, `number`, `group`, `logo`, `guide`, `enabled`, `sources`); `DELETE` writes `enabled: false`. Both go through the same revision-checked write.
- `PATCH` maps `offerings` to `sources` pins by provider and `tvg-id` of those offerings.
- While a policy is configured, answering `410 Gone` with code `POLICY_MANAGED` and a pointer to `/api/v1/admin/policy`: `POST /lineup`, `PUT /lineup/order`, `POST /lineup/adopt`, `POST /lineup/import`, `/curation/*` except `GET /curation/changes`.
- Client routes (`/api/v1/channels`, guide, sessions, outputs, HDHomeRun) are unchanged in shape.

## What goes

Curation rules (`group`, `correction`, `alias`, `equivalent`, `adopt`), pins, `equivalentEvidence`, `retiredGuides`, `retiredLogoHosts`, the pipeline import. Their tables stay one release, unread, for rollback, and are dropped after.

## Kept

Feeds and native providers, guides, health and probes, sessions and relay, outputs, HDHomeRun routes (with range devices), logo cache, change log, preview-before-apply, guides and logos derived from sources.

## Cutover

1. **Generate** a policy from today's database with `scripts/generate-policy.py` (see `docs/lineup-policy.md`):
   - native providers in today's priority order, the starter group map, `names.version` 2, `health.requireVerified`, and `numbering.groups` with one block of 100 per group (Local 1, News 100, Sports 200, Regional Sports 300, Entertainment 400, Movies 600, Comedy 800, Crime 900, Documentary 1000, Lifestyle 1100, Food & Travel 1300, Kids & Family 1400, Music 1500, Game Shows 1600, Faith 1700, Shopping 1800, Ambiance 1900) and the floor at 2000, clear of every block;
   - `aliases` from every enabled entry whose sources carry more than one key (BBC News / BBC News North America, Cheddar / Cheddar News, TheGrio / The Grio, Ion / Ion USA, and the rest), each checked by hand;
   - for each of the 38 legacy-only channels, a `required` station keyed by the computed key of its actual sources, with `sources` pins by provider and `tvg-id`, and its number, name and group as overrides, so pass one reserves those numbers before any group block fills. Fox Sports 1 and Fox Sports 2 carry the same five sources today, all the free FAST "FOX Sports" channel. Decided: FS1 and FS2 become the real networks, pinned to masqueradarr `Fox Sports 1 USA` (`tvg-id` 82547) and `Fox Sports 2 USA` (59305), required, numbers 227 and 228; the FAST "FOX Sports" channel comes from the native services like any other station.
2. **Preview** on a copy of the database with the generated policy, and run `POST /api/v1/admin/policy/check` with the legacy export and the replacements. The cutover fails unless:
   - every one of the 38 maps to exactly one station that carries all of its current sources (provider and `tvg-id`), or all of its replacement pins;
   - no enabled entry maps to more than one station unless the split is intended (read from the preview's `splits`);
   - no number or ID collides.
3. **Apply** on the live server after a database snapshot. Stations come on as their sources verify, first sources first; check `devicesNeeded` against `devicesConfigured`, then rescan Plex devices and remap; favorites on the TV reset once.
4. **Rollback:** restore the snapshot and the previous image; the old tables are still there for one release.

## Costs and limits

- A full rebuild every run over about 14,600 candidates; writes happen only when the lineup changes, so `saveLineup`'s delete-and-insert stays rare.
- A channel renamed by a service becomes a new station unless the key holds; the preview pairs a vanished station with new ones of a similar name so an alias can join them.
- Aggregator raw groups are useless (alphabetic buckets), so legacy stations carry group overrides.
