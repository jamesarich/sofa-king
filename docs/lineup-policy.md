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

So `The Bob Ross Channel` and `Bob Ross Channel` share the key `bob ross channel`, and `BBC News HD` joins `BBC News`. A trailing `tv` or `channel` always stays, so `Tennis Channel`, `Tennis TV` and `Tennis+` are three stations. A key depends on the name alone, never on what else the feeds carry.

When two spellings name one station, join them with an alias in `names.aliases`. Both sides are normalized keys:

```json
"names": { "version": 1, "aliases": { "espn 8": "espn8", "grio": "thegrio" } }
```

The station key is `aliases[key] ?: key`. The findings route suggests aliases for live stations whose keys differ only by a trailing ` tv` or ` channel`, or only by spaces.

## Fields

| Field | Default | Meaning |
| --- | --- | --- |
| `version` | `1` | Schema version; must be `1` |
| `names.version` | `1` | Normalization version |
| `names.aliases` | `{}` | Normalized key to station key. An alias may not point at another alias |
| `providers` | `[]` | Provider entries in priority order, see below |
| `groups.map` | `{}` | Lowercase raw group to group |
| `groups.patterns` | `[]` | `{"name": regex, "rawGroup": regex, "group": "..."}`, first match wins; give `name`, `rawGroup` or both |
| `groups.default` | unset | Group for a station nothing else groups; unset keeps the raw group |
| `numbering.floor` | `1000` | First number handed out when a station's own number is taken or not numeric |
| `numbering.keepProviderNumbers` | `true` | Use the leading channel's own number when it is free |
| `health.disableAfterHours` | `24` | Hours without an eligible source before a station turns off; 1 to 720 |
| `health.enableOn` | `healthy` | `healthy`: a `HEALTHY` source turns it back on; `eligible`: any selectable source does |
| `health.keepMissingDays` | `7` | Days a station whose channels vanished stays, disabled, before it leaves |
| `maxSourcesPerStation` | `16` | Source cap per station; 1 to 64 |
| `stations` | `{}` | Overrides by station key, see below |

### Provider entries

Each entry is `{"id": "...", "include": FILTER, "exclude": FILTER, "enabled": true}`. A channel takes the first entry whose `id` is its provider and whose filters pass it; no `include` admits everything. A filter has `groups` and `names` (exact, case-insensitive) and `groupPatterns` and `namePatterns` (case-insensitive regexes); any match passes. `enabled` is the default for stations that entry leads. A provider may appear more than once with different filters:

```json
"providers": [
  { "id": "localnow", "include": { "groups": ["My City"] } },
  { "id": "localnow", "include": { "groups": ["More Cities"] }, "enabled": false },
  { "id": "vizio", "exclude": { "groups": ["Local channels"] } }
]
```

A provider with no entry contributes only through pins.

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

Validation rejects duplicate numbers and IDs, an override keyed by an alias, aliases that chain, and a pin claimed by two stations.

## How a run works

A run is deterministic: everything sorts on values, never on feed or file order.

1. **Channels.** Each provider entry takes the live channels that pass its filters and that no earlier entry took. Pinned channels go to their station first. Channels with no current source never start or join a station.
2. **Stations.** Channels group by key. The leading channel, the lowest by (entry, channel number, channel ID), gives the name, logo and raw group.
3. **Sources.** Pinned sources first, then the rest by `prefer`, entry, channel number and channel ID, without excluded providers, up to the cap.
4. **Fields.** Overrides first, then the group map, patterns and default, then the leading channel.
5. **IDs.** The override `id`, else one derived from the key.
6. **Numbers.** Override numbers are reserved first. The rest get the leading channel's number when it is free and `keepProviderNumbers` is set, else the next free number from the floor. Each number given this way is written back to the policy file as `stations.<key>.number`, so the file holds the whole numbering. A station with a number in the file is never renumbered.
7. **Guide.** The override, else the guide of the leading source's own provider when it lists the channel, else any configured guide listing a source's `tvg-id` that is not another provider's.
8. **Availability.** A station with no eligible source for `disableAfterHours` turns off until a source qualifies. A station whose channels vanish stays, disabled, for `keepMissingDays`.
9. **Write.** The lineup is written in one transaction, in number order, only when it differs from the served one. Each change goes to the change log with the station key, the field, before and after, and the cause. A station that fails validation is reported and left as it was.

Runs follow every feed import, guide import and health change, after ten quiet seconds, and every 900 seconds. Until some feed has a live channel, a run leaves the lineup alone.

## Required stations

`"required": true` keeps a station in the lineup whatever happens to its sources:

- A required station with no live source stays, disabled, and the run reports `required station has no live source`.
- A pinned source missing from its feed is reported as `required source missing`. When the feed still lists the channel without a current source, the station keeps the sources that pin last resolved to, as long as the feed still has them, and says so.
- A required station never leaves the lineup for being missing.

## Findings

Each run reports findings, each starting with the station key: required or pinned sources missing, stations over the source cap, missing stations, and stations left as they were. With HDHomeRun range devices, a lineup larger than the devices hold adds an `hdhr:` finding. `GET /api/v1/admin/policy/findings` returns the open findings of a run over the current feeds, and the suggested aliases.

## API

All routes are under `/api/v1/admin` and take the admin token.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/policy` | The policy with its `revision` (the file's SHA-256, also the `ETag`) and `path` |
| PUT | `/policy` | Replace the policy; `If-Match: <revision>` is required. Validates, writes, applies, and returns the document and the apply result |
| GET | `/policy/preview` | What a run would change against the served lineup; writes nothing |
| POST | `/policy/apply` | Run now and write the result |
| GET | `/policy/findings` | Open findings and suggested aliases |
| POST | `/policy/check` | The cutover check, see below |
| GET | `/stations/{key}` | One station: its pins and every channel that names the key or joins it, with why |
| PATCH | `/lineup/{id}` | Writes `name`, `number`, `group`, `logo`, `guide`, `enabled` and `offerings` (as pins by provider and `tvg-id`) into the station's override, then applies |
| DELETE | `/lineup/{id}` | Writes `enabled: false` into the station's override, then applies |

Every write holds one lock, writes a temp file in the policy's directory, syncs it to disk and renames it over the policy. When the file changed on disk since the server read it, the server reloads it and the write fails with `409 POLICY_CHANGED`; read the policy again and retry. `PATCH` and `DELETE` accept `If-Match` too.

While a policy is configured, routes that edit the lineup by hand answer `410 Gone` with code `POLICY_MANAGED`: `POST /lineup`, `PUT /lineup/order`, `POST /lineup/adopt`, `POST /lineup/import`, and every `/curation/*` route except `GET /curation/changes`, which also lists policy changes (`kind: "policy"`).

## HDHomeRun devices

With a policy, the HDHomeRun devices split the lineup by range instead of by hash: the first `SOFA_HDHR_DEVICE_SIZE` enabled channels (default 250) in number order go on the first device, the next on the second, and so on. Adding a channel moves only the channels after it in its range. There are `SOFA_HDHR_EXTRA_PORTS` plus one devices; channels beyond their capacity go on the last device, and the server logs it and reports an `hdhr:` finding.

## Cut over from a curated lineup

1. **Export** the channels the policy must keep, as `{"channels": [{"name", "number", "group", "sources": [{"provider", "channelName", "tvgId"}]}]}`, typically the channels whose only sources are aggregator playlists no native service carries.
2. **Generate** a policy from the running server:

   ```sh
   scripts/generate-policy.py --server http://127.0.0.1:8080 \
     --admin-token "$SOFA_ADMIN_TOKEN" --api-token "$SOFA_API_TOKEN" \
     --legacy legacy.json --replacements replacements.json \
     --starter-groups deploy/curation-starter.json --out policy/policy.json
   ```

   It writes the native providers in priority order (`--providers` changes it), the starter group map, an alias for every enabled entry whose native sources carry other spellings than its name, and a required station for each exported channel, pinned to its sources, with its number, name and group. `--replacements` maps an export channel name to pins that intentionally replace its sources, as a list of pins or `{"name": "...", "sources": [...]}` when the station should be keyed by another name. It prints every alias and station for review; review them.
3. **Check** the policy on a copy of the server's data with `SOFA_POLICY` set:

   ```sh
   jq -n --slurpfile export legacy.json --slurpfile repl replacements.json \
     '{channels: $export[0].channels, replacements: $repl[0]}' |
   curl --fail -H "Authorization: Bearer $SOFA_ADMIN_TOKEN" -H 'Content-Type: application/json' \
     --data @- http://127.0.0.1:8080/api/v1/admin/policy/check
   ```

   A channel passes when exactly one station carries every one of its sources, or every one of its replacement pins, counting live sources and the station's pins. The check fails overall when any channel fails or two stations share a number or an ID. Fix the policy until `pass` is `true`.
4. **Apply** on the live server after a backup of `SOFA_DATA_DIR`, then rescan the Plex devices and remap their channels.
5. **Roll back** by restoring the backup and unsetting `SOFA_POLICY`. The policy rewrites the lineup but leaves the curation rules and settings in the database.
