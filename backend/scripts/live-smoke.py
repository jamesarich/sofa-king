#!/usr/bin/env python3
"""Import fixture feeds, decode video through the relay, verify probe health and restart."""
import base64
import functools
import gzip
import http.server
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

APP = Path(__file__).resolve().parents[1] / 'server/build/install/server/bin/server'
TOKEN = 'local-fixture-token-' * 3
OUTPUT_TOKEN = 'local-player-token-' + 'x' * 32


class Handler(http.server.SimpleHTTPRequestHandler):
    active_streams = 0
    lock = threading.Lock()

    def do_GET(self):
        if self.path != '/endless.ts':
            return super().do_GET()
        with self.lock:
            Handler.active_streams += 1
        try:
            chunk = next(Path(self.directory).glob('*.ts')).read_bytes()
            self.send_response(200)
            self.send_header('Content-Type', 'video/mp2t')
            self.end_headers()
            while True:
                self.wfile.write(chunk)
                self.wfile.flush()
                time.sleep(0.1)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            with self.lock:
                Handler.active_streams -= 1

    def log_message(self, *args):
        pass


def request(base, path, method='GET', body=None):
    data = None if body is None else json.dumps(body).encode()
    req = Request(base + path, data=data, method=method,
                  headers={'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json'})
    with urlopen(req, timeout=5) as response:
        content = response.read().decode()
        return json.loads(content) if content and not content.startswith('#') else content


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==')


def check_logos(base, upstream, hls_channel, playlist, guide, data):
    """Every output names the cached copy on this server; the logo route serves it without a token."""
    assert hls_channel['logo'].startswith('/logos/'), hls_channel
    logo_url = base + hls_channel['logo']
    assert f'tvg-logo="{logo_url}"' in playlist and upstream not in playlist, playlist
    assert f'<icon src="{logo_url}"/>' in guide and upstream not in guide, guide
    with urlopen(logo_url, timeout=5) as response:
        assert response.headers['Content-Type'] == 'image/png' and response.headers['Cache-Control'] == 'public, max-age=86400', dict(response.headers)
        etag = response.headers['ETag']
        assert response.read() == PNG
    try:
        urlopen(Request(logo_url, headers={'If-None-Match': etag}), timeout=5)
        raise AssertionError('A matching ETag was not answered 304')
    except HTTPError as error:
        assert error.code == 304
    try:
        urlopen(base + '/logos/' + '0' * 64, timeout=5)
        raise AssertionError('An unknown logo key was served')
    except HTTPError as error:
        assert error.code == 404
    cached = list((data / 'logos').iterdir())
    assert len(cached) == 1 and cached[0].read_bytes() == PNG, cached


def check_player_outputs(base, upstream, hls_channel, ts_channel, directory):
    """The M3U/XMLTV output a third-party player loads, with FFmpeg standing in for the player."""
    try:
        urlopen(base + '/output/playlist.m3u', timeout=5)
        raise AssertionError('Player output served without a token')
    except HTTPError as error:
        assert error.code == 401
    with urlopen(f'{base}/output/playlist.m3u?token={OUTPUT_TOKEN}', timeout=5) as response:
        playlist = response.read().decode()
    lines = playlist.splitlines()
    assert lines[0].startswith('#EXTM3U url-tvg="') and f'/output/guide.xml?token={OUTPUT_TOKEN}' in lines[0], lines[0]
    hls_url = f'{base}/output/stream/{hls_channel["id"]}.m3u8?token={OUTPUT_TOKEN}'
    ts_url = f'{base}/output/stream/{ts_channel["id"]}.ts?token={OUTPUT_TOKEN}'
    assert hls_url in lines and ts_url in lines, playlist
    assert f'tvg-id="{hls_channel["id"]}" tvg-chno="5"' in playlist and 'group-title="Test"' in playlist, playlist
    with urlopen(f'{base}/output/guide.xml?token={OUTPUT_TOKEN}&days=1', timeout=5) as response:
        guide = response.read().decode()
    assert f'<channel id="{hls_channel["id"]}">' in guide and '<title>Real fixture program</title>' in guide, guide
    check_logos(base, upstream, hls_channel, playlist, guide, directory / 'data')
    # A player following the redirect decodes video through the session.
    decoded = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', hls_url, '-t', '4', '-map', '0:v:0', '-progress', 'pipe:1', '-f', 'null', '-'],
                             capture_output=True, timeout=25)
    assert decoded.returncode == 0 and b'progress=end' in decoded.stdout, 'Player output decode failed: ' + decoded.stderr.decode() + '\n' + (directory / 'server.log').read_text()
    # A TS player gets the fan-out for as long as it stays connected; hanging up releases the upstream.
    with urlopen(ts_url, timeout=5) as stream:
        head = stream.read(188 * 3)
        assert len(head) == 188 * 3 and head[0] == 0x47 and head[188] == 0x47, 'TS player output is not aligned MPEG-TS'
        assert Handler.active_streams == 1
    deadline = time.monotonic() + 10
    while Handler.active_streams:
        assert time.monotonic() < deadline, 'TS player hang-up left the upstream open'
        time.sleep(0.1)


def check_lineup_edit(base, entry):
    """A household edit through the admin API reaches the app's catalog."""
    assert request(base, '/api/v1/admin/lineup/' + entry['id'])['group'] == 'Test'
    changed = request(base, '/api/v1/admin/lineup/' + entry['id'], 'PATCH', {'group': 'Fixtures'})
    assert changed['group'] == 'Fixtures' and changed['createdBy'] == 'manual', changed
    assert request(base, '/api/v1/channels')['channels'][0]['tags'] == ['Fixtures']


def ffmpeg_children(process):
    listed = subprocess.run(['pgrep', '-P', str(process.pid), '-x', 'ffmpeg'], capture_output=True, text=True)
    return len(listed.stdout.split())


def check_hdhomerun(base, process, directory):
    """What Plex reads from the HDHomeRun emulation, then a remuxed stream whose FFmpeg dies with the client."""
    with urlopen(base + '/discover.json', timeout=5) as response:
        discover = json.loads(response.read())
    assert discover['TunerCount'] == 2 and len(discover['DeviceID']) == 8 and discover['LineupURL'] == base + '/lineup.json', discover
    with urlopen(base + '/lineup.json', timeout=5) as response:
        lineup = json.loads(response.read())
    assert [item['GuideNumber'] for item in lineup] == ['5', '6'] and lineup[0]['URL'] == base + '/auto/v5', lineup
    with urlopen(base + '/lineup_status.json', timeout=5) as response:
        assert json.loads(response.read())['ScanInProgress'] == 0
    with urlopen(base + '/device.xml', timeout=5) as response:
        assert '<friendlyName>Sofa King</friendlyName>' in response.read().decode()
    with urlopen(base + '/auto/v6', timeout=5) as stream:
        head = stream.read(188 * 3)
        assert head[0] == 0x47 and head[188] == 0x47 and head[376] == 0x47, 'HDHomeRun TS stream is not aligned'
    before = ffmpeg_children(process)
    # The HLS channel is remuxed to MPEG-TS; FFmpeg as the Plex stand-in decodes video from it.
    decoded = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'mpegts', '-i', base + '/auto/v5', '-t', '4', '-map', '0:v:0', '-progress', 'pipe:1', '-f', 'null', '-'],
                             capture_output=True, timeout=30)
    assert decoded.returncode == 0 and b'progress=end' in decoded.stdout, 'HDHomeRun remux decode failed: ' + decoded.stderr.decode() + '\n' + (directory / 'server.log').read_text()
    deadline = time.monotonic() + 10
    while ffmpeg_children(process) > before:
        assert time.monotonic() < deadline, 'The remux FFmpeg outlived its client'
        time.sleep(0.1)


def stop(process):
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=12)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)
            raise RuntimeError('Server did not stop gracefully')


def main():
    if not APP.exists():
        raise SystemExit('Build :server:installDist first')
    with tempfile.TemporaryDirectory(prefix='sofa-live-smoke-') as temporary:
        directory = Path(temporary)
        media = directory / 'media'
        media.mkdir()
        generated = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
                                    'testsrc2=size=320x180:rate=15', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
                                    '-t', '35', '-c:v', 'libx264', '-preset', 'ultrafast', '-g', '30', '-c:a', 'aac',
                                    '-hls_time', '2', '-hls_playlist_type', 'vod', str(media / 'live.m3u8')],
                                   capture_output=True, timeout=30)
        assert generated.returncode == 0, 'Could not generate fixture media'
        fixture = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Handler, directory=str(media)))
        threading.Thread(target=fixture.serve_forever, daemon=True).start()
        upstream = f'http://127.0.0.1:{fixture.server_port}'
        (media / 'logo.png').write_bytes(PNG)
        (media / 'feed.m3u').write_text(f'#EXTM3U\n#EXTINF:-1 tvg-id="fixture" tvg-chno="5" tvg-logo="{upstream}/logo.png" group-title="Test",Fixture TV\n{upstream}/live.m3u8\n')
        now = datetime.now(timezone.utc).replace(microsecond=0)
        start = now.strftime('%Y%m%d%H%M%S +0000')
        end = (now + timedelta(hours=1)).strftime('%Y%m%d%H%M%S +0000')
        (media / 'guide.xml').write_text(f'<tv><programme channel="fixture" start="{start}" stop="{end}"><title>Real fixture program</title></programme></tv>')
        sources = directory / 'sources.json'
        (media / 'ts-feed.m3u').write_text(f'#EXTM3U\n#EXTINF:-1 tvg-id="ts-fixture" tvg-chno="6",Fixture TS\n{upstream}/endless.ts\n')
        with (media / 'guide.xml.gz').open('wb') as compressed:
            compressed.write(gzip.compress((media / 'guide.xml').read_bytes()))
        # The provider's own guide plus an independent gzip guide; the lineup maps the fixture channel onto the gzip one.
        sources.write_text(json.dumps({
            'providers': [{'id': 'fixture', 'playlistUrl': upstream + '/feed.m3u', 'guideUrl': upstream + '/guide.xml', 'probe': True},
                          {'id': 'ts-fixture', 'playlistUrl': upstream + '/ts-feed.m3u', 'probe': False}],
            'guides': [{'id': 'gz', 'url': upstream + '/guide.xml.gz', 'refreshSeconds': 300}],
        }))
        data = directory / 'data'
        port = free_port()
        base = f'http://127.0.0.1:{port}'
        # The fixture serves from loopback, which the server refuses unless told it is a development fixture.
        env = dict(os.environ, SOFA_HOST='127.0.0.1', SOFA_PORT=str(port), SOFA_DATA_DIR=str(data), SOFA_SOURCES=str(sources), SOFA_API_TOKEN=TOKEN,
                   SOFA_ALLOW_LOOPBACK_UPSTREAMS='true', SOFA_OUTPUT_TOKEN=OUTPUT_TOKEN)
        process = None
        try:
            with (directory / 'server.log').open('w') as log:
                process = subprocess.Popen([str(APP)], env=env, stdout=log, stderr=log, start_new_session=True)
                deadline = time.monotonic() + 30
                while True:
                    if process.poll() is not None:
                        raise RuntimeError('Server exited during startup')
                    try:
                        providers = request(base, '/api/v1/providers')
                        guides = request(base, '/api/v1/admin/guides')
                        if all(p['lastSuccessAt'] for p in providers) and all(g['lastSuccessAt'] for g in guides):
                            break
                    except (URLError, TimeoutError):
                        pass
                    assert time.monotonic() < deadline, 'Import did not complete'
                    time.sleep(0.1)
                assert request(base, '/api/v1/channels')['channels'] == [], 'Feed channels are served before they enter the lineup'
                candidates = request(base, '/api/v1/admin/candidates?q=fixture')
                assert candidates['total'] == 2 and all(c['lineupEntryIds'] == [] for c in candidates['items'])
                fixture_candidate = next(c for c in candidates['items'] if c['providerId'] == 'fixture')
                entry = request(base, '/api/v1/admin/lineup', 'POST', {'channelId': fixture_candidate['channelId'], 'guide': {'guideId': 'gz', 'channelId': 'fixture'}})
                assert entry['guide'] == {'guideId': 'gz', 'channelId': 'fixture'} and entry['offerings'][0]['providerId'] == 'fixture'
                adopted = request(base, '/api/v1/admin/lineup/adopt', 'POST', {'providerId': 'ts-fixture'})
                assert adopted == {'added': 1, 'skipped': 0}, adopted
                deadline = time.monotonic() + 30
                while True:
                    catalog = request(base, '/api/v1/channels')
                    if len(catalog['channels']) == 2 and catalog['channels'][0]['guideStatus'] == 'available':
                        break
                    assert time.monotonic() < deadline, 'Lineup mapping did not pull guide programs: ' + json.dumps(catalog)
                    time.sleep(0.1)
                channel = catalog['channels'][0]
                assert channel['id'] == entry['id'] and channel['legacyGuideId'] == 'fixture'
                assert entry['logo'] == f'{upstream}/logo.png', entry
                guide = request(base, '/api/v1/guide/query', 'POST', {'channelIds': [channel['id']], 'from': now.isoformat(), 'to': (now + timedelta(hours=1)).isoformat()})
                assert guide['channels'][0]['programs'][0]['title'] == 'Real fixture program'
                session = request(base, '/api/v1/playback-sessions', 'POST', {'channelId': channel['id'], 'intent': 'preview', 'idempotencyKey': 'smoke'})
                manifest = request(base, session['descriptor']['url'])
                assert upstream not in manifest and '/media/sessions/' in manifest
                promoted = request(base, '/api/v1/playback-sessions/' + session['id'], 'PATCH', {'intent': 'watch'})
                assert promoted['descriptor'] == session['descriptor']
                concurrent = [session] + [request(base, '/api/v1/playback-sessions', 'POST', {'channelId': channel['id'], 'idempotencyKey': f'concurrent-{i}'}) for i in range(3)]
                decoders = [subprocess.Popen(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', base + item['descriptor']['url'], '-t', '8', '-map', '0:v:0', '-progress', 'pipe:1', '-f', 'null', '-'], stdout=subprocess.PIPE, stderr=subprocess.PIPE) for item in concurrent]
                try:
                    for decoder in decoders:
                        progress_out, progress_err = decoder.communicate(timeout=25)
                        assert decoder.returncode == 0 and b'progress=end' in progress_out, 'Concurrent relay decode failed'
                finally:
                    for decoder in decoders:
                        if decoder.poll() is None:
                            decoder.kill(); decoder.wait(timeout=5)
                for item in concurrent[1:]:
                    request(base, '/api/v1/playback-sessions/' + item['id'], 'DELETE')
                decoded = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', base + session['descriptor']['url'], '-t', '8', '-map', '0:v:0', '-progress', 'pipe:1', '-f', 'null', '-'], capture_output=True, timeout=25)
                assert decoded.returncode == 0 and b'progress=end' in decoded.stdout, 'Relay video decode failed: ' + decoded.stderr.decode() + '\n' + (directory / 'server.log').read_text()
                progress = dict(line.split('=', 1) for line in decoded.stdout.decode().splitlines() if '=' in line)
                assert int(progress['frame']) >= 100 and int(progress['out_time_us']) >= 7_000_000
                deadline = time.monotonic() + 20
                while True:
                    # Source status is keyed by feed channel; the lineup entry names its source ids.
                    state = next(source for source in request(base, '/api/v1/sources')
                                 if source['id'] == entry['offerings'][0]['id'])
                    if state['status'] == 'HEALTHY':
                        break
                    assert time.monotonic() < deadline, 'Decode probe did not qualify source'
                    time.sleep(0.1)
                request(base, '/api/v1/playback-sessions/' + session['id'], 'DELETE')
                try:
                    request(base, session['descriptor']['url'])
                    raise AssertionError('Released session still serves media')
                except HTTPError as error:
                    assert error.code == 410
                ts_channel = next(c for c in catalog['channels'] if c['name'] == 'Fixture TS')
                ts_session = request(base, '/api/v1/playback-sessions', 'POST', {'channelId': ts_channel['id'], 'idempotencyKey': 'ts-cancellation'})
                with urlopen(base + ts_session['descriptor']['url'], timeout=5) as stream:
                    assert stream.read(1024), 'TS stream has no bytes'
                    request(base, '/api/v1/playback-sessions/' + ts_session['id'], 'DELETE')
                    deadline = time.monotonic() + 5
                    while Handler.active_streams:
                        assert time.monotonic() < deadline, 'Released TS session retained its upstream'
                        time.sleep(0.1)
                check_player_outputs(base, upstream, channel, ts_channel, directory)
                check_hdhomerun(base, process, directory)
                check_lineup_edit(base, entry)
                identity = request(base, '/api/v1/bootstrap')['serverId']
                stop(process)
                process = subprocess.Popen([str(APP)], env=env, stdout=log, stderr=log, start_new_session=True)
                deadline = time.monotonic() + 30
                while True:
                    try:
                        restarted = request(base, '/api/v1/channels')
                        break
                    except (URLError, TimeoutError):
                        assert time.monotonic() < deadline
                        time.sleep(0.1)
                assert restarted['channels'][0]['id'] == channel['id'] and len(restarted['channels']) == 2, 'Lineup did not survive restart'
                assert request(base, '/api/v1/bootstrap')['serverId'] == identity
                assert restarted['channels'][0]['tags'] == ['Fixtures'], 'The lineup edit did not survive restart'
                stop(process)
        finally:
            if process is not None:
                stop(process)
            fixture.shutdown()
            fixture.server_close()
        print('Live smoke passed: feed + gzip guide import, lineup edits through the admin API, session promotion, four concurrent HLS video decoders, automatic decode qualification, active TS upstream cancellation, M3U/XMLTV player output with a redirect-following decode and a TS hang-up, a cached logo served from this server in every output, HDHomeRun discovery and a remuxed stream whose FFmpeg dies with its client, release, and a persistent lineup across restart.')


if __name__ == '__main__':
    main()
