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
    parser.add_argument('--guide', help='XMLTV guide URL (plain or gzip); omit to skip the guide check')
    parser.add_argument('--decode-channel', help='Exact imported station name to decode for eight seconds')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='sofa-bridge-smoke-') as temporary:
        directory = Path(temporary)
        sources = directory / 'sources.json'
        provider = {'id': 'household-bridge', 'playlistUrl': args.playlist, 'probe': False}
        if args.guide:
            provider['guideUrl'] = args.guide
        sources.write_text(json.dumps([provider]))
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
                        status = smoke.request(base, '/api/v1/providers')[0]
                        assert status['errorCode'] is None, 'Household feed import failed'
                        guides = smoke.request(base, '/api/v1/admin/guides')
                        if status['lastSuccessAt'] and all(g['lastSuccessAt'] or g['errorCode'] for g in guides):
                            break
                    except (URLError, TimeoutError):
                        pass
                    assert time.monotonic() < deadline, 'Import timed out'
                    time.sleep(0.25)
                # The app only sees the lineup; adopt the whole feed into this disposable server.
                adopted = smoke.request(base, '/api/v1/admin/lineup/adopt', 'POST', {'providerId': 'household-bridge'})
                assert adopted['added'] > 0, 'Nothing to adopt'
                deadline = time.monotonic() + 300
                while True:
                    catalog = smoke.request(base, '/api/v1/channels')
                    candidates = [c for c in catalog['channels'] if c['guideStatus'] == 'available'][:50]
                    if candidates or not args.guide:
                        break
                    assert time.monotonic() < deadline, 'No real guide coverage'
                    time.sleep(1)
                now = datetime.now(timezone.utc)
                programs = 0
                if args.guide:
                    guide = smoke.request(base, '/api/v1/guide/query', 'POST', {'channelIds': [c['id'] for c in candidates], 'from': now.isoformat(), 'to': (now + timedelta(hours=6)).isoformat()})
                    programs = sum(len(c['programs']) for c in guide['channels'])
                    assert programs > 0, 'Bounded guide query has no programs'
                decoded = False
                if args.decode_channel:
                    channel = next(c for c in catalog['channels'] if c['name'] == args.decode_channel)
                    session = smoke.request(base, '/api/v1/playback-sessions', 'POST', {'channelId': channel['id'], 'idempotencyKey': 'bridge-read-only'})
                    result = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', base + session['descriptor']['url'], '-t', '8', '-map', '0:v:0', '-progress', 'pipe:1', '-f', 'null', '-'], capture_output=True, timeout=45)
                    assert result.returncode == 0 and b'progress=end' in result.stdout, 'Household stream did not decode through the backend'
                    smoke.request(base, '/api/v1/playback-sessions/' + session['id'], 'DELETE')
                    decoded = True
                print(f'Bridge smoke passed: {len(catalog["channels"])} channels, {len(candidates)} guide rows, {programs} real programs; video decode={decoded}. No provider probes or production writes.')
        finally:
            if process is not None:
                smoke.stop(process)


if __name__ == '__main__':
    main()
