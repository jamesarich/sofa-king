#!/usr/bin/env python3
"""Send a Dispatcharr export to the backend's lineup import and print the report.

    python3 scripts/dispatcharr-import.py --file sofaking.json --dry-run
    python3 scripts/dispatcharr-import.py --file sofaking.json --guide-map 4=gracenote

The token comes from --token, SOFA_ADMIN_TOKEN or SOFA_API_TOKEN. The export file
contains tokenized stream URLs; delete it once the lineup is in place.
"""
import argparse
import json
import os
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--file', required=True, help='JSON written by scripts/dispatcharr-export.py')
    parser.add_argument('--server', default='http://127.0.0.1:8080')
    parser.add_argument('--token', default=os.environ.get('SOFA_ADMIN_TOKEN') or os.environ.get('SOFA_API_TOKEN'))
    parser.add_argument('--guide-map', action='append', default=[], metavar='EPG_SOURCE_ID=GUIDE_ID',
                        help='Map a Dispatcharr EPG source id to a configured guide id when URLs differ')
    parser.add_argument('--dry-run', action='store_true', help='Report matches without changing the lineup')
    parser.add_argument('--include-unmatched', action='store_true', help='Create entries even when no stream matched a source')
    parser.add_argument('--verbose', action='store_true', help='Print every entry, not only the problems')
    args = parser.parse_args()
    with open(args.file) as handle:
        export = json.load(handle)
    guide_map = {}
    for item in args.guide_map:
        key, _, value = item.partition('=')
        if not key or not value:
            raise SystemExit('--guide-map takes EPG_SOURCE_ID=GUIDE_ID')
        guide_map[key] = value
    body = json.dumps({'export': export, 'guideMap': guide_map, 'dryRun': args.dry_run, 'includeUnmatched': args.include_unmatched}).encode()
    headers = {'Content-Type': 'application/json'}
    if args.token:
        headers['Authorization'] = 'Bearer ' + args.token
    request = Request(args.server.rstrip('/') + '/api/v1/admin/lineup/import', data=body, method='POST', headers=headers)
    try:
        with urlopen(request, timeout=120) as response:
            report = json.load(response)
    except HTTPError as error:
        raise SystemExit('import failed: HTTP %d %s' % (error.code, error.read().decode(errors='replace')))
    mode = 'dry run' if report['dryRun'] else 'applied'
    print('%s: created=%d updated=%d skipped=%d streams matched=%d unmatched=%d' % (
        mode, report['created'], report['updated'], report['skipped'], report['matchedStreams'], report['unmatchedStreams']))
    for source_id, guide_id in sorted(report.get('guideSources', {}).items()):
        print('epg source %s -> %s' % (source_id, guide_id or 'UNMAPPED (pass --guide-map %s=GUIDE_ID)' % source_id))
    problems = 0
    for entry in report['entries']:
        unmatched = entry.get('unmatchedStreams', [])
        problem = entry['action'] == 'skipped' or unmatched or entry['guide'] == 'unmapped-source'
        if problem:
            problems += 1
        if problem or args.verbose:
            print('%-8s %-6s %-40s sources=%d guide=%s%s' % (
                entry['action'], entry['number'], entry['name'][:40], entry['matchedStreams'], entry['guide'],
                (' unmatched=' + ', '.join(unmatched)) if unmatched else ''))
    print('%d entries need attention' % problems)
    return 0 if problems == 0 else 2


if __name__ == '__main__':
    sys.exit(main())
