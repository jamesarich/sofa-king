#!/usr/bin/env python3
"""Read-only acceptance of configured household feeds in a disposable backend."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from datetime import datetime, timedelta, timezone
from urllib.error import URLError

from importlib.util import module_from_spec, spec_from_file_location

spec = spec_from_file_location('live_smoke', Path(__file__).with_name('live-smoke.py'))
smoke = module_from_spec(spec)
spec.loader.exec_module(smoke)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--playlist', required=True)
    parser.add_argument('--guide', required=True)
    parser.add_argument('--decode-channel', help='Exact imported station name to decode for eight seconds')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='sofa-bridge-smoke-') as temporary:
        directory = Path(temporary)
        sources = directory / 'sources.json'
        sources.write_text(json.dumps([{'id': 'household-bridge', 'playlistUrl': args.playlist, 'guideUrl': args.guide, 'probe': False}]))
        base = f'http://127.0.0.1:{smoke.free_port()}'
        env = dict(os.environ, SOFA_HOST='127.0.0.1', SOFA_PORT=base.rsplit(':', 1)[1], SOFA_DATA_DIR=str(directory / 'data'), SOFA_SOURCES=str(sources), SOFA_API_TOKEN=smoke.TOKEN)
        process = None
        try:
            with (directory / 'server.log').open('w') as log:
                process = subprocess.Popen([str(smoke.APP)], env=env, stdout=log, stderr=log, start_new_session=True)
                deadline = time.monotonic() + 90
                while True:
                    assert process.poll() is None, 'Backend exited'
                    try:
                        catalog = smoke.request(base, '/api/v1/channels')
                        if catalog['channels']:
                            break
                        status = smoke.request(base, '/api/v1/providers')[0]
                        assert status['errorCode'] is None, 'Household feed import failed'
                    except (URLError, TimeoutError):
                        pass
                    assert time.monotonic() < deadline, 'Import timed out'
                    time.sleep(0.25)
                now = datetime.now(timezone.utc)
                candidates = [c for c in catalog['channels'] if c['guideStatus'] == 'available'][:50]
                assert candidates, 'No real guide coverage'
                guide = smoke.request(base, '/api/v1/guide/query', 'POST', {'channelIds': [c['id'] for c in candidates], 'from': now.isoformat(), 'to': (now + timedelta(hours=6)).isoformat()})
                programmes = sum(len(c['programmes']) for c in guide['channels'])
                assert programmes > 0, 'Bounded guide query has no programmes'
                decoded = False
                if args.decode_channel:
                    channel = next(c for c in catalog['channels'] if c['name'] == args.decode_channel)
                    session = smoke.request(base, '/api/v1/playback-sessions', 'POST', {'channelId': channel['id'], 'idempotencyKey': 'bridge-read-only'})
                    result = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', base + session['descriptor']['url'], '-t', '8', '-map', '0:v:0', '-progress', 'pipe:1', '-f', 'null', '-'], capture_output=True, timeout=45)
                    assert result.returncode == 0 and b'progress=end' in result.stdout, 'Household stream did not decode through the backend'
                    smoke.request(base, '/api/v1/playback-sessions/' + session['id'], 'DELETE')
                    decoded = True
                print(f'Bridge smoke passed: {len(catalog["channels"])} channels, {len(candidates)} guide rows, {programmes} real programmes; video decode={decoded}. No provider probes or production writes.')
        finally:
            if process is not None:
                smoke.stop(process)


if __name__ == '__main__':
    main()
