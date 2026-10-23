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
# How many segments a live HLS playlist lists.
LIVE_WINDOW = 5


class Upstream(http.server.SimpleHTTPRequestHandler):
    """Serves the fixture files, and /endless/<n>.ts as a live MPEG-TS stream that repeats one clip until the reader goes,
    at the clip's own pace as a broadcast arrives (a whole clip at once overflows the server's buffer for a new reader),
    and /live/<n>.m3u8 as a live HLS playlist of the segments in live-<n>/. Every file allows any origin, so a web page
    can load the guide's images."""

    def do_GET(self):
        if self.path.startswith('/live/'):
            return self.live()
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

    def live(self):
        """/live/<n>.m3u8 as a live HLS playlist that loops the segments in live-<n>/ (each listed with its length in
        live-<n>/segments.json) as if they had been airing since the epoch, with a discontinuity at each loop."""
        folder = Path(self.directory) / f'live-{Path(self.path).stem}'
        if not (folder / 'segments.json').exists():
            return self.send_error(404)
        segments = json.loads((folder / 'segments.json').read_text())
        target = max(seconds for _, seconds in segments)
        now = int(time.time() / target)
        first = now - LIVE_WINDOW + 1
        lines = ['#EXTM3U', '#EXT-X-VERSION:3', f'#EXT-X-TARGETDURATION:{round(target + 0.5)}',
                 f'#EXT-X-MEDIA-SEQUENCE:{first}', f'#EXT-X-DISCONTINUITY-SEQUENCE:{first // len(segments)}']
        for number in range(first, now + 1):
            name, seconds = segments[number % len(segments)]
            if number % len(segments) == 0 and number != first:
                lines.append('#EXT-X-DISCONTINUITY')
            lines += [f'#EXTINF:{seconds:.3f},', f'/live-{Path(self.path).stem}/{name}']
        body = ('\n'.join(lines) + '\n').encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/vnd.apple.mpegurl')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-cache')
        self.end_headers()
        self.wfile.write(body)

    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        super().end_headers()

    def log_message(self, *args):
        pass


def request(base, path, method='GET', body=None, timeout=5):
    data = None if body is None else json.dumps(body).encode()
    req = Request(base + path, data=data, method=method,
                  headers={'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json'})
    with urlopen(req, timeout=timeout) as response:
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


def fixtures(media, upstream, library=None):
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
    programmes = []
    for i in range(CHANNELS):
        for slot in range(12):
            begin = start + timedelta(minutes=30 * slot)
            programmes.append(f'<programme channel="c{i}" start="{stamp(begin)}" stop="{stamp(begin + timedelta(minutes=30))}">'
                              f'<title>{NAMES[i]} show {slot + 1}</title><desc>A fixture programme.</desc></programme>')
    (media / 'guide.xml').write_text('<tv>' + ''.join(programmes) + '</tv>')


@contextlib.contextmanager
def serve(out, build=fixtures, channels=CHANNELS, libraries=(), **settings):
    """Starts the fixture channels and the server, with its log in <out>/server.log and any further `SOFA_` [settings],
    and yields the server's base URL once the lineup is adopted; stops both on the way out. [build] writes the channels'
    files, playlist and guide into the folder it is given and serves them from the address it is given, the [channels]
    the lineup then holds, and the server's media folder, which it fills for [libraries]. Each of those, a (name, kind,
    folder) triple, is added from that folder once the lineup is in, and scanned before this yields."""
    if not SERVER.exists():
        raise SystemExit('Build :server:installDist in backend/ first')
    with tempfile.TemporaryDirectory(prefix='sofa-fixture-') as temporary:
        directory = Path(temporary)
        media = directory / 'media'
        media.mkdir()
        fixture = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Upstream, directory=str(media)))
        threading.Thread(target=fixture.serve_forever, daemon=True).start()
        upstream = f'http://127.0.0.1:{fixture.server_port}'
        library = directory / 'library'
        library.mkdir()
        build(media, upstream, library)
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
                   SOFA_RECORDINGS_FREE_GIB='0', SOFA_MEDIA_DIR=str(library), **settings)
        server_log = (out / 'server.log').open('w')
        server = subprocess.Popen([str(SERVER)], env=env, stdout=server_log, stderr=server_log, start_new_session=True)
        try:
            wait_for('the fixture import', lambda: all(p['lastSuccessAt'] for p in request(base, '/api/v1/providers')), 60)
            request(base, '/api/v1/admin/lineup/adopt', 'POST', {'providerId': 'fixture'})
            wait_for('the lineup', lambda: len(request(base, '/api/v1/channels')['channels']) == channels, 30)
            for name, kind, folder in libraries:
                added = request(base, '/api/v1/admin/libraries', 'POST', {'name': name, 'kind': kind, 'path': str(library / folder)})
                request(base, f'/api/v1/admin/libraries/{added["id"]}/scan', 'POST', timeout=300)
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
