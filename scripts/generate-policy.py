#!/usr/bin/env python3
"""Generate a lineup policy from a running server's lineup and a legacy channel export.

    scripts/generate-policy.py --server http://127.0.0.1:8080 \
        --admin-token "$SOFA_ADMIN_TOKEN" --api-token "$SOFA_API_TOKEN" \
        --legacy legacy-only-channels.json --replacements replacements.json \
        --starter-groups deploy/curation-starter.json --out policy/policy.json

Reads `GET /api/v1/providers`, `GET /api/v1/admin/lineup` and `GET /api/v1/admin/candidates` (paged). Writes:

- `providers`: the native order given by --providers, keeping only providers the server has; `localnow` is
  split into an enabled `My City` entry, a disabled `More Cities` entry and an entry for the rest of its channels,
  and `vizio` excludes `Local channels`.
- `groups.map`, `groups.weak` and `groups.patterns` from the starter file's `groupMap`, `weak` and `patterns`, with
  `Entertainment` as the default. `--local-pattern REGEX` puts a name pattern for the household's own market first,
  so its stations group as `Local`.
- `names.version` 2 and `health.requireVerified`, so only stations with a source that has qualified healthy show.
- `numbering.groups`: one block per group (`GROUP_BLOCKS`), each running to the next block's start, with the floor
  after the last block, and `numbering.followGroup`, so a station the server numbered follows its group's block.
- `names.aliases`: for every enabled lineup entry whose native sources carry other keys than the entry's own,
  each other key aliased to the entry's. Every alias is printed for review.
- `stations`: one `required` station per legacy channel, keyed by the key of its name (or its replacement's
  name), pinned to its sources (or its replacement's) by provider and `tvg-id`, with its number, name and group.
  Those numbers are overrides, so they are reserved before any group block is filled.

The output is sorted JSON with a two-space indent. Review it, then check it with `POST /api/v1/admin/policy/check`.
Python 3 standard library only. `--offline FILE` reads the three server responses from one JSON file instead
(`{"providers": [...], "lineup": [...], "candidates": {"<provider>": [...]}}`), for tests.
"""
import argparse
import json
import sys
import unicodedata
import urllib.parse
import urllib.request

NATIVE_ORDER = (
    'whale,samsung,vizio,lg,pluto,plex,roku,xumo,tubi,tcl,stirr,freelivesports,localnow,pbs,hallmark,ballysports,adultswim'
)

NAMES_VERSION = 2

# Each block runs to the next block's start, sized for the free services' catalogs with room to grow.
GROUP_BLOCKS = {
    'Local': 1, 'Local News (US)': 10, 'News': 100, 'Sports': 300, 'En Español': 600, 'Entertainment': 700,
    'Drama': 900, 'Classic TV': 1050, 'Movies': 1200, 'Sci-Fi & Horror': 1400, 'Comedy': 1500, 'Crime': 1600,
    'Documentary': 1700, 'Lifestyle': 1850, 'Food & Travel': 2100, 'Kids & Family': 2200, 'Anime': 2300,
    'Music': 2350, 'Game Shows': 2500, 'Faith': 2550, 'Shopping': 2580, 'Ambiance': 2600,
}
GROUP_SIZE = 400
# Stations the policy keeps off, and new stations past a full block, number from here, clear of every block.
NUMBER_FLOOR = 3000


def base_name(name):
    """Version 1 normalization, a port of `baseName` in StationKeys.kt; keep the two identical."""
    decomposed = ''.join(c for c in unicodedata.normalize('NFKD', name) if unicodedata.category(c) != 'Mn').lower()
    spelled = decomposed.replace('&', ' and ').replace('+', ' plus ')
    kept = ''.join(c if unicodedata.category(c)[0] == 'L' or unicodedata.category(c) in ('Nd', 'Mc') else ' ' for c in spelled)
    words = [w for w in kept.split(' ') if w]
    if len(words) > 1 and words[0] == 'the':
        words = words[1:]
    if len(words) > 1 and words[-1] == 'hd':
        words = words[:-1]
    return ' '.join(words)


def key_name(name, version):
    """The normalized name under names.version, a port of `normalizedName` in StationKeys.kt."""
    base = base_name(name)
    return base.replace(' ', '') if version >= 2 else base


def pin_identity(pin):
    if pin.get('tvgId') is not None:
        return '%s/tvg-id:%s' % (pin['provider'], pin['tvgId'])
    return '%s/name:%s' % (pin['provider'], (pin.get('name') or '').strip().lower())


def provider_entries(order, configured):
    entries = []
    for provider in order:
        if provider not in configured:
            print('provider %s: not configured on the server, left out' % provider, file=sys.stderr)
            continue
        if provider == 'localnow':
            entries.append({'id': 'localnow', 'include': {'groups': ['My City']}})
            entries.append({'id': 'localnow', 'include': {'groups': ['More Cities']}, 'enabled': False})
            entries.append({'id': 'localnow'})
        elif provider == 'vizio':
            entries.append({'id': 'vizio', 'exclude': {'groups': ['Local channels']}})
        else:
            entries.append({'id': provider})
    return entries


def aliases_from_lineup(lineup, native):
    aliases = {}
    for entry in sorted(lineup, key=lambda e: (e.get('number') or '', e['id'])):
        if not entry.get('enabled', True):
            continue
        own = key_name(entry['name'], NAMES_VERSION)
        names = sorted({key_name(o['channelName'], NAMES_VERSION) for o in entry.get('offerings', [])
                        if o.get('providerId') in native and o.get('channelName')})
        for name in names:
            if name == own or not name:
                continue
            if name in aliases and aliases[name] != own:
                print('alias %r -> %r skipped: already %r' % (name, own, aliases[name]), file=sys.stderr)
                continue
            aliases[name] = own
    for source in sorted(aliases):
        target = aliases[source]
        if target in aliases:
            print('alias %r -> %r dropped: %r is itself an alias' % (source, target, target), file=sys.stderr)
    aliases = {s: t for s, t in aliases.items() if t not in aliases}
    for source in sorted(aliases):
        print('alias %r -> %r' % (source, aliases[source]))
    return aliases


def replacement_of(value):
    if value is None:
        return None
    if isinstance(value, list):
        return {'name': None, 'sources': value}
    return {'name': value.get('name'), 'sources': value.get('sources', [])}


def legacy_stations(channels, replacements, aliases, offered, keep_numbers=False):
    stations = {}
    claimed = {}
    numbers = {}
    for channel in channels:
        replacement = replacement_of(replacements.get(channel['name']))
        key = key_name((replacement or {}).get('name') or channel['name'], NAMES_VERSION)
        key = aliases.get(key, key)
        if key in stations:
            print('legacy %r: key %r already taken by another legacy channel, skipped' % (channel['name'], key), file=sys.stderr)
            continue
        if replacement is not None:
            raw = [{'provider': p['provider'], 'tvgId': p.get('tvgId'), 'name': p.get('name')} for p in replacement['sources']]
        else:
            raw = [{'provider': s['provider'], 'tvgId': s.get('tvgId'), 'name': s.get('channelName')} for s in channel.get('sources', [])]
        pins = []
        for pin in raw:
            if pin['tvgId'] is None and not pin['name']:
                continue
            identity = pin_identity(pin)
            if identity in claimed:
                print('legacy %r: pin %s already pinned by %r, skipped' % (channel['name'], identity, claimed[identity]), file=sys.stderr)
                continue
            if identity not in offered:
                print('legacy %r: pin %s is not offered by the server now' % (channel['name'], identity), file=sys.stderr)
            claimed[identity] = key
            pins.append({k: v for k, v in pin.items() if v is not None})
        station = {'required': True, 'name': channel['name'], 'sources': pins}
        if keep_numbers and channel.get('number'):
            number = channel['number']
            if number in numbers:
                print('legacy %r: number %s already used by %r, left unset' % (channel['name'], number, numbers[number]), file=sys.stderr)
            else:
                numbers[number] = key
                station['number'] = number
        if channel.get('group'):
            station['group'] = channel['group']
        stations[key] = station
        print('station %r: %d pins%s' % (key, len(pins), ' (replacement)' if replacement is not None else ''))
    return stations


def starter_groups(starter, local_pattern=None):
    """`groups` from a starter file: its map and weak list lowercased, a local-market name pattern ahead of its patterns."""
    groups = {'map': {k.strip().lower(): v for k, v in starter.get('groupMap', {}).items()}, 'default': 'Entertainment'}
    weak = sorted({w.strip().lower() for w in starter.get('weak', [])})
    patterns = ([{'name': local_pattern, 'group': 'Local'}] if local_pattern else []) + list(starter.get('patterns', []))
    if weak:
        groups['weak'] = weak
    if patterns:
        groups['patterns'] = patterns
    return groups


def generate(providers, lineup, candidates, legacy, replacements, starter, order, keep_legacy_numbers=False, local_pattern=None):
    """The policy as a dict. [candidates] maps provider id to its candidate items."""
    configured = {p['id'] for p in providers}
    native = [p for p in order if p in configured]
    offered = set()
    for items in candidates.values():
        for item in items:
            if item.get('tvgId') is not None:
                offered.add(pin_identity({'provider': item['providerId'], 'tvgId': item['tvgId']}))
            offered.add(pin_identity({'provider': item['providerId'], 'name': item['name']}))
    aliases = aliases_from_lineup(lineup, set(native))
    return {
        'version': 1,
        'names': {'version': NAMES_VERSION, 'aliases': aliases},
        'providers': provider_entries(native, configured),
        'groups': starter_groups(starter, local_pattern),
        'numbering': {'floor': NUMBER_FLOOR, 'groups': dict(GROUP_BLOCKS), 'groupSize': GROUP_SIZE, 'followGroup': True},
        'health': {'requireVerified': True},
        'stations': legacy_stations(legacy.get('channels', []), replacements, aliases, offered, keep_legacy_numbers),
    }


def fetch(server, path, token):
    request = urllib.request.Request(server.rstrip('/') + path, headers={'Authorization': 'Bearer ' + token} if token else {})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def from_server(args, order, pin_providers):
    admin = args.admin_token or args.api_token
    providers = fetch(args.server, '/api/v1/providers', args.api_token)
    lineup = fetch(args.server, '/api/v1/admin/lineup', admin)
    candidates = {}
    configured = {p['id'] for p in providers}
    for provider in sorted((set(order) | pin_providers) & configured):
        items, offset = [], 0
        while True:
            query = urllib.parse.urlencode({'provider': provider, 'limit': 1000, 'offset': offset})
            page = fetch(args.server, '/api/v1/admin/candidates?' + query, admin)
            items.extend(page['items'])
            offset += len(page['items'])
            if not page['items'] or offset >= page['total']:
                break
        candidates[provider] = items
    return providers, lineup, candidates


def load(path, default):
    if not path:
        return default
    with open(path, encoding='utf-8') as handle:
        return json.load(handle)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--server')
    parser.add_argument('--admin-token')
    parser.add_argument('--api-token')
    parser.add_argument('--legacy', help='legacy export: {"channels": [{"name", "number", "group", "sources": [{"provider", "channelName", "tvgId"}]}]}')
    parser.add_argument('--out', required=True)
    parser.add_argument('--providers', default=NATIVE_ORDER, help='native provider priority, comma separated')
    parser.add_argument('--starter-groups', help='a file with a groupMap, weak and patterns, such as deploy/curation-starter.json')
    parser.add_argument('--local-pattern', help='a name regex for the household\'s own market, grouped as Local ahead of every other rule')
    parser.add_argument('--replacements', help='export channel name to pins, or to {"name", "sources"}')
    parser.add_argument('--keep-legacy-numbers', action='store_true', help='keep the exported numbers of legacy channels instead of numbering them in their group blocks')
    parser.add_argument('--offline', help='read providers, lineup and candidates from this file instead of the server')
    args = parser.parse_args()
    order = [p.strip() for p in args.providers.split(',') if p.strip()]
    legacy = load(args.legacy, {'channels': []})
    replacements = load(args.replacements, {})
    starter = load(args.starter_groups, {})
    pin_providers = {s['provider'] for c in legacy.get('channels', []) for s in c.get('sources', [])}
    for value in replacements.values():
        pin_providers |= {p['provider'] for p in replacement_of(value)['sources']}
    if args.offline:
        data = load(args.offline, {})
        providers, lineup, candidates = data['providers'], data['lineup'], data.get('candidates', {})
    elif args.server:
        providers, lineup, candidates = from_server(args, order, pin_providers)
    else:
        parser.error('give --server or --offline')
    policy = generate(providers, lineup, candidates, legacy, replacements, starter, order, args.keep_legacy_numbers, args.local_pattern)
    with open(args.out, 'w', encoding='utf-8') as handle:
        json.dump(policy, handle, indent=2, sort_keys=True, ensure_ascii=False)
        handle.write('\n')
    print('wrote %s: %d providers entries, %d aliases, %d stations' % (
        args.out, len(policy['providers']), len(policy['names']['aliases']), len(policy['stations'])))


if __name__ == '__main__':
    main()
