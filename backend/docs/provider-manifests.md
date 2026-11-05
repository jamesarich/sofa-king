# Provider manifests

A provider manifest is a native provider written as data. A service that answers in JSON, or in a document that decodes to JSON, needs no Kotlin client: its requests, the mapping from its answers to channels and programs, and the way its streams resolve are one JSON file. The server ships some manifests in its jar and reads others from the data directory, so a service that changes its schema is fixed by editing a file, not by a release. The [providers](providers.md) page lists the services; every file under `providers/` in the jar is a manifest, and the other services are Kotlin clients.

## Where manifests load from

| Place | Role |
| --- | --- |
| `providers/<service>.json` in the jar | The shipped manifest of the service |
| `<data directory>/providers/<service>.json` | Replaces the shipped one of the same name, or adds a service |

A job named `provider-manifests` ("Reload provider manifests") runs every 60 seconds and when the portal's Jobs page runs it. It compares each file's modified time and size, reads only the files that changed, and makes a valid one current at once. The next channel import, guide import or stream resolve of that service uses it, and the job asks the service's providers for a refresh. A file that does not parse or validate is ignored: the previous manifest stays current, and the job's note counts it as invalid. Deleting an override returns the service to its shipped manifest.

A service name that no jar or earlier start knew needs a restart before the source file accepts it. The portal's Sources page restarts the server after a save. A manifest in the data directory has no switch on that page unless it ships a `catalog` block in the jar.

## Schema

A manifest is a JSON object. Unknown keys are errors. `validate()` rejects an unknown op, an unknown `${}` name, a reference to a request that does not exist and a literal `Authorization`, `Cookie` or `Proxy-Authorization` header value; a credential header may only be `${option}` naming an option the household sets under `catalog.options`.

| Key | Default | Meaning |
| --- | --- | --- |
| `service` | Required | The service name; equals the file name |
| `version` | `1` | The schema version; only `1` exists |
| `hasGuide` | `false` | Whether the service carries a guide of its own |
| `protocol` | `hls` | `hls`, or `mpegts` for a service that streams MPEG-TS |
| `catalog` | None | How the portal's Sources page presents the service; a service without one has no switch |
| `defaults` | `{}` | Values for `${name}` when the source file sets no option of that name |
| `headers` | `{}` | Headers of every request; a request's own headers replace the same names |
| `cacheSeconds` | `0` | How long the channel list is kept and shared by the listing, the guide and resolves |
| `requests` | `{}` | The named requests |
| `channels` | Required | Where channels come from and how they map |
| `guide` | None | How programs map; absent means the service has none |
| `resolve` | Required | How a channel becomes a stream |

`catalog`:

| Key | Default | Meaning |
| --- | --- | --- |
| `label` | Required | The switch's name |
| `carries` | Required | One sentence on what the service carries |
| `priority` | `0` | Place in the starter policy's order. The Kotlin services carry 10, 20, 30 in `ServiceCatalog`, the places they had on the Sources page; a tie goes to the Kotlin service |
| `startsOn` | `true` | Whether a fresh server ticks it before anything is set; never when an option is required |
| `options` | `[]` | Objects of `key`, `label`, `hint`, `required` and `pattern` (a regular expression) |

Each entry of `requests`:

| Key | Default | Meaning |
| --- | --- | --- |
| `method` | `GET` | `GET` or `POST` |
| `url` | Required | A template |
| `headers` | `{}` | Templates |
| `body` | None | A template; `POST` only |
| `contentType` | `application/json` | Of the body |
| `paging` | None | `start` (first `${page}`, default 1), `maxPages`, `count` and `size` (paths in the first answer; more pages follow while `count` exceeds `size`) and `defaultSize`; or `cursorPath` and `maxPages` for a [keyed guide](#guide-keyed) |
| `decode` | `[]` | Steps from the response body to JSON, in order |
| `items` | `""` | Path of the array of items in the answer; empty for the whole answer. An answer that is itself an array is the items whatever the path says. A `*` segment steps into each element of an array (`categories.*.channels.*`), and an item read that way has the object that held its array at `^`, so `^.name` is the name of its category |
| `optional` | `false` | A failure reads as no answer instead of failing the import |
| `cacheSeconds` | `0` | How long the answer is shared; not for a request that uses `${channel...}` |

Decode steps are `base64`, `zlib`, `gzip`, `jsonArrayAfter:<key>` (the first array after `"key":` in a page) and `nextData` (a Next.js page's state). `base64` of a body that is already JSON ends the chain, so a service may send either form. With no steps the body is JSON.

`channels`:

| Key | Default | Meaning |
| --- | --- | --- |
| `from` | | The request that lists the channels |
| `static` | | Channel items written in the manifest, instead of `from`; exactly one of the two |
| `filter` | `[]` | Items that fail any filter are skipped |
| `map` | Required | `id` and `name` (required), `logo`, `group`, `number`, `tags` (literal strings) and `tagsWhen` (`{"tag": "featured", "when": <a filter>}` adds the tag to a channel whose item passes the filter) |
| `vars` | `{}` | Named values kept per channel, for `${channel.vars.NAME}` |
| `join` | None | Looks up each channel in another request's items: `request`, `key` (path in the item), `on` (a template for the channel), `vars` and `set` (`logo`, `name`, `group` or `number`) to take from the match |
| `sortBy` | None | A path to a number in the item; channels list in ascending order of it, items without one last |
| `distinctBy` | None | `id` keeps the first channel of an id |
| `requireNonEmpty` | `false` | An empty list fails the import as a service fault |

A filter is `path`, `op`, and `value` for `eq`, `neq` and `startsWith`; `default` stands in for a missing value, and `anyOf` (a list of paths) replaces `path` with the first of them that has a value. The ops are `eq`, `neq`, `startsWith` (ignoring case), `truthy` (JSON `true`, the text "true" or a number other than zero), `falsy`, `http` (an HTTP or HTTPS URL) and `nonEmpty`; `truthy` and `falsy` take a `path` only.

A field reads a value from an item. A string is a `path`; an object combines these, applied in this order:

| Key | Meaning |
| --- | --- |
| `path`, `firstOf`, `firstHttp`, `value` | The source, exactly one: a dotted path (`display.epg.title`, `images.logo.0.url`), the first path with a value, the first path holding an HTTP URL, or a literal |
| `fallback` | A path read when the source gives nothing |
| `before` | Keeps the text before the first occurrence of this |
| `trim` | Trims, and empty reads as nothing |
| `http` | Keeps only an HTTP or HTTPS URL |
| `dropIf` | Reads as nothing when equal to this, ignoring case |
| `unlessEquals` | Reads as nothing when equal to the value at this path |
| `as` | `epochSeconds`, `epochMillis` or `iso`: reads a time |
| `nonUrl` | `path` names an array: the first text in it that does not start with `http` |
| `sentenceCase` | Lowercases, then capitalizes the first letter |
| `usRating` | Reads a content rating and its system |

`guide`:

| Key | Meaning |
| --- | --- |
| `mode` | `inline`: each channel item carries its programs at `programs`. `perChannel`: `request` runs once per wanted channel. `keyed`: `request` runs once and each item names its channel |
| `requireVar` | A channel variable that must be set for a wanted channel, else the guide fails as a service fault |
| `key`, `keyVar` | `keyed` only: the path in a program item that names its channel, matched to the channel variable `keyVar` (to the channel id when there is none) |
| `fallbackKey` | `keyed` only: for an item whose `key` no channel has, the path of a name matched to the channel names with case and punctuation ignored |
| `pastHours` | `keyed` only: a program that ended more than this many hours ago is dropped |
| `map` | `title`, `start`, `end` (required; `end` after `start`), `description`, `subTitle`, `icon`, `category`, `rating`, `season` and `episode` (positive numbers become episode numbers), as fields over a program item |

The guide lists every channel and holds programs only for the channels the lineup wants.

<a id="guide-keyed"></a>A `keyed` guide whose request pages by `cursorPath` runs again for as long as a wanted channel has no programs yet, up to `maxPages`. Each run starts at the first such channel (`${cursor}` is its id and `${channels.count}` the number of channels listed), and `cursorPath` is the path in a program item of the station it belongs to. A station keeps the programs of the first run that carried it. A request paged by `cursorPath` is not cached.

`resolve`:

| Key | Default | Meaning |
| --- | --- | --- |
| `url` | Required | A template |
| `format` | `[]` | Templates filling each `%s` of the URL in order |
| `macros` | `{}` | Values for the ad-insertion names of the URL (`[NAME]`, `{NAME}`), as templates; unnamed ones become empty |
| `headers` | `{}` | The headers the relay sends with the stream |
| `expiresSeconds` | None | How long the URL works |
| `missing` | `Channel is no longer listed` | The message when the channel is gone from the service |

A channel that is not in the listing fails permanently after the listing is read once more, as for the Kotlin clients.

## Templates

A template is text with `${name}` substitutions; bracketed ad macros are left for `macros`.

| Name | Value |
| --- | --- |
| `${option}` | The source file's option of that name, else the manifest's `defaults`; the name must be a default or a `catalog` option |
| `${page}` | The page of a paged request |
| `${cursor}`, `${channels.count}` | In a request paged by `cursorPath`: the channel the run starts at, and the number of channels listed |
| `${channel.id}` | The channel's id |
| `${channel.vars.NAME}` | A variable of the channel, in a per-channel guide request, `join.on` and `resolve` |
| `${device.id}` | The provider's random per-install device id |
| `${ua.browser}` | The desktop Chrome user agent the services' web players use |
| `${time.epoch}` | Seconds since 1970 |
| `${time.bucket:N}` | Seconds since 1970 divided by N, rounded down |
| `${time.isoMillis:+Nh}` | Now plus N hours (the sign is optional), as `2026-10-17T23:00:00.000Z` |
| `${time.floorHour:+Nh}` | The start of this hour plus N hours, in the same form |

Values are not URL-encoded.

## Write one

Copy `cw.json` or `hallmark.json` from `server/src/main/resources/providers/` and change what the service returns. Put the service's sanitized answers under `server/src/test/resources/providers/`, test it in a `manifest/<Service>ManifestTest` against `ProviderFixture` (`manifestClient` in `ManifestTestSupport` builds the client), and check it against the live service with `scripts/provider-smoke.py`. A shipped manifest also goes in `SHIPPED_NAMES` in `ProviderManifests`, and `ProviderManifestsTest` checks that it validates and that the service is in the configuration table of [providers](providers.md).
