#!/usr/bin/env python3
"""Run one native provider through a disposable server: import, adopt, resolve channels and decode a few with FFmpeg.

    python3 scripts/provider-smoke.py --service whale [--option zip=50309] [--resolve 3] [--decode 2] [--seconds 5]

Prints the channel count, which channels resolved to an HLS master through the relay and which decoded. Live
network access to the service is required; nothing is written outside a temporary directory.
"""
import argparse
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

APP = Path(__file__).resolve().parents[1] / 'server/build/install/server/bin/server'
TOKEN = 'provider-smoke-token-' * 2


def request(base, path, method='GET', body=None, timeout=20):
    data = None if body is None else json.dumps(body).encode()
    req = Request(base + path, data=data, method=method, headers={'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json'})
    with urlopen(req, timeout=timeout) as response:
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--service', required=True)
    parser.add_argument('--option', action='append', default=[], help='key=value provider option, repeatable')
    parser.add_argument('--resolve', type=int, default=3, help='channels to open through the relay')
    parser.add_argument('--decode', type=int, default=2, help='of those, channels to decode with FFmpeg')
    parser.add_argument('--seconds', type=int, default=5, help='seconds of video to decode per channel')
    parser.add_argument('--pick', default='', help='comma-separated channel names to try first')
    parser.add_argument('--keep-log', action='store_true', help='print the server log at the end')
    args = parser.parse_args()
    if not APP.exists():
        raise SystemExit('Build :server:installDist first')
    options = dict(item.split('=', 1) for item in args.option)
    with tempfile.TemporaryDirectory(prefix=f'sofa-provider-{args.service}-') as temporary:
        directory = Path(temporary)
        sources = directory / 'sources.json'
        sources.write_text(json.dumps({'providers': [{'id': args.service, 'type': 'native', 'service': args.service, 'options': options, 'probe': False}]}))
        port = free_port()
        base = f'http://127.0.0.1:{port}'
        env = dict(os.environ, SOFA_HOST='127.0.0.1', SOFA_PORT=str(port), SOFA_DATA_DIR=str(directory / 'data'), SOFA_SOURCES=str(sources), SOFA_API_TOKEN=TOKEN)
        log_path = directory / 'server.log'
        process = None
        try:
            with log_path.open('w') as log:
                process = subprocess.Popen([str(APP)], env=env, stdout=log, stderr=log, start_new_session=True)
            deadline = time.monotonic() + 120
            while True:
                if process.poll() is not None:
                    raise SystemExit('Server exited during startup:\n' + log_path.read_text())
                try:
                    providers = request(base, '/api/v1/providers')
                    status = providers[0]
                    if status['lastSuccessAt'] or status['errorCode']:
                        break
                except (URLError, TimeoutError, ConnectionError):
                    pass
                if time.monotonic() >= deadline:
                    raise SystemExit('Import did not finish:\n' + log_path.read_text())
                time.sleep(0.5)
            if status['errorCode']:
                raise SystemExit(f'Import failed: {status}\n' + log_path.read_text())
            print(f"{args.service}: {status['channels']} channels, {status['sources']} sources, skipped={status['skippedEntries']}")
            adopted = request(base, '/api/v1/admin/lineup/adopt', 'POST', {'providerId': args.service})
            print(f"adopted {adopted['added']} entries")
            channels = request(base, '/api/v1/channels')['channels']
            picks = [name.strip() for name in args.pick.split(',') if name.strip()]
            ordered = [c for name in picks for c in channels if c['name'] == name] + [c for c in channels if c['name'] not in picks]
            resolved = []
            failures = []
            for channel in ordered:
                if len(resolved) >= args.resolve:
                    break
                try:
                    session = request(base, '/api/v1/playback-sessions', 'POST', {'channelId': channel['id'], 'intent': 'watch', 'idempotencyKey': 'smoke-' + channel['id']})
                    master = request(base, session['descriptor']['url'], timeout=30)
                    ok = isinstance(master, str) and master.lstrip().startswith('#EXTM3U')
                    print(f"resolve {channel['displayNumber']} {channel['name']}: {'HLS master' if ok else 'not a master'} ({len(master)} bytes)")
                    if ok:
                        resolved.append((channel, session))
                    else:
                        failures.append(channel['name'])
                except HTTPError as error:
                    detail = error.read().decode()[:200]
                    print(f"resolve {channel['displayNumber']} {channel['name']}: HTTP {error.code} {detail}")
                    failures.append(channel['name'])
                except (URLError, TimeoutError) as error:
                    print(f"resolve {channel['displayNumber']} {channel['name']}: {error}")
                    failures.append(channel['name'])
            decoded = 0
            for channel, session in resolved[:args.decode]:
                url = base + session['descriptor']['url']
                run = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', url, '-t', str(args.seconds), '-map', '0:v:0', '-progress', 'pipe:1', '-f', 'null', '-'],
                                     capture_output=True, timeout=90 + args.seconds * 4)
                progress = dict(line.split('=', 1) for line in run.stdout.decode().splitlines() if '=' in line)
                frames = int(progress.get('frame', '0') or 0)
                micros = int(progress.get('out_time_us', '0') or 0)
                good = run.returncode == 0 and frames > 0 and micros >= (args.seconds - 1) * 1_000_000
                decoded += good
                tail = run.stderr.decode().strip().splitlines()[-1:] if not good else []
                print(f"decode {channel['name']}: {'ok' if good else 'FAILED'} frames={frames} seconds={micros / 1_000_000:.1f} {' '.join(tail)}")
            print(f"summary service={args.service} channels={status['channels']} resolved={len(resolved)}/{min(args.resolve, len(ordered))} decoded={decoded}/{min(args.decode, len(resolved))} failures={failures}")
            if args.keep_log:
                print(log_path.read_text())
        finally:
            if process is not None:
                stop(process)
    return 0


if __name__ == '__main__':
    sys.exit(main())
