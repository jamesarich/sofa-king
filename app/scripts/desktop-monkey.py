#!/usr/bin/env python3
"""Press random keys on the desktop app's distribution in a virtual X display, against the server's installed
distribution serving fixture channels, with libVLC playing them; fail when the app exits, logs an uncaught exception or
an unhandled failure, or shows a blank screen, or when closing its window leaves the app running or a session open.

Needs Xvfb, xdotool, ImageMagick's import, python-xlib, libVLC (`vlc-plugin-base`), FFmpeg, the server's distribution
(`./gradlew :server:installDist` in backend/) and the desktop app (`./gradlew :ui:createDistributable`). Writes the
app's log, the server log, the keys and the last screen to --out.

With --break-check the app first opens the first channel with During ads on Logo (muted), against a server whose
breaks come every minute, and fails unless the break screen (the app's `break screen up` and `break screen down` log
lines) comes up and clears when the break's mark says, and Back shows the picture during the next break.
"""
import argparse
import math
import os
from pathlib import Path
import random
import signal
import subprocess
import sys
import tempfile
import time

from fixture_server import BREAK_EVERY, BREAK_SECONDS, ROOT, TOKEN, request, serve, wait_for

APP = ROOT / 'app/ui/build/compose/binaries/main/app/Sofa King/bin/Sofa King'
# The keyboard's remote keys, weighted as AppDriver.REMOTE weighs the remote's; a held Return opens a cell's details.
KEYS = [
    ('Up', 12), ('Down', 14), ('Left', 10), ('Right', 12), ('Return', 10), ('held Return', 1), ('Escape', 8),
    ('Menu', 1), ('Prior', 2), ('Next', 2), ('space', 1),
]
# Seconds between the break check's breaks: the one the check waits for starts within this of the app opening the channel.
MARK_EVERY = 60
# The desktop places the picture this far behind the newest segment (VlcBreakMarks), so the screen comes and goes this
# long after the mark's start and end. MARGIN is what a slow runner may add: the playlist read, the poll and the log.
PLACEMENT = 15
MARGIN = 10
# A screenshot this small is one color: the app drew nothing.
BLANK_BYTES = 6000
# What the app's log shows for a failure: an exception nothing caught, a coroutine's (Failures.kt) or a JVM crash.
FAILURES = ('Exception in thread', 'unhandled failure', 'A fatal error has been detected')


def settings_file(data, base, channel=None):
    """The DataStore preferences file of an app paired with [base]: a PreferenceMap of strings, in protobuf. With a
    [channel] id the app opens that channel full screen at start, with During ads on Logo (muted)."""
    def field(number, payload):
        return bytes([number << 3 | 2]) + varint(len(payload)) + payload

    def entry(key, value):
        return field(1, field(1, key.encode()) + field(2, field(5, value.encode())))
    path = data / 'sofa-king/datastore/settings.preferences_pb'
    path.parent.mkdir(parents=True)
    chosen = b'' if channel is None else (
        entry('startup', 'CHANNEL') + entry('startup_channel', channel) + entry('live_breaks', 'SCREEN_MUTED'))
    path.write_bytes(entry('backend_url', base) + entry('api_token', TOKEN) + chosen)


def varint(value):
    out = b''
    while value > 0x7F:
        out += bytes([value & 0x7F | 0x80])
        value >>= 7
    return out + bytes([value])


def close_window(display, window):
    """Sends [window] WM_DELETE_WINDOW, which a window manager's close button sends; Xvfb runs no window manager."""
    from Xlib import X, display as xdisplay, protocol
    connection = xdisplay.Display(display)
    target = connection.create_resource_object('window', int(window))
    delete = protocol.event.ClientMessage(window=target, client_type=connection.intern_atom('WM_PROTOCOLS'),
                                          data=(32, [connection.intern_atom('WM_DELETE_WINDOW'), X.CurrentTime, 0, 0, 0]))
    target.send_event(delete, event_mask=X.NoEventMask)
    connection.flush()
    connection.close()


def failures(log):
    return [line.strip() for line in log.read_text(errors='replace').splitlines() if any(f in line for f in FAILURES)]


class Screen:
    """The break screen's edges as the app logs them, each with the epoch second this script first read its line."""
    UP, DOWN = 'break screen up', 'break screen down'

    def __init__(self, log):
        self.log, self.read, self.edges = log, 0, []

    def poll(self):
        with self.log.open('rb') as file:
            file.seek(self.read)
            chunk = file.read()
        complete = chunk[:chunk.rfind(b'\n') + 1]
        self.read += len(complete)
        for line in complete.decode(errors='replace').splitlines():
            for edge in (self.UP, self.DOWN):
                if edge in line:
                    self.edges.append((time.time(), edge))

    def next(self, edge, after, until, since=0):
        """The time of the first [edge] logged at or after the [after]th edge and the epoch second [since], or None by
        the epoch second [until]."""
        while True:
            self.poll()
            for at, kind in self.edges[after:]:
                if kind == edge and at >= since:
                    return at
            if time.time() > until:
                return None
            time.sleep(0.25)


def check_breaks(screen, press, picture):
    """Waits for a break the app is tuned in for and checks when its screen comes and goes, then that Back during the next
    one shows the picture; returns what to report and what failed. [press] sends a key, [picture] saves a screenshot."""
    errors = []
    now = time.time()
    first = math.ceil((now + 10) / MARK_EVERY) * MARK_EVERY
    seen = len(screen.edges)
    up = screen.next(Screen.UP, seen, first + PLACEMENT + MARGIN, since=first)
    if up is None:
        errors.append(f'no break screen by {PLACEMENT + MARGIN} s after the mark at {first}')
        return f'no break screen for the mark at {first}', errors
    picture('break-up.png')
    seen = len(screen.edges)
    if up < first + PLACEMENT - MARGIN:
        errors.append(f'the break screen came up {first - up + PLACEMENT:.0f} s before the picture reached the mark')
    down = screen.next(Screen.DOWN, seen, first + BREAK_SECONDS + PLACEMENT + MARGIN)
    if down is None:
        errors.append(f'the break screen stayed up past {BREAK_SECONDS + PLACEMENT + MARGIN} s after the mark')
        return f'up {up - first:+.1f} s from the mark, never cleared', errors
    if down < first + BREAK_SECONDS + PLACEMENT - MARGIN:
        errors.append('the break screen cleared before the break ended')
    seen = len(screen.edges)
    second = first + MARK_EVERY
    again = screen.next(Screen.UP, seen, second + PLACEMENT + MARGIN)
    if again is None:
        errors.append('no break screen for the next mark')
        return f'up {up - first:+.1f} s, down {down - first:+.1f} s, no screen for the next mark', errors
    picture('break-before-ok.png')
    seen = len(screen.edges)
    # The break screen takes OK only with the player's bar down, but the bar stays up in this display (OK then opens
    # the channel list), so Back, which the break screen takes over the bar, stands in for OK.
    press('Escape')
    shown = screen.next(Screen.DOWN, seen, time.time() + 4)
    if shown is None:
        picture('break-back-failed.png')
        errors.append('Back did not show the picture during the break')
        return f'up {up - first:+.1f} s, down {down - first:+.1f} s, Back did not clear the screen', errors
    picture('break-picture.png')
    if shown > second + BREAK_SECONDS + PLACEMENT - MARGIN:
        errors.append('Back came too late to tell from the break ending')
    seen = len(screen.edges)
    if screen.next(Screen.UP, seen, second + BREAK_SECONDS + PLACEMENT + MARGIN) is not None:
        errors.append('the break screen came back after Back during the same break')
    return (f'up {up - first:+.1f} s and down {down - first:+.1f} s from the mark at {first}, '
            f'Back cleared the next at {shown - second:+.1f} s'), errors


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--out', required=True)
    parser.add_argument('--break-check', action='store_true',
                        help='first check the break screen of the first channel against breaks a minute apart')
    parser.add_argument('--keys', type=int, default=int(os.environ.get('SOFAKING_DESKTOP_MONKEY_KEYS') or 600))
    parser.add_argument('--seed', type=int, default=int(os.environ.get('SOFAKING_DESKTOP_MONKEY_SEED') or time.time()))
    args = parser.parse_args()
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    if not APP.exists():
        raise SystemExit('Build :ui:createDistributable in app/ first')
    print(f'Seed {args.seed}, {args.keys} keys', flush=True)

    errors, pressed, breaks, break_errors = [], [], None, []
    log = out / 'app.log'
    with (serve(out, mark_every=MARK_EVERY if args.break_check else BREAK_EVERY) as base,
          tempfile.TemporaryDirectory(prefix='sofa-desktop-') as data):
        channel = next(c['id'] for c in request(base, '/api/v1/channels')['channels'] if c['name'] == 'News Now') \
            if args.break_check else None
        settings_file(Path(data), base, channel)
        # -displayfd writes the display's number once the server is up.
        xvfb = subprocess.Popen(['Xvfb', '-displayfd', '1', '-screen', '0', '1400x900x24', '-nolisten', 'tcp'],
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        display = ':' + xvfb.stdout.readline().decode().strip()
        # No sound card: libVLC plays into ALSA's null device rather than logging a failed open per stream.
        alsa = Path(data, 'asound.conf')
        alsa.write_text('pcm.!default { type null }\nctl.!default { type hw card 0 }\n')
        env = dict(os.environ, DISPLAY=display, XDG_DATA_HOME=data, ALSA_CONFIG_PATH=str(alsa))
        # The JVM writes a crash report (hs_err) to its working directory, so it lands in --out.
        with log.open('w') as log_file:
            app = subprocess.Popen([str(APP)], env=env, cwd=out, stdout=log_file, stderr=subprocess.STDOUT)
        x = lambda *command: subprocess.run(['xdotool', *command], env=env, capture_output=True, text=True, timeout=30)
        shot = lambda: subprocess.run(['import', '-window', 'root', 'png:-'], env=env, capture_output=True, timeout=30).stdout
        try:
            window = wait_for('the window', lambda: x('search', '--name', '^Sofa King$').stdout.split(), 90)[0]
            x('windowfocus', '--sync', window)
            open_sessions = lambda: [s for s in request(base, '/api/v1/admin/sessions') if not s['external']]
            wait_for('the app to open a session', open_sessions, 90)
            if args.break_check:
                screen = Screen(log)

                def save(name):
                    (out / name).write_bytes(shot())
                breaks, failed = check_breaks(screen, lambda key: x('key', key), save)
                break_errors = failed
                print(f'Break check: {breaks}', flush=True)
                # Back leaves the player for the guide, where the random keys start.
                x('key', 'Escape')
                time.sleep(2)

            rng = random.Random(args.seed)
            names, weights = zip(*KEYS)
            blank = 0
            for index, key in enumerate(rng.choices(names, weights, k=args.keys)):
                if key == 'held Return':
                    x('keydown', 'Return', 'sleep', '0.8', 'keyup', 'Return')
                else:
                    x('key', key)
                pressed.append(key)
                time.sleep(0.15)
                if app.poll() is not None:
                    errors.append(f'the app exited ({app.returncode}) after key {index + 1}')
                    break
                if index % 25 == 24:
                    errors += failures(log)
                    blank = blank + 1 if len(shot()) < BLANK_BYTES else 0
                    if blank >= 2:
                        errors.append(f'the screen stayed blank after key {index + 1}')
                    if errors:
                        break
            errors += [f for f in failures(log) if f not in errors]
            (out / 'screen.png').write_bytes(shot())
            sessions_open = len(open_sessions())
            # Closed as a window manager's close button closes it, the app quits and releases its session.
            if app.poll() is None:
                # The frame keeps its X window, but a search can come back empty when a window goes while it walks
                # the tree, so the window found at the start is closed too.
                for current in {window, *x('search', '--name', '^Sofa King$').stdout.split()}:
                    close_window(display, current)
                try:
                    app.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    # The JVM prints every thread's stack to app.log on SIGQUIT, to show what held it.
                    app.send_signal(signal.SIGQUIT)
                    time.sleep(2)
                    errors.append('the app did not quit when its window closed (its threads are in app.log)')
            try:
                wait_for('the app to release its sessions', lambda: not open_sessions(), 10)
                left = 0
            except SystemExit:
                left = len(open_sessions())
                errors.append(f'{left} session(s) left open after the app quit')
        finally:
            if app.poll() is None:
                app.kill()
            xvfb.terminate()

    (out / 'keys.txt').write_text(' '.join(pressed) + '\n')
    summary = [f'## Desktop monkey: seed {args.seed}, {len(pressed)} of {args.keys} keys', '']
    if args.break_check:
        summary += [f'- Break check: {"failed, " if break_errors else ""}{breaks or "the app opened no session"}'
                    + ''.join(f'; {e}' for e in break_errors)]
        errors = break_errors + errors
    summary += [
               f'- Result: {"; ".join(errors[:3]) if errors else "the app kept running, logged no failure and drew every screen"}',
               f'- Sessions open before the window closed: {sessions_open}; left open after the app quit: {left}',
               f'- Focus requests before their target was placed (Compose\'s FocusRelatedWarning): {log.read_text(errors="replace").count("FocusRequester is not initialized")}',
               '- app.log, server.log and the last screen are in the artifact']
    print('\n'.join(summary))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as step:
            step.write('\n'.join(summary) + '\n')
    if errors:
        print(f'The last keys: {" ".join(pressed[-20:])}', file=sys.stderr)
        print(f'Replay with SOFAKING_DESKTOP_MONKEY_SEED={args.seed} SOFAKING_DESKTOP_MONKEY_KEYS={args.keys}', file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
