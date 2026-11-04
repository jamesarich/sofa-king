#!/usr/bin/env python3
"""Render docs/api.md's endpoint tables from the OpenAPI document.

Every operation's first tag names the table it belongs to. A table sits between
`<!-- api-tables:start:TAG -->` and `<!-- api-tables:end -->` in the page; the script rewrites what is
between the markers and leaves the rest of the page alone. A row's text is the first paragraph of the operation's
`description` when it has one, else its `summary`; the summary stays a short plain title.

  python3 -I scripts/api-tables.py          rewrite the page
  python3 -I scripts/api-tables.py --check  exit 1 with a diff when the page would change
"""
import argparse
import difflib
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_DOCUMENT = os.path.join(ROOT, 'server/src/main/resources/api/openapi.json')
DEFAULT_PAGE = os.path.join(ROOT, 'docs/api.md')

METHODS = ['get', 'post', 'put', 'patch', 'delete', 'head', 'options']
REGION = re.compile(r'<!-- api-tables:start:(?P<tag>[^>]+?) -->\n(?P<body>.*?)<!-- api-tables:end -->\n', re.S)


class TablesError(Exception):
    pass


def query_names(document, item, operation):
    """The names of the operation's query parameters, in the order the document lists them."""
    names = []
    for parameter in item.get('parameters', []) + operation.get('parameters', []):
        if '$ref' in parameter:
            node = document
            for part in parameter['$ref'].lstrip('#/').split('/'):
                node = node[part]
            parameter = node
        if parameter.get('in') == 'query' and parameter['name'] not in names:
            names.append(parameter['name'])
    return names


def cell_text(operation):
    """The table's text for an operation: the first paragraph of its description, else its summary."""
    description = operation.get('description', '').strip()
    text = description.split('\n\n')[0] if description else operation['summary'].strip()
    return ' '.join(text.split())


def operations_by_tag(document):
    """tag -> [(path, method, summary)], sorted by path, then by method."""
    tags = {}
    for path, item in document['paths'].items():
        for method in METHODS:
            operation = item.get(method)
            if operation is None:
                continue
            where = '%s %s' % (method.upper(), path)
            if not operation.get('tags'):
                raise TablesError('%s has no tags' % where)
            if not operation.get('summary', '').strip():
                raise TablesError('%s needs a summary' % where)
            summary = cell_text(operation)
            shown = path
            names = query_names(document, item, operation)
            if names:
                shown += '?' + '&'.join('%s=' % name for name in names)
            tags.setdefault(operation['tags'][0], []).append((path, method, shown, summary))
    for rows in tags.values():
        rows.sort(key=lambda row: (row[0], METHODS.index(row[1])))
    return tags


def render(rows):
    lines = ['| Method | Path | Summary |', '| --- | --- | --- |']
    for _, method, shown, summary in rows:
        lines.append('| %s | `%s` | %s |' % (method.upper(), shown, summary.replace('|', '\\|')))
    return '\n' + '\n'.join(lines) + '\n\n'


def rewrite(document, page):
    tags = operations_by_tag(document)
    seen = set()

    def fill(match):
        tag = match.group('tag')
        if tag in seen:
            raise TablesError('the page has two tables for the tag %r' % tag)
        seen.add(tag)
        if tag not in tags:
            raise TablesError('the page has a table for the tag %r, which no operation uses' % tag)
        return '<!-- api-tables:start:%s -->\n%s<!-- api-tables:end -->\n' % (tag, render(tags[tag]))

    result = REGION.sub(fill, page)
    missing = sorted(set(tags) - seen)
    if missing:
        raise TablesError('no table on the page for the tags: %s' % ', '.join(missing))
    return result


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--check', action='store_true', help='exit 1 with a diff when the page would change')
    parser.add_argument('--openapi', default=DEFAULT_DOCUMENT)
    parser.add_argument('--page', default=DEFAULT_PAGE)
    args = parser.parse_args(argv)
    with open(args.openapi, encoding='utf-8') as handle:
        document = json.load(handle)
    with open(args.page, encoding='utf-8') as handle:
        page = handle.read()
    try:
        result = rewrite(document, page)
    except TablesError as error:
        print('api-tables: %s' % error, file=sys.stderr)
        return 2
    if result == page:
        return 0
    if args.check:
        sys.stdout.writelines(difflib.unified_diff(
            page.splitlines(True), result.splitlines(True), 'docs/api.md', 'docs/api.md (generated)'))
        print('api-tables: run scripts/api-tables.py and commit docs/api.md', file=sys.stderr)
        return 1
    with open(args.page, 'w', encoding='utf-8') as handle:
        handle.write(result)
    print('api-tables: updated %s' % args.page)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
