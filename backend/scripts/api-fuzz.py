#!/usr/bin/env python3
"""Fuzz every route in the OpenAPI document against the installed application with Schemathesis.

Fails on any 5xx, a response that breaks its documented schema or content type, or a server that stops answering.
The live playback routes may answer their documented 503 SOURCE_UNAVAILABLE: a channel the fuzzer adds to the lineup
has a made-up stream with no usable source, and only some runs go on to play one. The multiview routes may answer
their documented 503 MULTIVIEW_UNAVAILABLE, since a runner has no GPU encoder.
Netty answers a request line over its 4096-byte limit with its own plain-text 400 before the application sees it, so
the run drops the schema and content-type failures of that one answer.
The routes that save configuration and restart the server are left out, since a restart ends the run, and so is the
admin event stream, which never ends. Schemathesis
runs in the temporary directory, so its example database goes with it.
"""
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import time
from urllib.error import URLError
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'server/build/install/server/bin/server'
SPEC = ROOT / 'server/src/main/resources/api/openapi.json'
TOKEN = 'local-fuzz-token-' + 'x' * 40
# PUT source-file, PUT tmdb-token and POST policy/starter save and restart (SetupService.requestRestart).
RESTARTS = r'/admin/(source-file|tmdb-token|policy/starter)$'
# The admin event stream stays open until the client leaves, so each example would wait out the run. Schemathesis
# keeps only the last --exclude-path-regex, so both go in one.
ENDLESS = r'/admin/events/stream$'
# Schemathesis reads schemathesis.toml from the directory it runs in.
CONFIG = '''[[operations]]
include-path-regex = '^/(api/v1/(playback|multiview)-sessions|media/(sessions|multiview)/|output/stream/|auto/)'
checks.not_a_server_error.expected-statuses = ["2xx", "3xx", "4xx", "503"]
'''
# Schemathesis loads these hooks from the file SCHEMATHESIS_HOOKS names.
HOOKS = '''import schemathesis


@schemathesis.hook
def filter_failure(context, failure, case, response):
    return not (response.status_code == 400 and response.content.startswith(b'An HTTP line is larger than'))
'''


def main():
    if not APP.exists():
        raise SystemExit('Build the installed distribution first: ./gradlew :server:installDist')
    examples = os.environ.get('SOFA_FUZZ_EXAMPLES', '100')
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    with tempfile.TemporaryDirectory(prefix='sofa-api-fuzz-') as temporary:
        directory = Path(temporary)
        (directory / 'schemathesis.toml').write_text(CONFIG)
        (directory / 'hooks.py').write_text(HOOKS)
        env = dict(os.environ, SOFA_HOST='127.0.0.1', SOFA_PORT=str(port), SOFA_DATA_DIR=str(directory), SOFA_API_TOKEN=TOKEN)
        log_path = directory / 'server.log'
        with log_path.open('w') as log:
            process = subprocess.Popen([str(APP)], env=env, stdout=log, stderr=log, start_new_session=True)
            try:
                deadline = time.monotonic() + 30
                while True:
                    if process.poll() is not None:
                        raise SystemExit(f'Server exited before ready: {process.returncode}\n' + log_path.read_text())
                    try:
                        with urlopen(base + '/readyz', timeout=2) as response:
                            if response.status == 200:
                                break
                    except (URLError, TimeoutError, ConnectionError):
                        pass
                    if time.monotonic() >= deadline:
                        raise SystemExit('Server never became ready')
                    time.sleep(0.1)
                result = subprocess.run([
                    'schemathesis', 'run', str(SPEC), '--url', base,
                    '--header', f'Authorization: Bearer {TOKEN}',
                    '--checks', 'not_a_server_error,response_schema_conformance,content_type_conformance',
                    '--max-examples', examples,
                    '--exclude-path-regex', f'{RESTARTS}|{ENDLESS}',
                ], cwd=directory, env=dict(os.environ, SCHEMATHESIS_HOOKS=str(directory / 'hooks.py')))
                if process.poll() is not None:
                    raise SystemExit(f'Server exited during the run: {process.returncode}\n' + log_path.read_text()[-4000:])
                if result.returncode != 0:
                    print(log_path.read_text()[-4000:], file=sys.stderr)
                    raise SystemExit(result.returncode)
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=12)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
    print('API fuzz passed: no server error, schema break or crash on any documented route.')


if __name__ == '__main__':
    main()
