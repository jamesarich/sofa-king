# Curation

The lineup is curated in this server. Rules and settings live in SQLite and are edited through the [admin API](api.md#curation); the automation they drive runs inside the server after every provider import and health change, and on an interval. Household edits are authoritative: a field the household set is pinned and automation never changes it.

This page lists what the Dispatcharr-side pipeline decided (a 15-minute export that published feed generations, and a nightly playback sweep that promoted and demoted sources), where each decision lives here, and what was dropped.

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
| `retiredGuides` | `[]` | Guide IDs going away; an enabled entry mapped to one, or to no guide, moves to a guide its sources list |

The guide pass also moves an enabled entry whose mapping names a guide that is no longer configured, or a loaded guide that does not list its channel. The entry takes the first source, in the entry's order, whose `tvg-id` a guide that is not retired lists: that source's provider guide first, then a guide that is no provider's own, since a provider guide keys channels by that provider's IDs. A pinned guide, a disabled entry and an entry whose sources no live guide lists keep their mapping. A provider guide fetches programmes only for the channels the lineup maps to it, so each remap adds its channel to the next guide refresh.

## Rules

A rule is `{"id", "kind", "position", "enabled", "match": {...}, "set": {...}, "note"}`. Rules of one kind apply in `position` order; the first match wins where only one can.

| Kind | Match | Set |
| --- | --- | --- |
| `group` | `group` (exact, case-insensitive raw group), `groupPattern` (regex on the raw group), `namePattern` (regex on the entry name) | `group` |
| `correction` | `name` (exact, case-insensitive candidate name), `tvgId`, `providerId` | `name`, `group`, `logo` |
| `alias` | `entryId` (the alias) | `canonicalEntryId` |
| `equivalent` | `entryId` (the target), `name` (candidate name), `providerId` | none |
| `adopt` | `providerId`, `group`, `groupPattern`, `namePattern`, `tvgId` | `enabled` (default false), `group` |

Regexes are Java patterns matched case-insensitively against the whole value with `find`. A `group` rule needs one match field and `set.group`; an `alias` rule needs two different existing entries. An `equivalent` rule attaches every matching candidate to its entry, also one another entry already carries, so two entries can share a source; evidence without a rule only takes candidates no entry carries.

## The pass

Each run computes a plan over the current lineup, feeds, health and guides, in this order: availability (disable and enable), alias merges, equivalent attachment, adoption, groups, corrections, retired logos, retired guides. With `enabled` the plan is applied in one lineup transaction, each action re-checked against the entry as it stands (the field unpinned, the old value unchanged), and recorded in the change log; without it the plan is only reported. `GET /api/v1/admin/curation/preview` computes the same plan without applying it, and `POST /api/v1/admin/curation/run` applies one now regardless of the interval. Availability is tracked whether or not automation is enabled, so the first run after enabling knows how long an entry has had no source.

Findings in the plan are things automation sees but does not change: duplicate numbers among enabled entries, enabled entries that share a name and a guide identity without an alias rule, candidates that match several entries none or more than one of which is enabled (a single enabled match takes the candidate), and attachments the 64-source cap refused.

## Limitations

- `enableOn: healthy` never restores an entry whose only sources belong to a provider that is not probed (credential or custom headers), because those stay `UNVERIFIED`; use `eligible` or pin `enabled` on such entries.
- Every entry that is not alias-disabled, including the entries the Dispatcharr import brought in disabled, is a subject for equivalent attachment, so a new candidate can be drawn to a disabled entry; the preview shows where.
- An `adopt` action's `entryId` in a preview is assigned per plan and differs from the id the applying run creates.
- Equivalence is judged on the exact lowercase name and a shared `tvg-id`; two providers naming one station differently are attached only through an `equivalent` rule or by hand.
