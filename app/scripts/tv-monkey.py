#!/usr/bin/env python3
"""Press random remote keys on the app in a running Android TV emulator, against the server's installed distribution
serving fixture channels; fail on a crash, an ANR, or a playback session left open after the app goes to the
background. StrictMode's findings in the debug build are counted and kept, not failed on.

Needs adb with one booted emulator, the debug APK, and what fixture_server.py needs: FFmpeg and the server's distribution
(`./gradlew :server:installDist` in backend/). Writes the logs, the key script and a screenshot to --out.
"""
import argparse
import os
from pathlib import Path
import random
import re
import subprocess
import sys
import time

from fixture_server import request, serve, wait_for

PACKAGE = 'dev.jamesrich.sofaking'
# Back on the Live tab leaves the app, as it should; the keys go in short scripts, and the app comes back between them.
CHUNK = 25
ACTIVITY = PACKAGE + '/.MainActivity'

# The remote's keys, weighted as AppDriver.REMOTE weighs them for the JVM fuzz; LongPress() is a held OK.
KEYS = [
    ('KEYCODE_DPAD_UP', 12), ('KEYCODE_DPAD_DOWN', 14), ('KEYCODE_DPAD_LEFT', 10), ('KEYCODE_DPAD_RIGHT', 12),
    ('KEYCODE_DPAD_CENTER', 8), ('KEYCODE_ENTER', 2), ('LongPress', 1), ('KEYCODE_BACK', 6), ('KEYCODE_MENU', 1),
    ('KEYCODE_GUIDE', 1), ('KEYCODE_INFO', 1), ('KEYCODE_CHANNEL_UP', 2), ('KEYCODE_CHANNEL_DOWN', 2),
    ('KEYCODE_MEDIA_PLAY_PAUSE', 1), ('KEYCODE_MEDIA_FAST_FORWARD', 1), ('KEYCODE_MEDIA_REWIND', 1),
    ('KEYCODE_CAPTIONS', 1), ('KEYCODE_5', 1),
]


def adb(*args, **kwargs):
    return subprocess.run(['adb', *args], check=True, capture_output=True, text=True, **kwargs).stdout


def key_scripts(directory, count, seed):
    """[count] weighted random keys a quarter second apart, as monkey scripts of [CHUNK] keys each, in order."""
    rng = random.Random(seed)
    names, weights = zip(*KEYS)
    keys = rng.choices(names, weights, k=count)
    directory.mkdir(parents=True, exist_ok=True)
    scripts = []
    for start in range(0, count, CHUNK):
        lines = ['type= raw events', f'count= {min(CHUNK, count - start)}', 'speed= 1.0', 'start data >>']
        for name in keys[start:start + CHUNK]:
            lines.append('LongPress()' if name == 'LongPress' else f'DispatchPress({name})')
            lines.append('UserWait(250)')
        script = directory / f'{len(scripts):03d}.txt'
        script.write_text('\n'.join(lines) + '\n')
        scripts.append(script)
    return scripts


def in_front():
    """Whether the app's activity is the resumed one."""
    activities = subprocess.run(['adb', 'shell', 'dumpsys', 'activity', 'activities'], capture_output=True, text=True).stdout
    return any(PACKAGE + '/' in line for line in activities.splitlines() if 'ResumedActivity' in line)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--apk', required=True)
    parser.add_argument('--out', required=True)
    parser.add_argument('--keys', type=int, default=int(os.environ.get('SOFAKING_MONKEY_KEYS') or 1500))
    parser.add_argument('--seed', type=int, default=int(os.environ.get('SOFAKING_MONKEY_SEED') or time.time()))
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    print(f'Seed {args.seed}, {args.keys} keys', flush=True)

    with serve(out) as base:
        logcat = None
        try:
            code = request(base, '/api/v1/admin/pairing-codes', 'POST')['code']
            port = base.rsplit(':', 1)[1]

            adb('install', '-r', '-g', args.apk, timeout=300)
            adb('logcat', '-c')
            logcat = subprocess.Popen(['adb', 'logcat', '-v', 'threadtime'], stdout=(out / 'logcat.txt').open('w'),
                                      stderr=subprocess.STDOUT)
            link = f'sofaking://pair?server=http://10.0.2.2:{port}&code={code}'
            adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d', f"'{link}'", PACKAGE, timeout=120)
            wait_for('the app to pair', lambda: request(base, '/api/v1/admin/viewers'), 60)
            # The guide loads and its preview opens a session once the lineup is in.
            wait_for('the guide preview', lambda: request(base, '/api/v1/admin/sessions'), 60)

            scripts = key_scripts(out / 'remote-keys', args.keys, args.seed)
            adb('push', str(out / 'remote-keys'), '/data/local/tmp/')
            relaunches = 0
            unfinished = False
            with (out / 'monkey.txt').open('w') as monkey_log:
                for script in scripts:
                    if not in_front():
                        relaunches += 1
                        adb('shell', 'am', 'start', '-W', '-n', ACTIVITY, timeout=120)
                    monkey = subprocess.run(['adb', 'shell', 'monkey', '-p', PACKAGE, '-f', f'/data/local/tmp/remote-keys/{script.name}',
                                             '-v', '-v', '1'], capture_output=True, text=True, timeout=CHUNK * 3 + 120)
                    monkey_log.write(f'=== {script.name}\n{monkey.stdout}{monkey.stderr}')
                    if '// Monkey finished' not in monkey.stdout:
                        unfinished = True
                        break
            subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=(out / 'screen.png').open('wb'), timeout=30)
            alive = bool(subprocess.run(['adb', 'shell', 'pidof', PACKAGE], capture_output=True, text=True).stdout.strip())

            # The app in front for the last check, as after a relaunch its guide opens a preview again.
            if alive and not in_front():
                adb('shell', 'am', 'start', '-W', '-n', ACTIVITY, timeout=120)
            time.sleep(10)

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

    log = (out / 'logcat.txt').read_text(errors='replace')
    monkey_out = (out / 'monkey.txt').read_text(errors='replace')
    failures = []
    if '// CRASH' in monkey_out or re.search(r'FATAL EXCEPTION.*\n.*Process: ' + re.escape(PACKAGE), log):
        failures.append('the app crashed')
    if '// NOT RESPONDING' in monkey_out or f'ANR in {PACKAGE}' in log:
        failures.append('the app stopped responding (ANR)')
    if unfinished and not failures:
        failures.append('the monkey did not finish')
    if not alive and not failures:
        failures.append('the app was not running after the keys')
    if sessions_left:
        failures.append(f'{len(sessions_left)} playback session(s) still open with the app in the background')
    strict = [line for line in log.splitlines() if ' StrictMode' in line and 'policy violation' in line]
    (out / 'strictmode.txt').write_text('\n'.join(strict) + '\n')
    # Tunes that failed in the player, and readers the relay dropped: a stream that never plays fails nothing, so count them.
    playback_errors = log.count('ExoPlaybackException')
    dropped = (out / 'server.log').read_text(errors='replace').count('relay dropped slow readers')

    summary = [f'## TV monkey: seed {args.seed}, {args.keys} keys', '',
               f'- Result: {"; ".join(failures) if failures else "no crash, ANR or session left open"}',
               f'- The app was brought back {relaunches} times after a key left it',
               f'- StrictMode violations logged: {len(strict)} (strictmode.txt in the artifact)',
               f'- Player errors: {playback_errors}; readers the relay dropped as slow: {dropped}']
    print('\n'.join(summary))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as step:
            step.write('\n'.join(summary) + '\n')
    if failures:
        print(f'Replay with SOFAKING_MONKEY_SEED={args.seed} SOFAKING_MONKEY_KEYS={args.keys}', file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
