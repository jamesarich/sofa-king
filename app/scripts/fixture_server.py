#!/usr/bin/env python3
"""Run the server's installed distribution against fixture channels, for tests that drive an app on a device or
simulator: eight channels from one FFmpeg clip (the first as HLS, the rest as endless MPEG-TS), six hours of guide, and
the lineup adopted. Run directly, it serves until stopped and writes the server's address and token to
<out>/server.env, for `xcodebuild test` and the like; tv-monkey.py imports it.

Needs FFmpeg and the server's distribution (`./gradlew :server:installDist` in backend/).
"""
import argparse
import contextlib
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
from urllib.error import URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / 'backend/server/build/install/server/bin/server'
TOKEN = 'local-fixture-token-' * 3
CHANNELS = 8
NAMES = ['News Now', 'Sofa Sports', 'Movies', 'Kids', 'Local 4', 'Weather', 'Music', 'Classics']
# The fixture clip's length, and the MPEG-TS packets the endless channels send at a time.
CLIP_SECONDS = 10
PIECE = 188 * 64


class Upstream(http.server.SimpleHTTPRequestHandler):
    """Serves the fixture files, and /endless/<n>.ts as a live MPEG-TS stream that repeats one clip until the reader goes,
    at the clip's own pace as a broadcast arrives (a whole clip at once overflows the server's buffer for a new reader)."""

    def do_GET(self):
        if not self.path.startswith('/endless/'):
            return super().do_GET()
        clip = (Path(self.directory) / 'clip.ts').read_bytes()
        pieces = [clip[at:at + PIECE] for at in range(0, len(clip), PIECE)]
        every = CLIP_SECONDS / len(pieces)
        try:
            self.send_response(200)
            self.send_header('Content-Type', 'video/mp2t')
            self.end_headers()
            due = time.monotonic()
            while True:
                for piece in pieces:
                    self.wfile.write(piece)
                    self.wfile.flush()
                    due += every
                    time.sleep(max(0.0, due - time.monotonic()))
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *args):
        pass


def request(base, path, method='GET', body=None):
    data = None if body is None else json.dumps(body).encode()
    req = Request(base + path, data=data, method=method,
                  headers={'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json'})
    with urlopen(req, timeout=5) as response:
        content = response.read().decode()
        return json.loads(content) if content else None


def wait_for(what, check, seconds):
    deadline = time.monotonic() + seconds
    while True:
        try:
            result = check()
            if result:
                return result
        except (URLError, TimeoutError, ConnectionError):
            pass
        if time.monotonic() > deadline:
            raise SystemExit(f'Timed out waiting for {what}')
        time.sleep(0.5)


def fixtures(media, upstream):
    """A ten-second clip for the TS channels, the same clip as HLS for the first channel, an M3U and six hours of guide."""
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=25',
                    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', str(CLIP_SECONDS), '-c:v', 'libx264',
                    '-preset', 'ultrafast', '-g', '25', '-c:a', 'aac', '-f', 'mpegts', str(media / 'clip.ts')],
                   check=True, timeout=60)
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', str(media / 'clip.ts'), '-c', 'copy',
                    '-hls_time', '2', '-hls_playlist_type', 'vod', str(media / 'live.m3u8')], check=True, timeout=60)
    playlist = ['#EXTM3U']
    for i in range(CHANNELS):
        url = f'{upstream}/live.m3u8' if i == 0 else f'{upstream}/endless/{i}.ts'
        playlist += [f'#EXTINF:-1 tvg-id="c{i}" tvg-chno="{i + 2}" group-title="{"News" if i % 2 == 0 else "Sports"}",{NAMES[i]}', url]
    (media / 'feed.m3u').write_text('\n'.join(playlist) + '\n')
    start = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) - timedelta(hours=1)
    stamp = lambda t: t.strftime('%Y%m%d%H%M%S +0000')
    programs = []
    for i in range(CHANNELS):
        for slot in range(12):
            begin = start + timedelta(minutes=30 * slot)
            programs.append(f'<programme channel="c{i}" start="{stamp(begin)}" stop="{stamp(begin + timedelta(minutes=30))}">'
                              f'<title>{NAMES[i]} show {slot + 1}</title><desc>A fixture program.</desc></programme>')
    (media / 'guide.xml').write_text('<tv>' + ''.join(programs) + '</tv>')


@contextlib.contextmanager
def serve(out, **settings):
    """Starts the fixture channels and the server, with its log in <out>/server.log and any further `SOFA_` [settings],
    and yields the server's base URL once the lineup is adopted; stops both on the way out."""
    if not SERVER.exists():
        raise SystemExit('Build :server:installDist in backend/ first')
    with tempfile.TemporaryDirectory(prefix='sofa-fixture-') as temporary:
        directory = Path(temporary)
        media = directory / 'media'
        media.mkdir()
        fixture = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Upstream, directory=str(media)))
        threading.Thread(target=fixture.serve_forever, daemon=True).start()
        upstream = f'http://127.0.0.1:{fixture.server_port}'
        fixtures(media, upstream)
        sources = directory / 'sources.json'
        sources.write_text(json.dumps({'providers': [
            {'id': 'fixture', 'playlistUrl': upstream + '/feed.m3u', 'guideUrl': upstream + '/guide.xml', 'probe': False}]}))
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        base = f'http://127.0.0.1:{port}'
        # The fixture serves from loopback, which the server refuses unless told it is a development fixture. The DVR
        # keeps no free space, so a full CI disk still records.
        env = dict(os.environ, SOFA_HOST='127.0.0.1', SOFA_PORT=str(port), SOFA_DATA_DIR=str(directory / 'data'),
                   SOFA_SOURCES=str(sources), SOFA_API_TOKEN=TOKEN, SOFA_ALLOW_LOOPBACK_UPSTREAMS='true', SOFA_UPDATE_IMAGE='',
                   SOFA_RECORDINGS_FREE_GIB='0', **settings)
        server_log = (out / 'server.log').open('w')
        server = subprocess.Popen([str(SERVER)], env=env, stdout=server_log, stderr=server_log, start_new_session=True)
        try:
            wait_for('the fixture import', lambda: all(p['lastSuccessAt'] for p in request(base, '/api/v1/providers')), 60)
            request(base, '/api/v1/admin/lineup/adopt', 'POST', {'providerId': 'fixture'})
            wait_for('the lineup', lambda: len(request(base, '/api/v1/channels')['channels']) == CHANNELS, 30)
            yield base
        finally:
            if server.poll() is None:
                os.killpg(server.pid, signal.SIGTERM)
                server.wait(timeout=15)
            server_log.close()
            fixture.shutdown()


def stop(*_):
    """Stopping the script stops the server too."""
    raise SystemExit(0)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    ready = out / 'server.env'
    ready.unlink(missing_ok=True)
    signal.signal(signal.SIGTERM, stop)
    with serve(out) as base:
        ready.write_text(f'SOFAKING_SERVER={base}\nSOFAKING_TOKEN={TOKEN}\n')
        print(f'Serving {base}', flush=True)
        try:
            while True:
                time.sleep(3600)
        except KeyboardInterrupt:
            pass


if __name__ == '__main__':
    main()
