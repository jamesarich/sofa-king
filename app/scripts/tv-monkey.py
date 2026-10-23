#!/usr/bin/env python3
"""Press random remote keys on the app in a running Android TV emulator, against the server's installed distribution
serving fixture channels; fail on a crash, an ANR, or a playback session left open after the app goes to the
background. StrictMode's findings in the debug build are counted and kept, not failed on.

Needs adb with one booted emulator, FFmpeg, the debug APK and the server's distribution (`./gradlew :server:installDist`
in backend/). Writes the logs, the key script and a screenshot to --out.
"""
import argparse
import functools
import http.server
import json
import os
from pathlib import Path
import random
import re
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from urllib.error import URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / 'backend/server/build/install/server/bin/server'
PACKAGE = 'dev.jamesrich.sofaking'
TOKEN = 'local-fixture-token-' * 3
CHANNELS = 8

# The remote's keys, weighted as AppDriver.REMOTE weighs them for the JVM fuzz; LongPress() is a held OK.
KEYS = [
    ('KEYCODE_DPAD_UP', 12), ('KEYCODE_DPAD_DOWN', 14), ('KEYCODE_DPAD_LEFT', 10), ('KEYCODE_DPAD_RIGHT', 12),
    ('KEYCODE_DPAD_CENTER', 8), ('KEYCODE_ENTER', 2), ('LongPress', 1), ('KEYCODE_BACK', 6), ('KEYCODE_MENU', 1),
    ('KEYCODE_GUIDE', 1), ('KEYCODE_INFO', 1), ('KEYCODE_CHANNEL_UP', 2), ('KEYCODE_CHANNEL_DOWN', 2),
    ('KEYCODE_MEDIA_PLAY_PAUSE', 1), ('KEYCODE_MEDIA_FAST_FORWARD', 1), ('KEYCODE_MEDIA_REWIND', 1),
    ('KEYCODE_CAPTIONS', 1), ('KEYCODE_5', 1),
]


class Upstream(http.server.SimpleHTTPRequestHandler):
    """Serves the fixture files, and /endless/<n>.ts as a live MPEG-TS stream that repeats one clip until the reader goes."""

    def do_GET(self):
        if not self.path.startswith('/endless/'):
            return super().do_GET()
        chunk = (Path(self.directory) / 'clip.ts').read_bytes()
        try:
            self.send_response(200)
            self.send_header('Content-Type', 'video/mp2t')
            self.end_headers()
            while True:
                self.wfile.write(chunk)
                self.wfile.flush()
                time.sleep(8)
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


def adb(*args, **kwargs):
    return subprocess.run(['adb', *args], check=True, capture_output=True, text=True, **kwargs).stdout


def fixtures(media, upstream):
    """A ten-second clip for the TS channels, the same clip as HLS for channel 2, an M3U and six hours of guide."""
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=25',
                    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', '10', '-c:v', 'libx264',
                    '-preset', 'ultrafast', '-g', '25', '-c:a', 'aac', '-f', 'mpegts', str(media / 'clip.ts')],
                   check=True, timeout=60)
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', str(media / 'clip.ts'), '-c', 'copy',
                    '-hls_time', '2', '-hls_playlist_type', 'vod', str(media / 'live.m3u8')], check=True, timeout=60)
    names = ['News Now', 'Sofa Sports', 'Movies', 'Kids', 'Local 4', 'Weather', 'Music', 'Classics']
    playlist = ['#EXTM3U']
    for i in range(CHANNELS):
        url = f'{upstream}/live.m3u8' if i == 0 else f'{upstream}/endless/{i}.ts'
        playlist += [f'#EXTINF:-1 tvg-id="c{i}" tvg-chno="{i + 2}" group-title="{"News" if i % 2 == 0 else "Sports"}",{names[i]}', url]
    (media / 'feed.m3u').write_text('\n'.join(playlist) + '\n')
    start = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) - timedelta(hours=1)
    stamp = lambda t: t.strftime('%Y%m%d%H%M%S +0000')
    programmes = []
    for i in range(CHANNELS):
        for slot in range(12):
            begin = start + timedelta(minutes=30 * slot)
            programmes.append(f'<programme channel="c{i}" start="{stamp(begin)}" stop="{stamp(begin + timedelta(minutes=30))}">'
                              f'<title>{names[i]} show {slot + 1}</title><desc>A fixture programme.</desc></programme>')
    (media / 'guide.xml').write_text('<tv>' + ''.join(programmes) + '</tv>')


def key_script(path, count, seed):
    """A monkey script of [count] weighted random keys, a quarter second apart."""
    rng = random.Random(seed)
    names, weights = zip(*KEYS)
    lines = ['type= raw events', f'count= {count}', 'speed= 1.0', 'start data >>']
    for name in rng.choices(names, weights, k=count):
        lines.append('LongPress()' if name == 'LongPress' else f'DispatchPress({name})')
        lines.append('UserWait(250)')
    path.write_text('\n'.join(lines) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--apk', required=True)
    parser.add_argument('--out', required=True)
    parser.add_argument('--keys', type=int, default=int(os.environ.get('SOFAKING_MONKEY_KEYS') or 1500))
    parser.add_argument('--seed', type=int, default=int(os.environ.get('SOFAKING_MONKEY_SEED') or time.time()))
    args = parser.parse_args()
    if not SERVER.exists():
        raise SystemExit('Build :server:installDist in backend/ first')
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    print(f'Seed {args.seed}, {args.keys} keys', flush=True)

    with tempfile.TemporaryDirectory(prefix='sofa-tv-monkey-') as temporary:
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
        # The emulator reaches the host's loopback as 10.0.2.2. The fixture serves from loopback, which the server
        # refuses unless told it is a development fixture.
        env = dict(os.environ, SOFA_HOST='127.0.0.1', SOFA_PORT=str(port), SOFA_DATA_DIR=str(directory / 'data'),
                   SOFA_SOURCES=str(sources), SOFA_API_TOKEN=TOKEN, SOFA_ALLOW_LOOPBACK_UPSTREAMS='true', SOFA_UPDATE_IMAGE='')
        server_log = (out / 'server.log').open('w')
        server = subprocess.Popen([str(SERVER)], env=env, stdout=server_log, stderr=server_log, start_new_session=True)
        logcat = None
        try:
            wait_for('the fixture import', lambda: all(p['lastSuccessAt'] for p in request(base, '/api/v1/providers')), 60)
            request(base, '/api/v1/admin/lineup/adopt', 'POST', {'providerId': 'fixture'})
            wait_for('the lineup', lambda: len(request(base, '/api/v1/channels')['channels']) == CHANNELS, 30)
            code = request(base, '/api/v1/admin/pairing-codes', 'POST')['code']

            adb('install', '-r', '-g', args.apk, timeout=300)
            adb('logcat', '-c')
            logcat = subprocess.Popen(['adb', 'logcat', '-v', 'threadtime'], stdout=(out / 'logcat.txt').open('w'),
                                      stderr=subprocess.STDOUT)
            link = f'sofaking://pair?server=http://10.0.2.2:{port}&code={code}'
            adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d', f"'{link}'", PACKAGE, timeout=120)
            wait_for('the app to pair', lambda: request(base, '/api/v1/admin/viewers'), 60)
            # The guide loads and its preview opens a session once the lineup is in.
            wait_for('the guide preview', lambda: request(base, '/api/v1/admin/sessions'), 60)

            script = out / 'remote-keys.txt'
            key_script(script, args.keys, args.seed)
            adb('push', str(script), '/data/local/tmp/remote-keys.txt')
            monkey = subprocess.run(['adb', 'shell', 'monkey', '-p', PACKAGE, '-f', '/data/local/tmp/remote-keys.txt',
                                     '-v', '-v', '1'], capture_output=True, text=True, timeout=args.keys * 3 + 300)
            (out / 'monkey.txt').write_text(monkey.stdout + monkey.stderr)
            subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=(out / 'screen.png').open('wb'), timeout=30)
            alive = bool(subprocess.run(['adb', 'shell', 'pidof', PACKAGE], capture_output=True, text=True).stdout.strip())

            # Behind another app, the player stops and releases its session.
            adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
            try:
                wait_for('the app to release its sessions',
                         lambda: not [s for s in request(base, '/api/v1/admin/sessions') if not s['external']], 20)
                sessions_left = []
            except SystemExit:
                sessions_left = request(base, '/api/v1/admin/sessions')
        finally:
            if logcat:
                time.sleep(2)
                logcat.terminate()
            if server.poll() is None:
                os.killpg(server.pid, signal.SIGTERM)
                server.wait(timeout=15)
            server_log.close()

    log = (out / 'logcat.txt').read_text(errors='replace')
    monkey_out = (out / 'monkey.txt').read_text(errors='replace')
    failures = []
    if '// CRASH' in monkey_out or re.search(r'FATAL EXCEPTION.*\n.*Process: ' + re.escape(PACKAGE), log):
        failures.append('the app crashed')
    if '// NOT RESPONDING' in monkey_out or f'ANR in {PACKAGE}' in log:
        failures.append('the app stopped responding (ANR)')
    if 'Monkey finished' not in monkey_out and not failures:
        failures.append('the monkey did not finish')
    if not alive and not failures:
        failures.append('the app was not running after the keys')
    if sessions_left:
        failures.append(f'{len(sessions_left)} playback session(s) still open with the app in the background')
    strict = [line for line in log.splitlines() if ' StrictMode' in line and 'policy violation' in line]
    (out / 'strictmode.txt').write_text('\n'.join(strict) + '\n')

    summary = [f'## TV monkey: seed {args.seed}, {args.keys} keys', '',
               f'- Result: {"; ".join(failures) if failures else "no crash, ANR or session left open"}',
               f'- StrictMode violations logged: {len(strict)} (strictmode.txt in the artifact)']
    print('\n'.join(summary))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as step:
            step.write('\n'.join(summary) + '\n')
    if failures:
        print(f'Replay with SOFAKING_MONKEY_SEED={args.seed} SOFAKING_MONKEY_KEYS={args.keys}', file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
