# Curation

The lineup is curated in this server. Rules and settings live in SQLite and are edited through the [admin API](api.md#curation); the automation they drive runs inside the server after every provider import and health change, and on an interval. Household edits are authoritative: a field the household set is pinned and automation never changes it.

This page lists what the Dispatcharr-side pipeline decided (a 15-minute export that published feed generations, and a nightly playback sweep that promoted and demoted sources), where each decision lives here, and what was dropped.

## Pipeline decisions and where they live

| Pipeline decision | Here |
| --- | --- |
| Group mapping: a provider's `group-title` is mapped to a household genre through a lowercase lookup table; a one-letter group falls back to name patterns (`sport|espn|tennis|...` to Sports, `news|weather|cnn|cnbc` to News, else Entertainment); an explicit household group wins | `group` rules: an exact lowercase match on the raw group, a regex on the raw group, or a regex on the entry name, first matching rule in position order. A pinned `group` wins. `POST /api/v1/admin/curation/import` takes the `group-map.json` shape |
| Language is not a genre (`Spanish Language`, `International` to Entertainment) | The same `group` rules; the lookup table carries those rows |
| Name and logo corrections for stations a feed mislabels (a FAST feed's `MLB Network` is `MLB Channel`; the sports-corrections records) | `correction` rules matched on the candidate's exact name, `tvg-id` or provider, setting `name`, `group` or `logo` on the entry that carries the candidate. The import takes the `sports-corrections.json` shape (`beforeName` to `afterName`) |
| The Spanish-station corrections (`corrections.json`) and the NFL Channel / NFL Network stream split | Already in the lineup: they were Dispatcharr overrides and stream links, and the [Dispatcharr import](live-tv.md#migrate-from-dispatcharr) carried the effective names, groups, guides and stream order. The import endpoint accepts the shape and reports it as applied through the lineup; no rule is created. New candidates with those names are handled by `correction` rules |
| Aliases: duplicate channels (equal exact name, and a shared guide identity or provider station id) are clustered, one winner keeps the streams of the others as backups, the losers leave the curated profile | `alias` rules (`aliases.json` shape): the alias entry's sources are appended to the canonical entry's failover order and the alias entry is disabled by automation. Neither entry is deleted; the alias keeps its UUID and comes back when the household enables it or the rule is removed. Candidate pairs that look like duplicates are listed as findings in the preview, never merged on their own |
| Equivalent-source attachment: a channel gains the stream of another channel with the same exact name after a hand review | Automatic when the evidence is the pipeline's bar: exact lowercase name match and a shared `tvg-id` or guide channel. Name-only attachment needs an `equivalent` rule naming the entry and the candidate name (the `equivalent-repairs.json` shape). Attached sources are appended; health ranks them at play time |
| Promotion: the fastest-starting stream that decoded moves to the front | Dropped. Playback already picks healthy sources before unverified before suspect, lineup order within a rank, and startup time is not measured here. A household reorder of `offerings` is pinned |
| Nightly playback sweep: every channel is decoded for two seconds through the proxy, a failure after one retry drops the channel from the curated profile, a pass restores it, a pass older than 36 hours expires | Per-source probes ([source health](source-health.md)) with two tiers, strikes and quarantine replace the channel probe. An entry whose sources are all ineligible (quarantined, in probation, manually disabled or withdrawn) for `disableAfterHours` is disabled by automation and re-enabled when a source is healthy again (`enableOn`). No expiry: an unverified source is eligible, because feeds with credential headers are never probed |
| Health gate flag (`enforced`) | `settings.enabled`. Off, the server previews and tracks availability but changes nothing |
| Empty feed: an export with no curated channels publishes an empty profile; the lineup stays | A failed or empty provider import keeps the previous snapshot (`allowEmpty`); withdrawn sources leave the entry in place and skip it for playback |
| Logo sourcing: Dispatcharr logo rows, served through a Caddy host | Every output serves `/logos/KEY` from the [logo cache](api.md#logos), which keeps a fetched copy when its host goes away. A channel's logo is the entry's `logo`, else the first of its sources' feed logos, else the guide channel's icon. A host in `retiredLogoHosts` makes its logos count as absent, so the channel resolves to a provider or guide logo instead and the cached copies are evicted; an entry with no `tvg-logo` on any source and no guide icon then has no logo where it had one |
| Numbering: Dispatcharr numbers, asserted unique at export | Entries keep their numbers. An adopted candidate takes its own number when it is numeric and free, else the lowest free integer at or above `numberFloor`. Duplicate numbers among enabled entries are a preview finding |
| New channels: Dispatcharr auto-synced them into the `sofaking-all` profile | Nothing enters the lineup on its own. An `adopt` rule (provider, raw group, name pattern) creates entries for unreferenced candidates, disabled unless the rule says `enabled`, numbered by the policy above, grouped and named through the rules, guide-mapped by `tvg-id`. A candidate that is an equivalent of an existing entry is attached instead. A candidate adopted once is never adopted again, so a deleted adopted entry stays gone |
| Generations, atomic symlink publish, rollback manifests, `url_utils.py` | Dropped. The lineup is one SQLite transaction and every output renders from it on request; there is no second copy to publish or roll back. Automation writes a change log instead (`GET /api/v1/admin/curation/changes`) |
| Reference enrichment (`ready.json`: country, languages, station ids, tags) | Dropped. The clients read `tags` as the group only; no output consumes the other fields |

## Pins

Every field an admin request sets on an entry is pinned: `number`, `name`, `group`, `logo`, `enabled`, `offerings`, `guide` (the `clear*` flags pin too). `PATCH` also takes `pin` and `unpin` lists. Automation only changes unpinned fields, and only enables an entry it disabled itself; an entry the household disabled, or that the Dispatcharr import brought in disabled, stays disabled. Entries carry `pinned`, `createdBy` (`manual`, `import`, `rule:ID`), `autoDisabledAt` and `autoDisabledReason` (`unavailable` or `alias`).

## Settings

| Field | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Apply the plan. Off, every run is a preview |
| `intervalSeconds` | `900` | Runs between triggers; at least 60 |
| `disableAfterHours` | `24` | Hours without an eligible source before an enabled entry is disabled; 1 to 720 |
| `enableOn` | `healthy` | `healthy`: a `HEALTHY` source restores an entry automation disabled; `eligible`: any selectable source does |
| `equivalentEvidence` | `name-and-guide` | `name` attaches candidates on an exact name match alone |
| `numberFloor` | `1000` | First number assigned when a candidate's own number is taken or not numeric |
| `retiredLogoHosts` | `[]` | `host` or `host:port` values whose logos count as absent |
| `defaultGroup` | unset | Group for an adopted candidate with no raw group and no matching rule |

## Rules

A rule is `{"id", "kind", "position", "enabled", "match": {...}, "set": {...}, "note"}`. Rules of one kind apply in `position` order; the first match wins where only one can.

| Kind | Match | Set |
| --- | --- | --- |
| `group` | `group` (exact, case-insensitive raw group), `groupPattern` (regex on the raw group), `namePattern` (regex on the entry name) | `group` |
| `correction` | `name` (exact, case-insensitive candidate name), `tvgId`, `providerId` | `name`, `group`, `logo` |
| `alias` | `entryId` (the alias) | `canonicalEntryId` |
| `equivalent` | `entryId` (the target), `name` (candidate name), `providerId` | none |
| `adopt` | `providerId`, `group`, `groupPattern`, `namePattern`, `tvgId` | `enabled` (default false), `group` |

Regexes are Java patterns matched case-insensitively against the whole value with `find`. A `group` rule needs one match field and `set.group`; an `alias` rule needs two different existing entries.

## The pass

Each run computes a plan over the current lineup, feeds, health and guides, in this order: availability (disable and enable), alias merges, equivalent attachment, adoption, groups, corrections, retired logos. With `enabled` the plan is applied in one lineup transaction, each action re-checked against the entry as it stands (the field unpinned, the old value unchanged), and recorded in the change log; without it the plan is only reported. `GET /api/v1/admin/curation/preview` computes the same plan without applying it, and `POST /api/v1/admin/curation/run` applies one now regardless of the interval. Availability is tracked whether or not automation is enabled, so the first run after enabling knows how long an entry has had no source.

Findings in the plan are things automation sees but does not change: duplicate numbers among enabled entries, enabled entries that share a name and a guide identity without an alias rule, candidates that match several entries, and attachments the 64-source cap refused.

## Import the pipeline's data

`POST /api/v1/admin/curation/import` takes the JSON files the pipeline kept and creates rules:

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

`groupMap` becomes one `group` rule per row, `aliases` one `alias` rule per pair whose two UUIDs are lineup entries, `equivalents` one `equivalent` rule per item (the `uuid` and `name` fields of the repair records), and `sportsCorrections` one `correction` rule per record with `beforeName` and `afterName`. Records without those fields (the stream split) and the whole `corrections` object are reported as `applied-by-import`. A rule equal to an existing one is reported as `exists`. The report lists every rule created and every item skipped with its reason.

## Cut over from the pipeline

1. Import the lineup ([migrate from Dispatcharr](live-tv.md#migrate-from-dispatcharr)) and let the probes qualify the sources.
2. Post the pipeline's data files to the import endpoint with `dryRun`, then without, `aliases.json` included: the duplicates it names are in the lineup as disabled entries with their canonicals' names and `tvg-id` values, and until the alias rules exist a new candidate for one of those stations matches two entries and is a finding instead of an attachment.
3. Read `GET /api/v1/admin/curation/preview` and pin whatever the plan would change that the household wants kept.
4. Set `enabled` to `true`.
5. Point every client at this server: the app and TiviMate at `/output/*`, Plex at the HDHomeRun routes ([third-party players](live-tv.md#third-party-players), [Plex](live-tv.md#plex-live-tv)). Until then they read the pipeline's Caddy host.
6. List the logo hosts the lineup names (`GET /api/v1/admin/lineup`, the `logo` fields; the Caddy host and Dispatcharr's own address are both candidates) and put the ones that are going away in `retiredLogoHosts`. Check the preview's `clear-logo` actions against entries whose sources and guide carry no logo of their own.
7. Remove the feed-refresh cron line and whatever schedules the playback refresh, then stop the Caddy container and Dispatcharr; the server reads nothing from them.

## Limitations

- `enableOn: healthy` never restores an entry whose only sources belong to a provider that is not probed (credential or custom headers), because those stay `UNVERIFIED`; use `eligible` or pin `enabled` on such entries.
- Every entry that is not alias-disabled, including the entries the Dispatcharr import brought in disabled, is a subject for equivalent attachment, so a new candidate can be drawn to a disabled entry; the preview shows where.
- An `adopt` action's `entryId` in a preview is assigned per plan and differs from the id the applying run creates.
- Equivalence is judged on the exact lowercase name and a shared `tvg-id`; two providers naming one station differently are attached only through an `equivalent` rule or by hand.
