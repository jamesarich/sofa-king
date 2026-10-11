# Lineup policy: a recoverable, provider-agnostic lineup

## Goal

Rebuild the whole lineup from two files, `sources.json` and a lineup policy, on any server. No rule or setting refers to a database row, so a fresh volume plus the two files reproduces the lineup, and the default policy works for anyone who only sets their market.

Out of scope: channel IDs and numbers survive a rebuild only where the policy pins them; favorites and Plex mappings are rebuilt once at cutover.

## Model

A **station** is one channel as the viewer sees it, identified by a **key**: the channel name, normalized (lowercase, `&` to `and`, punctuation and spacing collapsed, a leading "the" and trailing "hd", "tv", "channel" dropped), then mapped through the policy's alias table. Every candidate whose key matches is a source of that station. The lineup is the list of stations, materialized into the existing `lineup_entry` table each run, so the catalog, outputs, HDHomeRun routes, sessions and relay read it unchanged.

## Policy file

`SOFA_POLICY` names a JSON file. The server loads it at start, and admin edits through the API write it back atomically, so the file is always the truth and backs up with `sources.json`.

```json
{
  "version": 1,
  "providers": [
    { "id": "localnow", "include": { "groups": ["My City"] } },
    { "id": "localnow", "include": { "groups": ["More Cities"] }, "enabled": false },
    { "id": "whale" }, { "id": "samsung" }, { "id": "vizio", "exclude": { "groups": ["Local channels"] } },
    { "id": "masqueradarr", "include": { "names": ["NFL Network", "Fox Sports 1"] } }
  ],
  "names": { "aliases": { "the bob ross channel": "bob ross" } },
  "groups": { "map": { "crime tv": "Crime" }, "patterns": [ { "name": "sport|espn", "group": "Sports" } ], "default": "Entertainment" },
  "numbering": { "floor": 1000, "keepProviderNumbers": true },
  "health": { "disableAfterHours": 24, "enableOn": "healthy" },
  "stations": {
    "nfl network": { "number": "245", "group": "Sports" },
    "qvc": { "enabled": false }
  }
}
```

- `providers` is ordered: it is provider priority. A provider may appear more than once with different filters; a candidate takes the first entry that matches it. `include` and `exclude` take `groups`, `groupPatterns`, `names`, `namePatterns`; with no `include`, everything is included. `enabled` (default true) is whether stations first created from this entry start on.
- A station's leading source comes from the highest-priority entry carrying it; every other matching candidate is a fallback in priority order. Health still picks among them at play time.
- `stations` holds per-station overrides keyed by station key: `name`, `number`, `group`, `logo`, `enabled`, `prefer` / `exclude` (provider IDs for that station's sources). Admin `PATCH` on an entry writes here.
- Unrecoverable sources (an aggregator only one server can reach) stay ordinary providers with an `include.names` list, so the file records exactly what they contribute.

## Each run

1. For each policy provider entry in order, take its feed's channels that pass the filters; key each, skipping candidates an earlier entry already claimed.
2. Group by key into stations; the first candidate gives the raw name, logo and raw group.
3. Apply groups (map, patterns, default), then station overrides.
4. Number: override, else the station's stored assignment, else the provider's number when free and `keepProviderNumbers`, else the next free from `floor`. Assignments are stored (`station_number`) so numbers hold across runs; they are not part of recovery.
5. ID: `stableId("station:" + key)`, so a rebuild from the same files yields the same IDs.
6. Guide: the leading source's provider guide when it lists the channel, else any guide that lists a source's `tvg-id` and is not another provider's own (today's `guideFor`).
7. Availability: a station with no eligible source for `disableAfterHours` is off until one qualifies, tracked by key.
8. Write the lineup in one transaction when it differs; record changes in the change log.

Preview (`GET /api/v1/admin/policy/preview`) runs 1-7 and returns the diff against the current lineup.

## API

- `GET` / `PUT /api/v1/admin/policy`: read or replace the policy (validated; written to the file).
- `GET /api/v1/admin/policy/preview`, `POST /api/v1/admin/policy/apply`.
- `PATCH /api/v1/admin/lineup/{id}`: writes the station override; `DELETE` writes `enabled: false`.
- `GET /api/v1/admin/stations/{key}`: a station's candidates and why each was taken or skipped.

## What goes

Curation rules (`group`, `correction`, `alias`, `equivalent`, `adopt`), pins, `equivalentEvidence`, `retiredGuides`, `retiredLogoHosts`, the pipeline import, `POST /lineup/adopt` and `/lineup/import`, and their tables. Logos derive from sources and overrides; guide mappings are derived every run.

## Kept

Feeds and native providers, guides, health and probes, sessions and relay, outputs, HDHomeRun, logo cache, the change log.

## Cutover on the existing server

1. Generate the policy from today's state: native providers in today's priority order, the starter group map, `masqueradarr` and `fastchannels` with `include.names` from `legacy-only-channels-2026-10-11.json`, and station overrides for those 38 channels' names, groups and numbers.
2. Preview against the current lineup; check the 38 legacy channels are present with their sources.
3. Apply; rescan the four Plex tuners and remap; favorites on the TV reset once.

## Risks

- A renamed channel becomes a new station; the preview lists stations that disappeared next to new ones with a similar name so an alias can join them.
- Same-named local stations from different cities: providers carry other cities in their own groups, which the default policy leaves off (`More Cities`, `Local channels`).
- Plex saves a few hundred channels per tuner; the default lineup is larger, so `SOFA_HDHR_EXTRA_PORTS` grows with it.
