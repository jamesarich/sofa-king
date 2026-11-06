"""The updater's gates, run against fake docker, cosign and gh commands and a fake server on loopback.

The script needs GNU coreutils and flock-style paths, so these run on Linux only."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
from datetime import datetime, timedelta, timezone

SCRIPT = Path(__file__).resolve().parent.parent / 'deploy' / 'sofa-king-update.sh'

FAKE_DOCKER = r'''#!/usr/bin/env bash
echo "$*" >> "$CALLS"
case "$1 $2" in
  "pull "*) exit 0 ;;
  "image inspect")
    case "$*" in
      *RepoDigests*) echo "ghcr.io/jamesarich/sofa-king@sha256:abc" ;;
      *Labels*) case "$5" in sha256:new) echo newrev ;; *) echo oldrev ;; esac ;;
      *) echo sha256:new ;;
    esac ;;
  "inspect --format")
    case "$3" in
      "{{.Image}}") echo sha256:old ;;
      "{{.State.Running}}") echo true ;;
      "{{.State.Health.Status}}") echo healthy ;;
      *Env*) echo "SOFA_DATA_DIR=/config" ;;
      *Mounts*) printf '/config\t%s\n' "$CONFIG" ;;
    esac ;;
  "exec "*) exit 1 ;;
  "compose config") echo ghcr.io/jamesarich/sofa-king:main ;;
  *) exit 0 ;;
esac
'''


class FakeServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, recordings=None, channels=None, session_status=201, playlist='#EXTM3U\n#EXT-X-VERSION:3\n',
                 recordings_status=200):
        super().__init__(('127.0.0.1', 0), Handler)
        self.recordings_status = recordings_status
        self.recordings = recordings or []
        self.channels = channels if channels is not None else []
        self.session_status = session_status
        self.playlist = playlist
        self.requests = []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def respond(self, status, body, content_type='application/json'):
        data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def handle_any(self):
        if self.headers.get('Authorization') != 'Bearer api-secret':
            return self.respond(401, {})
        self.server.requests.append(f'{self.command} {self.path}')
        if self.command == 'POST':
            self.rfile.read(int(self.headers.get('Content-Length', 0)))
        if self.path == '/api/v1/recordings':
            self.respond(self.server.recordings_status, {'recordings': self.server.recordings})
        elif self.path == '/api/v1/channels':
            self.respond(200, {'channels': self.server.channels})
        elif self.path == '/api/v1/playback-sessions':
            session = {'id': 's1', 'descriptor': {'url': '/media/s1/index.m3u8'}}
            self.respond(self.server.session_status, session if self.server.session_status < 300 else {})
        elif self.path == '/media/s1/index.m3u8':
            self.respond(200, self.server.playlist, 'application/vnd.apple.mpegurl')
        elif self.command == 'DELETE':
            self.respond(204, '')
        else:
            self.respond(404, {})

    do_GET = do_POST = do_DELETE = handle_any


@unittest.skipUnless(sys.platform.startswith('linux'), 'the updater needs GNU tools')
class UpdateGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.compose_dir = root / 'compose'
        self.config = self.compose_dir / 'config'
        self.bin = root / 'bin'
        self.config.mkdir(parents=True)
        self.bin.mkdir()
        (self.compose_dir / 'compose.yaml').write_text('services: {}\n')
        (self.config / 'secrets.json').write_text(json.dumps({'apiToken': 'api-secret'}))
        database = sqlite3.connect(self.config / 'backend.sqlite')
        database.execute('create table t (a)')
        database.commit()
        database.close()
        self.calls = root / 'calls'
        self.calls.write_text('')
        self.shim('docker', FAKE_DOCKER)
        self.shim('flock', 'exit 0')
        self.server = None

    def tearDown(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()
        self.tmp.cleanup()

    def shim(self, name, body):
        path = self.bin / name
        path.write_text(body if body.startswith('#!') else f'#!/usr/bin/env bash\n{body}\n')
        path.chmod(0o755)

    def serve(self, **kwargs):
        self.server = FakeServer(**kwargs)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def run_updater(self):
        env = {
            'PATH': f'{self.bin}:/usr/bin:/bin',
            'HOME': self.tmp.name,
            'CALLS': str(self.calls),
            'CONFIG': str(self.config),
            'SOFA_UPDATE_DIR': str(self.compose_dir),
            'SOFA_UPDATE_LOCK_FILE': str(Path(self.tmp.name) / 'lock'),
            'SOFA_UPDATE_LOCAL_TAG': 'sofa-king-backend:local',
            'SOFA_UPDATE_HEALTH_SECONDS': '30',
            'SOFA_UPDATE_URL': f'http://127.0.0.1:{self.server.server_port}' if self.server else 'http://127.0.0.1:9',
        }
        result = subprocess.run([str(SCRIPT)], env=env, capture_output=True, text=True, timeout=120)
        status = json.loads((self.config / 'update-status.json').read_text())
        return result, status

    def swapped(self):
        return 'compose up' in self.calls.read_text()

    def recording(self, state, starts_in_seconds):
        starts = datetime.now(timezone.utc) + timedelta(seconds=starts_in_seconds)
        return {'title': 'News', 'state': state, 'startsAt': starts.strftime('%Y-%m-%dT%H:%M:%SZ'), 'startPaddingSeconds': 0}

    def test_failed_signature_aborts_the_update_and_is_reported(self):
        self.shim('cosign', 'exit 1')
        result, status = self.run_updater()
        self.assertEqual(status['outcome'], 'verify-failed')
        self.assertIn('signature', status['message'])
        self.assertFalse(self.swapped())
        self.assertEqual(result.returncode, 1)

    def test_failed_attestation_aborts_the_update(self):
        self.shim('cosign', 'exit 0')
        self.shim('gh', 'if [ "$1" = auth ]; then exit 0; fi; exit 1')
        result, status = self.run_updater()
        self.assertEqual(status['outcome'], 'verify-failed')
        self.assertIn('attestation', status['message'])
        self.assertFalse(self.swapped())

    def test_signature_is_checked_against_the_repository_workflow_identity(self):
        self.shim('cosign', 'echo "$*" >> "$CALLS"; exit 0')
        self.run_updater()
        call = next(line for line in self.calls.read_text().splitlines() if line.startswith('verify '))
        self.assertIn('sha256:abc', call)
        self.assertIn('jamesarich/sofa-king/\\.github/workflows/backend\\.yml', call)
        self.assertIn('https://token.actions.githubusercontent.com', call)

    def test_missing_verification_tools_are_skipped_and_the_update_goes_on(self):
        self.serve()
        result, status = self.run_updater()
        self.assertEqual(status['outcome'], 'updated')
        self.assertIn('cosign is not installed', result.stdout)
        self.assertTrue(self.swapped())

    def test_running_recording_defers_the_update(self):
        self.serve(recordings=[self.recording('recording', -60)])
        result, status = self.run_updater()
        self.assertEqual(status['outcome'], 'skipped')
        self.assertIn('is recording', status['message'])
        self.assertFalse(self.swapped())
        self.assertEqual(result.returncode, 0)

    def test_recording_starting_inside_the_health_window_defers_the_update(self):
        self.serve(recordings=[self.recording('scheduled', 20)])
        _, status = self.run_updater()
        self.assertEqual(status['outcome'], 'skipped')
        self.assertFalse(self.swapped())

    def test_recording_starting_after_the_health_window_does_not_defer_the_update(self):
        self.serve(recordings=[self.recording('scheduled', 3600), self.recording('completed', -7200)])
        _, status = self.run_updater()
        self.assertEqual(status['outcome'], 'updated')

    def test_server_without_recordings_does_not_block_the_update(self):
        self.serve(recordings_status=404)
        result, status = self.run_updater()
        self.assertEqual(status['outcome'], 'updated')
        self.assertIn('could not list recordings', result.stdout)

    def test_playback_smoke_opens_reads_and_releases_a_session(self):
        self.serve(channels=[{'id': 'off', 'playbackEligible': False}, {'id': 'c1', 'playbackEligible': True}])
        _, status = self.run_updater()
        self.assertEqual(status['outcome'], 'updated')
        self.assertEqual(self.server.requests[-3:], [
            'POST /api/v1/playback-sessions', 'GET /media/s1/index.m3u8', 'DELETE /api/v1/playback-sessions/s1'])

    def test_no_playable_channel_skips_the_smoke(self):
        self.serve(channels=[{'id': 'off', 'playbackEligible': False}])
        result, status = self.run_updater()
        self.assertEqual(status['outcome'], 'updated')
        self.assertIn('no channel can play yet', result.stdout)
        self.assertNotIn('POST /api/v1/playback-sessions', self.server.requests)

    def test_session_that_cannot_open_rolls_the_update_back(self):
        self.serve(channels=[{'id': 'c1', 'playbackEligible': True}], session_status=500)
        _, status = self.run_updater()
        self.assertEqual(status['outcome'], 'rolled-back')
        self.assertIn('playback check', status['message'])
        self.assertEqual(self.calls.read_text().count('compose up'), 2)

    def test_playlist_that_is_not_a_playlist_rolls_the_update_back(self):
        self.serve(channels=[{'id': 'c1', 'playbackEligible': True}], playlist='<html>')
        _, status = self.run_updater()
        self.assertEqual(status['outcome'], 'rolled-back')
        self.assertIn('playlist', status['message'])


if __name__ == '__main__':
    unittest.main()
