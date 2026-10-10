#!/usr/bin/env python3
"""Import fixture feeds, decode video through the relay, verify probe health and restart."""
import functools
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
        (media / 'feed.m3u').write_text(f'#EXTM3U\n#EXTINF:-1 tvg-id="fixture" tvg-chno="5" group-title="Test",Fixture TV\n{upstream}/live.m3u8\n')
        now = datetime.now(timezone.utc).replace(microsecond=0)
        start = now.strftime('%Y%m%d%H%M%S +0000')
        end = (now + timedelta(hours=1)).strftime('%Y%m%d%H%M%S +0000')
        (media / 'guide.xml').write_text(f'<tv><programme channel="fixture" start="{start}" stop="{end}"><title>Real fixture programme</title></programme></tv>')
        sources = directory / 'sources.json'
        (media / 'ts-feed.m3u').write_text(f'#EXTM3U\n#EXTINF:-1 tvg-id="ts-fixture" tvg-chno="6",Fixture TS\n{upstream}/endless.ts\n')
        sources.write_text(json.dumps([{'id': 'fixture', 'playlistUrl': upstream + '/feed.m3u', 'guideUrl': upstream + '/guide.xml', 'probe': True}, {'id': 'ts-fixture', 'playlistUrl': upstream + '/ts-feed.m3u', 'probe': False}]))
        data = directory / 'data'
        port = free_port()
        base = f'http://127.0.0.1:{port}'
        env = dict(os.environ, SOFA_HOST='127.0.0.1', SOFA_PORT=str(port), SOFA_DATA_DIR=str(data), SOFA_SOURCES=str(sources), SOFA_API_TOKEN=TOKEN)
        process = None
        try:
            with (directory / 'server.log').open('w') as log:
                process = subprocess.Popen([str(APP)], env=env, stdout=log, stderr=log, start_new_session=True)
                deadline = time.monotonic() + 30
                while True:
                    if process.poll() is not None:
                        raise RuntimeError('Server exited during startup')
                    try:
                        catalog = request(base, '/api/v1/channels')
                        if len(catalog['channels']) == 2:
                            break
                    except (URLError, TimeoutError):
                        pass
                    assert time.monotonic() < deadline, 'Import did not complete'
                    time.sleep(0.1)
                channel = catalog['channels'][0]
                guide = request(base, '/api/v1/guide/query', 'POST', {'channelIds': [channel['id']], 'from': now.isoformat(), 'to': (now + timedelta(hours=1)).isoformat()})
                assert guide['channels'][0]['programmes'][0]['title'] == 'Real fixture programme'
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
                    state = next(source for source in request(base, '/api/v1/sources')
                                 if source['channelId'] == channel['id'])
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
                assert restarted['channels'][0]['id'] == channel['id']
                assert request(base, '/api/v1/bootstrap')['serverId'] == identity
                stop(process)
        finally:
            if process is not None:
                stop(process)
            fixture.shutdown()
            fixture.server_close()
        print('Live smoke passed: feed + guide import, session promotion, four concurrent HLS video decoders, automatic decode qualification, active TS upstream cancellation, release and persistent restart.')


if __name__ == '__main__':
    main()
