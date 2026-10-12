#!/usr/bin/env python3
"""Generate a lineup policy from a running server's providers and lineup.

    scripts/generate-policy.py --server http://127.0.0.1:8080 \
        --admin-token "$SOFA_ADMIN_TOKEN" --api-token "$SOFA_API_TOKEN" \
        --starter-groups deploy/curation-starter.json --out policy/policy.json

Reads `GET /api/v1/providers` and `GET /api/v1/admin/lineup`. Writes:

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

The output is sorted JSON with a two-space indent. Review it, then preview it with `GET /api/v1/admin/policy/preview`.
Python 3 standard library only. `--offline FILE` reads the two server responses from one JSON file instead
(`{"providers": [...], "lineup": [...]}`), for tests.
"""
import argparse
import json
import sys
import unicodedata
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


def generate(providers, lineup, starter, order, local_pattern=None):
    """The policy as a dict."""
    configured = {p['id'] for p in providers}
    native = [p for p in order if p in configured]
    return {
        'version': 1,
        'names': {'version': NAMES_VERSION, 'aliases': aliases_from_lineup(lineup, set(native))},
        'providers': provider_entries(native, configured),
        'groups': starter_groups(starter, local_pattern),
        'numbering': {'floor': NUMBER_FLOOR, 'groups': dict(GROUP_BLOCKS), 'groupSize': GROUP_SIZE, 'followGroup': True},
        'health': {'requireVerified': True},
    }


def fetch(server, path, token):
    request = urllib.request.Request(server.rstrip('/') + path, headers={'Authorization': 'Bearer ' + token} if token else {})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def from_server(args):
    providers = fetch(args.server, '/api/v1/providers', args.api_token)
    lineup = fetch(args.server, '/api/v1/admin/lineup', args.admin_token or args.api_token)
    return providers, lineup


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
    parser.add_argument('--out', required=True)
    parser.add_argument('--providers', default=NATIVE_ORDER, help='native provider priority, comma separated')
    parser.add_argument('--starter-groups', help='a file with a groupMap, weak and patterns, such as deploy/curation-starter.json')
    parser.add_argument('--local-pattern', help='a name regex for the household\'s own market, grouped as Local ahead of every other rule')
    parser.add_argument('--offline', help='read providers and lineup from this file instead of the server')
    args = parser.parse_args()
    order = [p.strip() for p in args.providers.split(',') if p.strip()]
    starter = load(args.starter_groups, {})
    if args.offline:
        data = load(args.offline, {})
        providers, lineup = data['providers'], data['lineup']
    elif args.server:
        providers, lineup = from_server(args)
    else:
        parser.error('give --server or --offline')
    policy = generate(providers, lineup, starter, order, args.local_pattern)
    with open(args.out, 'w', encoding='utf-8') as handle:
        json.dump(policy, handle, indent=2, sort_keys=True, ensure_ascii=False)
        handle.write('\n')
    print('wrote %s: %d providers entries, %d aliases' % (args.out, len(policy['providers']), len(policy['names']['aliases'])))


if __name__ == '__main__':
    main()
