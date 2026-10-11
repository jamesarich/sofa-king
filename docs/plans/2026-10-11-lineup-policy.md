# Lineup policy: a recoverable, provider-agnostic lineup

## Goal

Rebuild the whole lineup from two files, `sources.json` and a lineup policy, on any server: the same two files give the same stations, IDs, numbers, groups and sources. No rule refers to a database row. The default policy works for anyone who only sets their market.

Required stations (sources that exist only on one aggregator) are never dropped silently: when their sources move or vanish, the server keeps the last known ones and says so.

## Model

A **station** is one channel as the viewer sees it, identified by a **key**. Candidates join a station by key, or by an explicit source pin.

### Keys and normalization

`normalize(name)`, versioned (`names.version`), conservative:

1. Unicode NFKD, drop combining marks, lowercase; `&` becomes `and`, `+` becomes ` plus`.
2. Collapse every run of non-letter, non-digit characters to one space (letters in any script survive).
3. Drop a leading `the` and a trailing `hd`.
4. A trailing `tv` or `channel` is dropped only when the remainder is at least two words or five characters, and no candidate in the catalog carries the same remainder with a different suffix (so `Tennis Channel` and `Tennis TV` stay apart, `MLB` and `MLB Channel` stay apart, `ESPN8`/`ESPN 8` still meet through an alias).

The key is `aliases[normalize(name)] ?: normalize(name)`. Changing the normalization bumps `names.version`; the preview shows every key the bump changes. A golden test pins the normalization over a committed corpus of real candidate names (the current catalog dump), including the known traps: Tennis Channel / Tennis TV / Tennis+, Bloomberg TV / Bloomberg TV+, Pet Collective / Pet Collective +, MLB / MLB Channel, The Weather Channel, non-Latin names.

### Source pins

A station override may list `sources`: `{ "provider": "...", "tvgId": "...", "name": "..." }`. A pinned source joins that station by provider plus `tvg-id` (name is a fallback when `tvg-id` is absent) regardless of its name, and is never claimed by another station. Legacy aggregator channels are always pinned this way.

### Required stations

`"required": true` on a station override:

- If a pinned source is missing from the current feed, the station keeps that source's last known offering (a sticky snapshot stored in the policy as the pin itself plus the retained channel) and the run reports `required source missing` with the station and provider.
- If a required station resolves to no live source at all, it stays in the lineup, disabled, and the finding repeats every run; `/api/v1/admin/policy/findings` lists open findings for monitoring.
- A required station's source set never shrinks without a finding.

Ghost channels (retained by a feed with no current offering) never create or join a station; they only back the sticky snapshot of a required pin.

## Policy file

`SOFA_POLICY` names a JSON file in a writable directory (compose mounts `./policy/` at `/config/policy/`, not a single-file bind). The server loads it at start. Writes from the API hold one mutex shared by every writer, require `If-Match: <revision>` (the file's SHA-256), write a temp file in the same directory, fsync, and rename. If the file changed on disk since it was loaded, the server reloads it and rejects the write with `409`. The policy is never `${VAR}`-expanded.

```json
{
  "version": 1,
  "names": { "version": 1, "aliases": { "the grio": "thegrio", "espn 8": "espn8" } },
  "providers": [
    { "id": "localnow", "include": { "groups": ["My City"] } },
    { "id": "localnow", "include": { "groups": ["More Cities"] }, "enabled": false },
    { "id": "whale" }, { "id": "samsung" },
    { "id": "vizio", "exclude": { "groups": ["Local channels"] } }
  ],
  "groups": { "map": { "crime tv": "Crime" }, "patterns": [ { "name": "sport|espn", "group": "Sports" } ], "default": "Entertainment" },
  "numbering": { "floor": 1000, "keepProviderNumbers": true },
  "health": { "disableAfterHours": 24, "enableOn": "healthy", "keepMissingDays": 7 },
  "maxSourcesPerStation": 16,
  "stations": {
    "nfl network": {
      "number": "245", "group": "Sports", "required": true,
      "sources": [ { "provider": "masqueradarr", "tvgId": "NFLNetwork.us", "name": "NFL Network" } ]
    },
    "qvc": { "enabled": false }
  }
}
```

- `providers` is ordered: provider priority. A provider may appear more than once with different filters; a candidate takes the first entry that matches it (`include` / `exclude` with `groups`, `groupPatterns`, `names`, `namePatterns`; no `include` means everything). `enabled` (default true) is the default for stations led by that entry.
- `stations.<key>` overrides: `id`, `number`, `name`, `group`, `logo`, `guide` (`{guideId, channelId}`), `enabled`, `required`, `sources` (pins), `prefer` / `exclude` (provider IDs ordering or removing that station's unpinned sources).
- Station keys in `stations` are post-alias keys. Validation rejects duplicate numbers, duplicate IDs, aliases that chain or cycle, and pins claimed by two stations.

## Each run (deterministic)

Everything below sorts on total orders, never on map iteration or playlist order.

1. **Candidates.** For each provider entry in order, take its feed's live channels (ghosts excluded) that pass the filters and that no earlier entry claimed. Pinned candidates go to their pinned station first.
2. **Stations.** Group by key. The **leading candidate** is the lowest (provider entry index, channel number as a number, channel id); it gives the raw name, logo and raw group.
3. **Sources.** Pinned sources first in pin order, then the rest by (provider entry index, `prefer`, channel number, channel id), with `exclude` applied, capped at `maxSourcesPerStation` (at most 64), and the cap reported as a finding.
4. **Fields.** Group: override, else group map / patterns / default. Name and logo: override, else leading candidate. Enabled: override, else the leading provider entry's `enabled` (a pure function, no creation history).
5. **IDs.** Override `id`, else `stableId("station:" + key)`. An alias that joins station A into B keeps B's key and ID; the preview lists every ID that changes and why.
6. **Numbers.** Pass one reserves every override number (validated unique). Pass two assigns the rest in order (provider entry index, provider channel number, key): the provider's number when free and `keepProviderNumbers`, else the next free from `floor`. Every number assigned in pass two is written back to the policy as `stations.<key>.number`, so the file holds the whole numbering and a rebuild needs nothing else. No database table.
7. **Guide.** Override, else the leading source's provider guide when it lists the channel, else any guide listing a source's `tvg-id` that is not another provider's own.
8. **Availability.** A station with no eligible source for `disableAfterHours` turns off until one qualifies, tracked by key. A station whose candidates vanish (provider removed or feed dropped the channel) stays, disabled, for `keepMissingDays` before it leaves the lineup; required stations never leave.
9. **Order.** By number (numeric), then key.
10. **Write.** In one transaction, only when the lineup differs; each change goes to the change log as `{key, field, before, after, cause}`. A station that fails validation becomes a finding and is left as it was; one bad station never aborts the run.

`GET /api/v1/admin/policy/preview` runs 1-9 and returns the diff (stations added, removed, IDs and numbers changed, merges and splits, findings) without writing.

## HDHomeRun devices

Devices split the lineup by number range instead of an ID hash: `SOFA_HDHR_DEVICE_SIZE` channels per device in number order (default 250), each range served on its own port. Adding a channel affects only its range; growing the lineup adds a device at the end. The default policy keeps enabled stations within a size Plex can map on the configured devices, and the preview reports how many devices the enabled lineup needs.

## API

- `GET` / `PUT /api/v1/admin/policy` (with `If-Match`), `GET /api/v1/admin/policy/preview`, `POST /api/v1/admin/policy/apply`, `GET /api/v1/admin/policy/findings`.
- `GET /api/v1/admin/stations/{key}`: candidates, pins, and why each candidate was taken, skipped or claimed elsewhere.
- `PATCH /api/v1/admin/lineup/{id}` writes the station override (`name`, `number`, `group`, `logo`, `guide`, `enabled`, `sources`); `DELETE` writes `enabled: false`. Both go through the same revision-checked write.
- Removed, answering `410 Gone` with a pointer to the policy: `POST /lineup`, `PUT /lineup/order`, `POST /lineup/adopt`, `POST /lineup/import`, `/curation/*`.
- Client routes (`/api/v1/channels`, guide, sessions, outputs, HDHomeRun) are unchanged in shape.

## What goes

Curation rules (`group`, `correction`, `alias`, `equivalent`, `adopt`), pins, `equivalentEvidence`, `retiredGuides`, `retiredLogoHosts`, the pipeline import. Their tables stay one release, unread, for rollback, and are dropped after.

## Kept

Feeds and native providers, guides, health and probes, sessions and relay, outputs, HDHomeRun routes (with range devices), logo cache, change log, preview-before-apply, guides and logos derived from sources.

## Cutover

1. **Generate** a policy from today's database:
   - native providers in today's priority order, the starter group map;
   - `aliases` from every enabled entry whose sources carry more than one key (BBC News / BBC News North America, Cheddar / Cheddar News, ESPN8 / ESPN 8, TheGrio / The Grio, Ion / Ion USA, the Tennis Channel 2 spellings, and the rest), each checked by hand;
   - for each of the 38 legacy-only channels, a `required` station keyed by the computed key of its actual sources, with `sources` pins by provider and `tvg-id`, and its number, name and group as overrides. Fox Sports 1 and Fox Sports 2 carry the same five sources today, all the free FAST "FOX Sports" channel. Decided: FS1 and FS2 become the real networks, pinned to masqueradarr `Fox Sports 1 USA` (`tvg-id` 82547) and `Fox Sports 2 USA` (59305), required, numbers 227 and 228; the FAST "FOX Sports" channel comes from the native services like any other station.
2. **Preview** on a copy of the database with the generated policy. The cutover fails unless:
   - every one of the 38 maps to exactly one station whose pinned sources include all of its current `tvg-id`s;
   - no enabled entry maps to more than one station unless the split is intended;
   - no number or ID collides.
3. **Apply** on the live server after a database snapshot. Rescan Plex devices and remap; favorites on the TV reset once.
4. **Rollback:** restore the snapshot and the previous image; the old tables are still there for one release.

## Costs and limits

- A full rebuild every run over about 14,600 candidates; writes happen only when the lineup changes, so `saveLineup`'s delete-and-insert stays rare.
- A channel renamed by a service becomes a new station unless the key holds; the preview pairs a vanished station with new ones of a similar name so an alias can join them.
- Aggregator raw groups are useless (alphabetic buckets), so legacy stations carry group overrides.
