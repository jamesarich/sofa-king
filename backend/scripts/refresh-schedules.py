#!/usr/bin/env python3
"""Refresh verified broadcaster schedules as atomic XMLTV files."""
import datetime as dt
import json
import pathlib
import re
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET

UTC = dt.timezone.utc

def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'}), timeout=40) as response:
        data = response.read(20_000_001)
    if len(data) > 20_000_000:
        raise ValueError('Feed exceeds size limit')
    return data

def stamp(value):
    return value.astimezone(UTC).strftime('%Y%m%d%H%M%S +0000')

def value(item, key):
    text = item.get(key) or {}
    return text.get('value', '') if isinstance(text, dict) else str(text)

def wurl(data):
    doc = json.loads(data)
    channel = doc['channelData']['id']
    root = ET.Element('tv')
    ET.SubElement(ET.SubElement(root, 'channel', id=channel), 'display-name').text = doc['channelData']['name']
    assets = {}
    for key in ('movies', 'shortFormVideos', 'tvSpecials'):
        for asset in (doc.get(key) or []):
            assets[asset['id']] = (asset, None)
    for series in (doc.get('series') or []):
        for season in (series.get('seasons') or []):
            for episode in (season.get('episodes') or []):
                assets[episode['id']] = (episode, value(series, 'title'))
    for slot in doc['schedules']:
        start = dt.datetime.fromisoformat(slot['startDateTime'].replace('Z', '+00:00'))
        end = start + dt.timedelta(seconds=slot['durSecs'])
        if end <= dt.datetime.now(UTC):
            continue
        asset, series = assets[slot['id']]
        title = series or value(asset, 'title')
        if not title or end <= start:
            raise ValueError('Invalid program')
        p = ET.SubElement(root, 'programme', channel=channel, start=stamp(start), stop=stamp(end))
        ET.SubElement(p, 'title').text = title
        if series:
            ET.SubElement(p, 'sub-title').text = value(asset, 'title')
        if value(asset, 'description'):
            ET.SubElement(p, 'desc').text = value(asset, 'description')
    return root

def ctvcraft(data):
    doc = ET.fromstring(data)
    if doc.tag != 'channels':
        raise ValueError('Not a CTVCraft export')
    root = ET.Element('tv')
    for source in doc.findall('channel'):
        channel = source.attrib['id']
        ET.SubElement(ET.SubElement(root, 'channel', id=channel), 'display-name').text = source.findtext('title')
        for item in source.findall('items/item'):
            start = dt.datetime.fromisoformat(item.findtext('startTime'))
            if start.tzinfo is None:
                raise ValueError('Program without timezone')
            end = start + dt.timedelta(seconds=int(item.findtext('duration')))
            if end <= dt.datetime.now(UTC):
                continue
            p = ET.SubElement(root, 'programme', channel=channel, start=stamp(start), stop=stamp(end))
            ET.SubElement(p, 'title').text = item.findtext('title')
            if item.findtext('description'):
                ET.SubElement(p, 'desc').text = item.findtext('description')
            if item.findtext('genre'):
                ET.SubElement(p, 'category').text = item.findtext('genre')
    return root

def xmltv(data, kind):
    root = ET.fromstring(data)
    if root.tag != 'tv':
        raise ValueError('Not XMLTV')
    for p in root.findall('programme'):
        for attr in ('start', 'stop'):
            text = p.attrib[attr]
            if kind == 'frequency':
                # Frequency explicitly supplies zero offset but omits its plus sign.
                text = re.sub(r' 0000$', ' +0000', text)
            elif kind == 'cgtn':
                text = stamp(dt.datetime.fromisoformat(text))
            p.set(attr, text)
    return root

def refresh(config, output):
    output.mkdir(parents=True, exist_ok=True)
    failures = 0
    for source in config:
        try:
            if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,63}', source['id']):
                raise ValueError('Invalid feed id')
            if source['kind'] not in ('wurl', 'ctvcraft', 'frequency', 'cgtn'):
                raise ValueError('Unknown feed format')
            url = source['url'].replace('{date}', dt.datetime.now(UTC).strftime('%Y-%m-%d'))
            data = fetch(url)
            if source['kind'] == 'wurl':
                root = wurl(data)
            elif source['kind'] == 'ctvcraft':
                root = ctvcraft(data)
            else:
                root = xmltv(data, source['kind'])
            programs = root.findall('programme')
            upcoming = 0
            for p in programs:
                start = dt.datetime.strptime(p.attrib['start'], '%Y%m%d%H%M%S %z')
                end = dt.datetime.strptime(p.attrib['stop'], '%Y%m%d%H%M%S %z')
                if end <= start or not p.findtext('title'):
                    raise ValueError('Invalid program')
                upcoming += end > dt.datetime.now(UTC)
            if not upcoming:
                raise ValueError('No upcoming programs; retaining previous feed')
            target = output / (source['id'] + '.xml')
            with tempfile.NamedTemporaryFile(dir=output, suffix='.tmp', delete=False) as file:
                temporary = pathlib.Path(file.name)
            try:
                ET.ElementTree(root).write(temporary, encoding='utf-8', xml_declaration=True)
                temporary.chmod(0o644)
                temporary.replace(target)
            finally:
                temporary.unlink(missing_ok=True)
            print(source['id'], 'upcoming', upcoming, flush=True)
        except Exception as error:
            failures += 1
            print(source['id'], 'failed', type(error).__name__, file=sys.stderr, flush=True)
    return failures

if __name__ == '__main__':
    sys.exit(bool(refresh(json.loads(pathlib.Path(sys.argv[1]).read_text()), pathlib.Path(sys.argv[2]))))
