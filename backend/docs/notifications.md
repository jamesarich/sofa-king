# Notifications

The server tells you when something happens: a recording starts, finishes, fails or has no free tuner slot, a source is quarantined or recovers, the recordings disk runs low, an update is published, or a library scan adds files. It sends each to the targets you add: an [ntfy](https://ntfy.sh) topic for your phone, an [Apprise API](https://github.com/caronc/apprise-api) server for Discord, Telegram, Pushover, Gotify, email and the rest, or any webhook, such as a Home Assistant automation.

## Add a target

Open Settings in the [admin portal](admin.md) and choose the Connect tab. Add a target takes:

| Field | What it is |
| --- | --- |
| Name | What the table calls it, such as Phone or Home Assistant |
| Kind | ntfy, Apprise API or Webhook (JSON) |
| URL | Where to send: the ntfy topic URL, the Apprise API notify URL, or the webhook URL. `http` or `https`, with no user name in it. Addresses on your network and on the server itself are allowed: unlike a source, a target is your own choice |
| Secret | Optional. ntfy's access token, sent as `Authorization: Bearer`, or the key a webhook's body is signed with. The server never shows it again; leave it blank when you edit a target to keep it |
| Events | What the target receives, below. A new target takes all but the attention items, which the home page already shows |

Then choose Test on its row: the server sends a test notification at once and the page says whether it arrived. Turn off pauses a target without losing it, Edit changes it, and Remove asks once, then deletes it with its deliveries. The Last delivery column shows whether the newest one arrived, or why it failed.

The same targets are in the [admin API](api.md#notifications) under `/api/v1/admin/notifications`.

## Events

| Event | When |
| --- | --- |
| `recording.scheduled` | A recording is scheduled, by hand or by a rule |
| `recording.started` | A recording starts |
| `recording.finished` | A recording finishes |
| `recording.failed` | A recording fails or is missed, with the reason |
| `recording.conflict` | A scheduled recording newly has no free slot at its time. Conflicts the server finds when it starts are not sent again |
| `source.quarantined` | A source fails its probes and is set aside ([source health](source-health.md)) |
| `source.recovered` | A quarantined source passes its probes again |
| `disk.low` | The recordings disk drops under the free space [cleanup](recordings.md#cleanup) keeps; once, until it is back over |
| `update.available` | A newer server image is published ([update](admin.md#update)); once per build |
| `library.added` | A library scan adds files, with up to ten of their names |
| `attention.raised` | An item appears on the home page's attention list, or gets worse |
| `attention.cleared` | An item leaves the attention list |

## What each kind receives

**ntfy** gets the message as the body, and the title, tags and priority as the `title`, `tags` and `priority` query parameters: a failure is `high` priority, the rest `default`. Use the topic's full URL, such as `https://ntfy.sh/your-topic` or `http://192.0.2.20:8080/sofa`, and the access token as the secret when the topic is protected.

**Apprise API** gets a JSON body of `title`, `body` and `type` (`info`, `success`, `warning` or `failure`). Point it at a key you saved in Apprise with your services' URLs, such as `http://192.0.2.21:8000/notify/sofa`; Apprise then sends to each of them.

**Webhook** gets a JSON envelope, with the event in the `X-SofaKing-Event` header too:

```json
{
  "eventType": "recording.finished",
  "instanceName": "Sofa King",
  "serverId": "6f1c…",
  "version": "1.4.0",
  "occurredAt": "2026-10-25T20:31:07Z",
  "title": "Recording finished",
  "message": "The Evening News on KQED",
  "path": "/admin/recordings",
  "data": { "id": "…", "title": "The Evening News", "channelName": "KQED", "state": "completed", "bytes": 1832009216 }
}
```

`data` holds the event's own fields, as the [event stream](api.md#event-stream) has them, and `path` is the portal page about it, when there is one. With a secret set, `X-SofaKing-Signature` carries `sha256=` and the hex HMAC-SHA256 of the raw body under the secret. Check it before you trust a body:

```python
import hashlib, hmac

def genuine(body: bytes, header: str, secret: str) -> bool:
    expected = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, header)
```

## Delivery

Notifications go out in the background, four at a time, so a slow target holds up nothing else. A target that fails or does not answer is tried twice more, after 2 and 10 seconds; one that refuses with a 4xx other than 408 and 429 is not tried again. Up to 256 wait at once; past that a new one is dropped, with a warning in the log. The server keeps each target's newest 200 deliveries, which the API lists. The log names a target by its id, never by its URL, since a URL can carry a topic or a key.

Nothing is replayed: a notification for an event while a target was off, or while the server was down, is not sent later.

## Recipes

### Home Assistant

Add an automation with a Webhook trigger, and note its webhook ID. Then add a Webhook target with the URL `http://HOME_ASSISTANT:8123/api/webhook/WEBHOOK_ID`. The automation reads the envelope as `trigger.json`, so a condition on `trigger.json.eventType` picks the events, and `trigger.json.message` is ready to show. Home Assistant only takes a webhook from your own network by default, which suits a server on the same network.

### Discord, Telegram, Pushover and others

Run the Apprise API, save a key holding the services' Apprise URLs (`discord://WEBHOOK_ID/WEBHOOK_TOKEN`, `tgram://BOT_TOKEN/CHAT_ID` and so on), and add an Apprise API target with `http://APPRISE:8000/notify/KEY`.

### Your phone

Install the ntfy app, subscribe to a topic, and add an ntfy target with the topic's URL. Pick a topic name nobody would guess, or protect it with an access token, since anyone who knows a public topic can read it.
