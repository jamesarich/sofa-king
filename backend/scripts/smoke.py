#!/usr/bin/env python3
"""Verify the installed application over real HTTP, including SIGTERM and restart."""
import json
import os
from pathlib import Path
import signal
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

APP = Path(__file__).resolve().parents[1] / 'server/build/install/server/bin/server'
MIGRATIONS = Path(__file__).resolve().parents[1] / 'server/src/main/resources/db'
SCHEMA_VERSIONS = sorted((int(path.name[1:4]),) for path in MIGRATIONS.glob('V[0-9][0-9][0-9]__*.sql'))


def request(base, route):
    try:
        response = urlopen(base + route, timeout=2)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.read().decode()


def start_and_check(directory, iteration):
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    env = dict(os.environ, SOFA_HOST='127.0.0.1', SOFA_PORT=str(port), SOFA_DATA_DIR=str(directory))
    with (directory / f'run-{iteration}.log').open('w') as log:
        process = subprocess.Popen([str(APP)], env=env, stdout=log, stderr=log, start_new_session=True)
        try:
            deadline = time.monotonic() + 30
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f'Server exited before ready: {process.returncode}\n' + (directory / f'run-{iteration}.log').read_text())
                try:
                    status, _ = request(base, '/readyz')
                    if status == 200:
                        break
                except (URLError, TimeoutError, ConnectionError):
                    pass
                if time.monotonic() >= deadline:
                    raise RuntimeError('Server never became ready')
                time.sleep(0.1)
            assert request(base, '/healthz') == (200, 'ok')
            status, body = request(base, '/api/v1/bootstrap')
            assert status == 200
            bootstrap = json.loads(body)
            assert bootstrap['apiVersion'] == 1
            assert bootstrap['capabilities'] == {'catalog': True, 'guide': True, 'playback': True, 'events': False, 'viewers': True, 'library': True, 'recordings': True, 'watchlist': True, 'onLater': True, 'household': True}
            status, body = request(base, '/api/v1/missing')
            assert status == 404 and json.loads(body)['code'] == 'RESOURCE_NOT_FOUND'
            return bootstrap['serverId']
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
                    raise RuntimeError('Server failed to stop gracefully')
            if sys.exc_info()[0] is None and process.returncode not in (0, -signal.SIGTERM, 128 + signal.SIGTERM):
                raise RuntimeError(f'Unexpected server exit: {process.returncode}')


def main():
    if not APP.exists():
        raise SystemExit('Build the installed distribution first: ./gradlew :server:installDist')
    with tempfile.TemporaryDirectory(prefix='sofa-runtime-smoke-') as temporary:
        directory = Path(temporary)
        first = start_and_check(directory, 1)
        second = start_and_check(directory, 2)
        assert first == second, 'Server identity changed across restart'
        with sqlite3.connect(directory / 'backend.sqlite') as connection:
            assert connection.execute('PRAGMA journal_mode').fetchone()[0] == 'wal'
            assert connection.execute('SELECT version FROM schema_version ORDER BY version').fetchall() == SCHEMA_VERSIONS
            assert connection.execute('SELECT COUNT(*) FROM lineup_entry').fetchone() == (0,)
            assert connection.execute('SELECT COUNT(*) FROM lineup_entry').fetchone() == (0,)
        for iteration in (1, 2):
            log = (directory / f'run-{iteration}.log').read_text()
            steps = [log.find(step) for step in ('shutdown: server stopped', 'shutdown: runtime closed', 'shutdown: store closed')]
            assert -1 not in steps and steps == sorted(steps), f'SIGTERM did not close server, runtime and store in order:\n{log}'
        print('Runtime smoke passed: real HTTP, truthful capabilities, SQLite WAL/migrations, ordered SIGTERM shutdown and persistent identity across restart.')


if __name__ == '__main__':
    main()
