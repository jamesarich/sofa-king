# Build a starter lineup

A fresh server knows its sources but has an empty lineup: nothing enters it until you add it. This guide builds a lineup from the free native services, one entry per channel with every other service that carries the same channel attached as a fallback, sorted into a small set of groups. It takes about ten minutes, most of it waiting for imports.

You need a running server ([quick start](../README.md#sofa-king-fast-setup) or [Docker](deploy.md)) and its admin token. The examples use these shell variables:

```sh
SERVER=http://SERVER_HOST:PORT
api=(-H "Authorization: Bearer API_TOKEN")     # SOFA_API_TOKEN
auth=(-H "Authorization: Bearer ADMIN_TOKEN")  # SOFA_ADMIN_TOKEN; the API token when no admin token is set
```

## 1. Configure the sources

Copy `deploy/sources.example.json` to your sources file. It lists all 17 [native providers](providers.md) and needs two edits:

- `pbs`: set `options.zip` to your ZIP code, so the server finds your member station.
- `localnow`: add `"options": { "dma": "DMA_CODE", "market": "MARKET" }` for your local news; without them it serves the national lineup.

Remove the `example-m3u` entry unless you have an M3U playlist of your own. Drop any service you don't want; each one is independent.

Native providers bring their own guides, so the `guides` list can stay empty. On a large lineup, add `"probe": false` to the biggest services (`vizio`, `samsung`, `plex`, `tcl`, `localnow`, `xumo`, `pluto`) to start: their sources are still checked when they play, and the background probe queue stays short.

Start or restart the server, then check that every provider imported:

```sh
curl -s "${api[@]}" "$SERVER/api/v1/providers"      # each entry: channels > 0, no errorCode
curl -s "${auth[@]}" "$SERVER/api/v1/admin/guides"  # one guide per native provider
```

A provider with an `errorCode` is skipped and the rest carry on; see [when a native provider breaks](operations.md).

## 2. Import the starter groups

`deploy/curation-starter.json` maps the raw category names the native services use (`Crime TV`, `More Cities`, `News And Opinion`, …) to 17 groups: Entertainment, Movies, Sports, Regional Sports, News, Local, Lifestyle, Documentary, Crime, Comedy, Kids & Family, Food & Travel, Music, Game Shows, Faith, Shopping and Ambiance.

```sh
curl -s "${auth[@]}" -H 'Content-Type: application/json' \
  --data @deploy/curation-starter.json "$SERVER/api/v1/admin/curation/import"
```

The report lists one `group` rule per mapping. Edit the file first if you want different groups; a category with no rule keeps the service's own name, and `defaultGroup` below catches channels with none.

## 3. Set curation

```sh
curl -s "${auth[@]}" -X PUT -H 'Content-Type: application/json' "$SERVER/api/v1/admin/curation/settings" -d '{
  "enabled": false,
  "intervalSeconds": 900,
  "disableAfterHours": 24,
  "enableOn": "healthy",
  "equivalentEvidence": "name",
  "numberFloor": 1000,
  "retiredLogoHosts": [],
  "defaultGroup": "Entertainment",
  "retiredGuides": []
}'
```

The request replaces every setting, so send them all. `enabled: false` keeps runs to previews until you have looked at one. `equivalentEvidence: "name"` treats two channels with the exact same name as the same station, which is what folds a channel carried by six services into one entry. The trade-off is local stations: two cities' `FOX 17` share a name, so keep Local Now to one market (step 1) and don't adopt local channels from M3U sources the same way.

## 4. Add adopt rules in priority order

An `adopt` rule turns a provider's channels into lineup entries. The first rule a channel matches decides, and rule order is priority: the first rule's service supplies the entry and its leading source, and the same channel from a later rule's service joins that entry as a fallback. Put the services whose streams you trust most first.

```sh
rule() { curl -s "${auth[@]}" -H 'Content-Type: application/json' "$SERVER/api/v1/admin/curation/rules" -d "$1" > /dev/null; }

# Your market first; other cities' local stations come in switched off.
rule '{"id":"adopt-my-city","kind":"adopt","position":0,"match":{"providerId":"localnow","group":"My City"},"set":{"enabled":true}}'
rule '{"id":"adopt-other-cities","kind":"adopt","position":1,"match":{"providerId":"localnow","group":"More Cities"},"set":{"enabled":false}}'
rule '{"id":"adopt-vizio-locals","kind":"adopt","position":2,"match":{"providerId":"vizio","group":"Local channels"},"set":{"enabled":false}}'

# Then every service, most trusted first.
pos=10
for p in whale samsung vizio lg pluto plex roku xumo tubi tcl stirr freelivesports localnow pbs hallmark ballysports adultswim; do
  rule "{\"id\":\"adopt-$p\",\"kind\":\"adopt\",\"position\":$pos,\"match\":{\"providerId\":\"$p\"},\"set\":{\"enabled\":true}}"
  pos=$((pos + 1))
done
```

`"enabled": true` puts adopted channels straight into the lineup; `false` adopts them switched off, ready to enable one by one. A rule narrows what it adopts with `group`, `groupPattern` or `namePattern`, and since the first matching rule wins, a narrow rule placed before a service's catch-all rule overrides it for those channels.

## 5. Preview, then run

```sh
curl -s "${auth[@]}" "$SERVER/api/v1/admin/curation/preview" | jq '.counts, (.findings | length)'
```

Expect a few thousand `adopt` actions; each adopted channel already carries its group and its fallback sources. When the counts look right, turn automation on and run it:

```sh
curl -s "${auth[@]}" -X PUT -H 'Content-Type: application/json' "$SERVER/api/v1/admin/curation/settings" -d '{
  "enabled": true, "intervalSeconds": 900, "disableAfterHours": 24, "enableOn": "healthy",
  "equivalentEvidence": "name", "numberFloor": 1000, "retiredLogoHosts": [],
  "defaultGroup": "Entertainment", "retiredGuides": []
}'
curl -s "${auth[@]}" -X POST "$SERVER/api/v1/admin/curation/run" | jq '.counts'
```

Guide data for new channels arrives with each guide's next refresh, within the hour (or at once after a restart); until then most channels show `guideStatus: empty`. From here curation runs every 15 minutes: new channels a service adds are adopted and grouped, same-name channels from other services are attached as fallbacks, guide mappings follow the sources, and an entry with no playable source for a day is disabled until one recovers.

## Your local channels

The free services carry local news and some station streams, but not the broadcast networks' live channels. Three sources cover your area:

- **Local Now** carries local news for one market at a time. Set `dma` and `market` on the `localnow` provider (step 1); the `My City` rule above adopts that market, enabled, and other cities come in switched off.
- **PBS** finds the member station for `options.zip` and adds its Create and World channels.
- **An antenna tuner** brings in your broadcast stations (ABC, CBS, NBC, FOX, PBS, CW and the rest over the air). A current HDHomeRun serves its lineup as an M3U playlist at `/lineup.m3u` (some older models answer 404), which the server imports like any other:

  ```json
  { "id": "antenna", "playlistUrl": "http://HDHOMERUN_IP/lineup.m3u", "probe": false }
  ```

  Add it to `providers`, add an adopt rule for it at `position` 0 so your over-the-air feeds lead any streamed copy of the same station, and add an XMLTV guide that lists your market's stations to `guides`. The tuner has as many streams as it has tuners, so keep `SOFA_MAX_SESSIONS` in mind. Over-the-air channels are usually named by call sign (`WOI-DT`), so they rarely match a streamed copy by name; join them with an `equivalent` rule where you want the streamed one as a fallback.

Some services also list other cities' station streams under their own groups (Samsung, Plex). They land in the Local group enabled; switch off the ones you don't want with a `PATCH` (below), or add a disabled adopt rule for them ahead of that service's catch-all rule.

## 6. Make it yours

- **Trim it.** `PATCH /api/v1/admin/lineup/ENTRY_ID` with `{"enabled": false}` hides a channel; an admin's change is pinned, so curation never undoes it. Deleting an adopted entry keeps it gone.
- **Rename, regroup or renumber.** A `PATCH` with `name`, `group` or `number` pins that field the same way.
- **Merge stragglers.** Channels named differently on two services (`Bob Ross` and `The Bob Ross Channel`) stay separate. An `equivalent` rule naming the entry and the other name joins them; the findings in each preview list same-name entries that look like duplicates.
- **Check the guide.** `GET /api/v1/channels` (API token) reports each channel's `guideStatus`; `available` is the goal.

Then point your players at it: the [Sofa King app](../README.md#clients), any [M3U/XMLTV player](live-tv.md#third-party-players), or [Plex](live-tv.md#plex-live-tv). A lineup of a few thousand channels is more than Plex saves on one tuner, so split it with `SOFA_HDHR_EXTRA_PORTS`.

[Curation](curation.md) has every rule and setting; [the HTTP API](api.md) has every endpoint used here.
