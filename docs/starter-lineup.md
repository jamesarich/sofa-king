# Build a starter lineup

A fresh server knows its sources but serves no channels until a [lineup policy](lineup-policy.md) builds them. This guide writes a policy for the free native services: one station per channel, with every other service that carries the same channel attached as a fallback, sorted into a small set of groups with a block of numbers each. It takes about ten minutes, most of it waiting for imports.

You need a running server ([quick start](../README.md#sofa-king-fast-setup) or [Docker](deploy.md)) and its tokens. The examples use these shell variables:

```sh
SERVER=http://SERVER_HOST:PORT
API_TOKEN=...                                   # SOFA_API_TOKEN
ADMIN_TOKEN=...                                 # SOFA_ADMIN_TOKEN; the API token when no admin token is set
api=(-H "Authorization: Bearer $API_TOKEN")
auth=(-H "Authorization: Bearer $ADMIN_TOKEN")
```

## 1. Configure the sources

Copy `deploy/sources.example.json` to your sources file. It lists all 17 [native providers](providers.md) and needs two edits:

- `pbs`: set `options.zip` to your ZIP code, so the server finds your member station.
- `localnow`: add `"options": { "dma": "DMA_CODE", "market": "MARKET" }` for your local news; without them it serves the national lineup.

Remove the `example-m3u` entry unless you have an M3U playlist of your own. Drop any service you don't want; each one is independent.

Native providers bring their own guides, so the `guides` list can stay empty.

Start or restart the server, then check that every provider imported:

```sh
curl -s "${api[@]}" "$SERVER/api/v1/providers"      # each entry: channels > 0, no errorCode
curl -s "${auth[@]}" "$SERVER/api/v1/admin/guides"  # one guide per native provider
```

A provider with an `errorCode` is skipped and the rest carry on; see [when a native provider breaks](operations.md#when-a-native-provider-breaks).

## 2. Generate the policy

`deploy/starter-groups.json` maps the raw category names the native services use (`Crime TV`, `More Cities`, `News And Opinion`, …) to 22 groups: Local, Local News (US), News, Sports, En Español, Entertainment, Drama, Classic TV, Movies, Sci-Fi & Horror, Comedy, Crime, Documentary, Lifestyle, Food & Travel, Kids & Family, Anime, Music, Game Shows, Faith, Shopping and Ambiance. Its `weak` list holds broad categories that decide a station's group only when nothing more specific does, and its `patterns` group stations by name.

```sh
mkdir -p policy
python3 scripts/generate-policy.py --server "$SERVER" \
  --admin-token "$ADMIN_TOKEN" --api-token "$API_TOKEN" \
  --starter-groups deploy/starter-groups.json --local-pattern '\b(des moines|kcci)\b' \
  --out policy/policy.json
```

Replace the `--local-pattern` regex with your own market's city and station call signs; stations whose names match it group as Local. The generated policy:

- Takes the services in priority order, the ones whose streams hold up best first: the first service that carries a channel leads its station, and the same channel from the others joins it as a fallback. `--providers` changes the order.
- Takes Local Now's `My City` channels switched on and its `More Cities` channels switched off, and leaves out Vizio's `Local channels`.
- Numbers each group in its own block: Local from 1, Local News (US) from 10, News from 100, Sports from 300 and so on ([every block](lineup-policy.md#generate-a-policy)). Stations the policy keeps off number from 3000.
- Keeps each station off until one of its sources has played once (`health.requireVerified`).

Edit `deploy/starter-groups.json` first if you want different groups. A category the file does not name casts no vote, and a station that nothing groups goes in `Entertainment`.

## 3. Turn it on

Set `SOFA_POLICY` to the file and restart. With Docker, mount the `policy` directory read-write as [lineup policy setup](lineup-policy.md#setup) shows; the server writes numbers back into the file. Then preview the lineup:

```sh
curl -s "${auth[@]}" "$SERVER/api/v1/admin/policy/preview" | jq '.stations, .enabledStations, .counts, (.findings | length)'
```

Expect a few thousand stations. They come on as the probes verify one source of each, first sources first, so the lineup fills over the first hours. From then on the policy runs after every import, guide import and health change, and every 15 minutes: a channel a service adds joins its station or starts a new one, a station with no playable source for a day turns off until one recovers, and the numbers the server assigns are written into the policy file.

## Your local channels

The free services carry local news and some station streams, but not the broadcast networks' live channels. Three sources cover your area:

- **Local Now** carries local news for one market at a time. Set `dma` and `market` on the `localnow` provider (step 1); the policy takes that market switched on, and other cities switched off.
- **PBS** finds the member station for `options.zip` and adds its Create and World channels.
- **An antenna tuner** brings in your broadcast stations (ABC, CBS, NBC, FOX, PBS, CW and the rest over the air). A current HDHomeRun serves its lineup as an M3U playlist at `/lineup.m3u` (some older models answer 404), which the server imports like any other:

  ```json
  { "id": "antenna", "playlistUrl": "http://HDHOMERUN_IP/lineup.m3u", "probe": false }
  ```

  Add it to `providers` in the sources file, add `{"id": "antenna"}` first in the policy's `providers` so your over-the-air feeds lead any streamed copy of the same station, and add an XMLTV guide that lists your market's stations to `guides`. The tuner has as many streams as it has tuners, so keep `SOFA_MAX_SESSIONS` in mind. Over-the-air channels are usually named by call sign (`WOI-DT`), so they rarely share a key with a streamed copy; join the two with a `names.aliases` entry where you want the streamed one as a fallback.

Some services also list other cities' station streams under their own groups (Samsung, Plex). Switch off the ones you don't want with a `DELETE` (below), or add an `exclude` filter to that service's provider entry.

## 4. Make it yours

- **Trim it.** `DELETE /api/v1/admin/lineup/ENTRY_ID`, or a `PATCH` with `{"enabled": false}`, writes `enabled: false` into the station's override in the policy file, so it stays off through every rebuild.
- **Rename, regroup or renumber.** A `PATCH` with `name`, `group` or `number` writes the override the same way.
- **Merge stragglers.** Stations whose names match loosely merge on their own when their schedules agree; the rest are listed as suggested aliases in `GET /api/v1/admin/policy/findings`. Add the ones that are one channel to `names.aliases`.
- **Map the categories.** `unmappedCategories` in the preview lists the raw categories no group map names; add them to `groups.map`.
- **Check the guide.** `GET /api/v1/channels` (API token) reports each channel's `guideStatus`; `available` is the goal.

Then point your players at it: the [Sofa King app](../README.md#clients), any [M3U/XMLTV player](live-tv.md#third-party-players), or [Plex](live-tv.md#plex-live-tv). A lineup of a few thousand channels is more than Plex saves on one tuner, so split it with `SOFA_HDHR_EXTRA_PORTS`; the preview's `devicesNeeded` says how many devices the enabled stations need.

[Lineup policy](lineup-policy.md) has every field; [the HTTP API](api.md) has every endpoint used here.
