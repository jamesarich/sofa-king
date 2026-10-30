#!/usr/bin/env python3
"""Take the documentation's screenshots of the app on one platform, against the server's installed distribution serving
the showcase lineup (showcase.py): the guide with its preview, a channel full screen, the Library tab and an item's page,
as the platform can reach them. Writes <platform>-<screen>.png to --out, with the server log beside them. The app's
Appearance is pinned to Dark; `--appearance light` takes the tour again as <platform>-<screen>-light.png (web, desktop,
Android phone and tablet; the TV is always dark). Android's status bar is in demo mode with the clock at 16:30.

Platforms and what each needs besides FFmpeg and the server's distribution (`./gradlew :server:installDist` in backend/):

- web: Playwright for Python and a production web build (`--app ui/build/dist/wasmJs/productionExecutable`); Google
  Chrome (`--channel chrome`) plays the H.264 that Playwright's own Chromium does not.
- desktop: the desktop app (`--app` the launcher `:ui:createDistributable` writes) with VLC 3, Xvfb, Openbox, xdotool
  and ImageMagick; it runs on a display of its own, so nothing shows on yours. Java takes keys only from a window a
  window manager has focused, hence Openbox.
- android-tv, android-phone, android-tablet: adb with one booted emulator of that kind and the debug APK (`--app`),
  which it installs over any earlier one; the settings go in with `run-as`, which only a debug build allows.
- admin: the server's admin portal (Home, Channels, DVR, Devices, Library and Settings) in Playwright's browser at
  1440x900, dark then light, with three recordings scheduled; needs no app. Writes admin-<page>[-light].png.
- ios, tvos: one booted simulator (`xcrun simctl`) and the app the Debug build writes (`--app`, a `.app` folder). The
  simulators take no keys from a script, so these show the guide or channel list, a channel playing (iOS only: Apple
  TV asks before a link opens the app) and the Library tab. The iOS app runs in landscape on a simulator that boots
  upright, so its pictures are turned or cropped to the app.
"""
import argparse
import contextlib
import functools
import http.server
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
import xml.etree.ElementTree as ElementTree

from fixture_server import TOKEN, request, serve, wait_for
import showcase

PACKAGE = 'dev.jamesrich.sofaking'
# Settings every platform starts with: nothing opens full screen or plays sound by itself, and the info bar's key legend
# is already used up.
SETTINGS = {'auto_open_delay': 'OFF', 'preview_sound': False, 'key_legend_shown': 3}
# The Android status bar's clock in demo mode.
CLOCK = '1630'
# The guide's preview starts after 350 ms and needs a few seconds of stream before its first frame.
PREVIEW_SECONDS = 8
# How many times a tap asks uiautomator for the screen before giving up.
DUMP_TRIES = 10
# Downs a TV takes to bring a Library shelf up from under the spotlight.
SHELF_PRESSES = 4


def showcase_server(out, images=None, **settings):
    """The fixture server with the showcase lineup and libraries and any further `SOFA_` [settings]; [images] maps the
    fixture's address to the one the app loads the programs' images from."""
    cache = showcase.prepare()
    build = lambda media, upstream, library: showcase.build(media, upstream, library, cache, images and images(upstream))
    return serve(out, build=build, channels=len(showcase.CHANNELS), libraries=showcase.LIBRARIES, **settings)


class App:
    """One start of the app, as the tour drives it: [keys] presses keys by their web names, [tap] taps the item a text
    or description names (None for the middle of the screen), [shoot] saves the screen under a name, and [touch] says
    the platform is driven by touch rather than keys; [preview] says its guide plays the focused channel, and [logged]
    says whether the app has logged a line with a given text since this start. A platform without keys, taps or the
    app's log leaves them None, and the tour takes another way, skips the screen or waits a fixed time."""

    def __init__(self, shoot, keys=None, tap=None, touch=False, preview=True, logged=None):
        self.shoot, self.keys, self.tap, self.touch, self.preview, self.logged = shoot, keys, tap, touch, preview, logged


def wait_for_session(base, seconds=60):
    wait_for('a playback session', lambda: [s for s in request(base, '/api/v1/admin/sessions') if not s['external']], seconds)


# Web


class Static(http.server.SimpleHTTPRequestHandler):
    """The web build's files, with WebAssembly's type, which streaming compilation insists on."""
    extensions_map = {**http.server.SimpleHTTPRequestHandler.extensions_map, '.wasm': 'application/wasm', '.mjs': 'text/javascript'}

    def log_message(self, *args):
        pass


def web(args, out):
    from playwright.sync_api import sync_playwright

    static = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Static, directory=args.app))
    threading.Thread(target=static.serve_forever, daemon=True).start()
    page_url = f'http://127.0.0.1:{static.server_port}/'
    width, height = args.size
    with showcase_server(out, SOFA_CORS_ORIGINS=page_url.rstrip('/')) as base, sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel=args.channel, args=['--mute-audio', '--autoplay-policy=no-user-gesture-required'])

        @contextlib.contextmanager
        def start(**extra):
            """A fresh page, paired and set up with [extra] settings, as keys to press and a camera."""
            context = browser.new_context(viewport={'width': width, 'height': height}, device_scale_factor=args.scale)
            page = context.new_page()
            page.on('pageerror', lambda error: print(f'page error: {error}', file=sys.stderr))
            settings = {'backend_url': base, 'api_token': TOKEN, **SETTINGS, 'appearance': args.appearance, **extra}
            typed = {name: {'t': 'b' if isinstance(v, bool) else 'i' if isinstance(v, int) else 's', 'v': v} for name, v in settings.items()}
            # Paired as a build before DataStore kept its settings: the app moves them over on its first read.
            page.add_init_script(f'localStorage.getItem("sofaking.preferences") || localStorage.setItem("sofaking.settings", {json.dumps(json.dumps(typed))})')
            page.goto(page_url)

            def keys(*names):
                for name in names:
                    page.keyboard.press(name)
                    page.wait_for_timeout(400)
            try:
                yield App(lambda name: page.screenshot(path=str(picture(out, args, name))), keys)
            finally:
                context.close()

        tour(base, start, out)
        browser.close()


# Desktop


def preferences_pb(settings):
    """The settings as DataStore's preferences file: a protobuf map of name to a Value of one type."""
    def varint(n):
        out = bytearray()
        while True:
            byte, n = n & 0x7F, n >> 7
            out.append(byte | (0x80 if n else 0))
            if not n:
                return bytes(out)

    def field(number, wire, payload):
        return varint(number << 3 | wire) + (varint(len(payload)) + payload if wire == 2 else payload)

    body = b''
    for name, value in settings.items():
        if isinstance(value, bool):
            encoded = field(1, 0, varint(int(value)))
        elif isinstance(value, int):
            encoded = field(3, 0, varint(value))
        else:
            encoded = field(5, 2, value.encode())
        body += field(1, 2, field(1, 2, name.encode()) + field(2, 2, encoded))
    return body


DESKTOP_KEYS = {'ArrowUp': 'Up', 'ArrowDown': 'Down', 'ArrowLeft': 'Left', 'ArrowRight': 'Right', 'Enter': 'Return', 'Escape': 'Escape'}


def desktop(args, out):
    width, height = args.size
    display = ':57'
    screen = f'{int(width * args.scale) + 64}x{int(height * args.scale) + 64}x24'
    xvfb = subprocess.Popen(['Xvfb', display, '-screen', '0', screen, '-nolisten', 'tcp'])
    time.sleep(1)
    manager = subprocess.Popen(['openbox'], env=dict(os.environ, DISPLAY=display), stderr=subprocess.DEVNULL)
    try:
        with showcase_server(out) as base:
            @contextlib.contextmanager
            def start(**extra):
                """The app on a fresh data folder, paired and set up with [extra] settings, as keys and a camera."""
                with tempfile.TemporaryDirectory(prefix='sofa-desktop-') as home:
                    data = Path(home) / 'sofa-king/datastore'
                    data.mkdir(parents=True)
                    settings = {'backend_url': base, 'api_token': TOKEN, **SETTINGS, 'appearance': args.appearance, **extra}
                    (data / 'settings.preferences_pb').write_bytes(preferences_pb(settings))
                    env = dict(os.environ, DISPLAY=display, XDG_DATA_HOME=home, HOSTNAME='Sofa King showcase',
                               JAVA_TOOL_OPTIONS=f'-Dsun.java2d.uiScale={args.scale}')
                    app = subprocess.Popen([args.app], env=env, stdout=(out / 'desktop.log').open('a'), stderr=subprocess.STDOUT)
                    try:
                        search = ['xdotool', 'search', '--onlyvisible', '--name', '^Sofa King$']
                        window = wait_for('the window', lambda: subprocess.run(search, env=env, capture_output=True, text=True).stdout.split()[-1:], 60)[0]
                        xdotool = lambda *a: subprocess.run(['xdotool', *map(str, a)], env=env, check=True)
                        xdotool('windowmove', window, 0, 0)
                        xdotool('windowsize', window, int(width * args.scale), int(height * args.scale))
                        xdotool('windowactivate', '--sync', window)

                        def keys(*names):
                            for name in names:
                                xdotool('key', DESKTOP_KEYS.get(name, name))
                                time.sleep(0.4)

                        yield App(lambda name: subprocess.run(['import', '-window', window, str(picture(out, args, name))], env=env, check=True), keys)
                    finally:
                        app.terminate()
                        app.wait(timeout=15)

            tour(base, start, out)
    finally:
        manager.terminate()
        xvfb.terminate()


# Android


ANDROID_KEYS = {'ArrowUp': 'KEYCODE_DPAD_UP', 'ArrowDown': 'KEYCODE_DPAD_DOWN', 'ArrowLeft': 'KEYCODE_DPAD_LEFT',
                'ArrowRight': 'KEYCODE_DPAD_RIGHT', 'Enter': 'KEYCODE_DPAD_CENTER', 'Escape': 'KEYCODE_BACK'}


def adb(*args, data=None, timeout=60):
    return subprocess.run(['adb', *args], input=data, check=True, capture_output=True, timeout=timeout).stdout


def android(args, out):
    """The emulator reaches the host as 10.0.2.2, the server and the guide's images both."""
    emulated = lambda address: address.replace('127.0.0.1', '10.0.2.2')
    adb('install', '-r', '-g', args.app, timeout=300)
    # The status bar as the demo mode keeps it: no notifications, a full battery and signal, and the clock at a fixed time.
    adb('shell', 'settings', 'put', 'global', 'sysui_demo_allowed', '1')
    for command in (['enter'], ['notifications', '-e', 'visible', 'false'], ['battery', '-e', 'level', '100', '-e', 'plugged', 'false'],
                    ['network', '-e', 'wifi', 'show', '-e', 'level', '4'], ['network', '-e', 'mobile', 'hide'],
                    ['clock', '-e', 'hhmm', CLOCK]):
        adb('shell', 'am', 'broadcast', '-a', 'com.android.systemui.demo', '-e', 'command', *command)
    # The full-screen player would otherwise open under Android's one-time note on how to leave full screen.
    adb('shell', 'settings', 'put', 'secure', 'immersive_mode_confirmations', 'confirmed')
    tv = args.platform == 'android-tv'
    adb('logcat', '-c')
    logcat = subprocess.Popen(['adb', 'logcat', '-v', 'threadtime'], stdout=(out / 'logcat.txt').open('w'), stderr=subprocess.STDOUT)
    with showcase_server(out, images=emulated) as base:
        @contextlib.contextmanager
        def start(**extra):
            """The app from a cold start, paired and set up with [extra] settings."""
            settings = {'backend_url': emulated(base), 'api_token': TOKEN, **SETTINGS, 'appearance': args.appearance, **extra}
            # A fresh files folder, so nothing the last start saved carries over; clearing the app would take back its
            # permissions. Read back, since a write just after the install can land short and leave the app unpaired.
            written = preferences_pb(settings)
            for _ in range(10):
                adb('exec-in', f'run-as {PACKAGE} sh -c "rm -rf files && mkdir -p files/datastore && cat > files/datastore/settings.preferences_pb"',
                    data=written)
                if adb('exec-out', 'run-as', PACKAGE, 'cat', 'files/datastore/settings.preferences_pb') == written:
                    break
                time.sleep(2)
            else:
                raise SystemExit('The app\'s settings did not stay written')
            adb('shell', 'am', 'start', '-W', '-n', f'{PACKAGE}/.MainActivity', timeout=120)
            shoot = lambda name: picture(out, args, name).write_bytes(adb('exec-out', 'screencap', '-p'))

            def keys(*names):
                for name in names:
                    adb('shell', 'input', 'keyevent', ANDROID_KEYS.get(name, name))
                    time.sleep(0.4)

            # The app's own lines, read afresh each time: a long-running logcat can stop on an emulator. Each line
            # carries its time, so the ones from earlier starts are told apart.
            app_log = lambda: adb('logcat', '-d', '-s', 'SofaKing:I').decode(errors='replace').splitlines()
            earlier = set(app_log())
            logged = lambda text: any(text in line and line not in earlier for line in app_log())

            def screen():
                # The dump waits for the screen to settle, so it fails while a loader spins or artwork animates in; it
                # is tried again until it writes.
                for _ in range(DUMP_TRIES):
                    adb('shell', 'rm', '-f', '/data/local/tmp/screen.xml')
                    with contextlib.suppress(subprocess.CalledProcessError, ElementTree.ParseError):
                        adb('shell', 'uiautomator', 'dump', '/data/local/tmp/screen.xml')
                        return ElementTree.fromstring(adb('exec-out', 'cat', '/data/local/tmp/screen.xml'))
                    time.sleep(1)
                raise SystemExit('uiautomator could not dump the screen')

            def tap(text):
                if text is None:
                    size = re.search(rb'(\d+)x(\d+)', adb('shell', 'wm', 'size').splitlines()[-1])
                    return adb('shell', 'input', 'tap', str(int(size[1]) // 2), str(int(size[2]) // 2))
                # The first clickable item whose text or description starts with [text]. On a TV the Library's
                # spotlight can leave a shelf off the screen, so Down, then Up, brings the shelves past and the screen
                # is read again after each.
                named = lambda node: any((node.get(a) or '').startswith(text) for a in ('text', 'content-desc'))
                moves = ['ArrowDown'] * SHELF_PRESSES + ['ArrowUp'] * (2 * SHELF_PRESSES) if tv else []
                for move in [*moves, None]:
                    tree = screen()
                    nodes = [n for n in tree.iter('node') if named(n)]
                    if nodes:
                        break
                    if move is None:
                        raise SystemExit(f'Nothing on screen reads {text!r}')
                    keys(move)
                    time.sleep(1)
                clickable = [n for n in tree.iter('node') if n.get('clickable') == 'true' and any(named(c) for c in n.iter('node'))]
                left, top, right, bottom = map(int, re.findall(r'\d+', (clickable or nodes)[-1].get('bounds')))
                adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))

            try:
                # The TV is driven by the remote's keys; a tap still reaches a library item, more surely than keys
                # through the TV's own layout.
                # Nothing has focus on a touch screen, so its guide previews nothing until a channel is tapped.
                yield App(shoot, keys, tap, touch=not tv, preview=tv, logged=logged)
            except BaseException:
                shoot('failed')
                raise
            finally:
                adb('shell', 'am', 'force-stop', PACKAGE)

        try:
            tour(base, start, out)
        finally:
            logcat.terminate()
    adb('shell', 'am', 'broadcast', '-a', 'com.android.systemui.demo', '-e', 'command', 'exit')


# iOS and Apple TV


def simctl(*args, data=None, timeout=120):
    return subprocess.run(['xcrun', 'simctl', *args], input=data, check=True, capture_output=True, timeout=timeout).stdout


def apple(args, out):
    """The simulator shares the host's network, so the server's own address works. The iOS app keeps its settings in a
    DataStore file in Application Support, the Apple TV app in its user defaults."""
    tv = args.platform == 'tvos'
    bundle = PACKAGE + ('.tv' if tv else '')
    simctl('install', 'booted', args.app, timeout=300)
    if not tv:
        simctl('status_bar', 'booted', 'override', '--time', '9:41', '--batteryState', 'charged', '--batteryLevel', '100',
               '--wifiBars', '3', '--cellularMode', 'notSupported')
    with showcase_server(out) as base:
        @contextlib.contextmanager
        def start(**extra):
            subprocess.run(['xcrun', 'simctl', 'terminate', 'booted', bundle], capture_output=True)
            settings = preferences_pb({'backend_url': base, 'api_token': TOKEN, **SETTINGS, 'appearance': args.appearance, **extra})
            if tv:
                simctl('spawn', 'booted', 'defaults', 'write', bundle, 'sofaking.preferences', '-data', settings.hex())
            else:
                container = Path(simctl('get_app_container', 'booted', bundle, 'data').decode().strip())
                data = container / 'Library/Application Support/datastore'
                data.mkdir(parents=True, exist_ok=True)
                (data / 'settings.preferences_pb').write_bytes(settings)
            simctl('launch', 'booted', bundle)

            def shoot(name):
                path = picture(out, args, name)
                simctl('io', 'booted', 'screenshot', str(path))
                if tv:
                    return
                # The simulator stands upright and the app lies across it: an iPhone's picture is turned a quarter,
                # an iPad's shows the app across its width between black bands.
                size = subprocess.run(['sips', '-g', 'pixelWidth', '-g', 'pixelHeight', str(path)], capture_output=True, text=True, check=True).stdout
                width, height = map(int, re.findall(r'pixel(?:Width|Height): (\d+)', size))
                if args.name == 'iphone':
                    subprocess.run(['sips', '-r', '270', str(path)], capture_output=True, check=True)
                elif height > width:
                    subprocess.run(['sips', '-c', str(width * width // height), str(width), str(path)], capture_output=True, check=True)

            try:
                # Nothing has focus on a touch screen, and the Apple TV's channel list has no preview.
                yield App(shoot, preview=False)
            except BaseException:
                shoot('failed')
                raise
            finally:
                simctl('terminate', 'booted', bundle)

        # The Apple TV app has no channel to start on, and Apple TV asks before the Top Shelf's link opens one.
        tour(base, start, out, channel=not tv)
    if not tv:
        simctl('status_bar', 'booted', 'clear')


def picture(out, args, name):
    """Where a platform's screen [name] goes: <platform>-<screen>.png, with -light after it in the light appearance."""
    return out / f'{args.name}-{name}{args.suffix}.png'


# Admin portal


ADMIN_PAGES = {'home': '/admin', 'channels': '/admin/lineup', 'dvr': '/admin/recordings', 'devices': '/admin/viewers',
               'library': '/admin/library', 'settings': '/admin/settings'}


def admin(args, out):
    """The portal's pages at 1440x900, dark and light, signed in with the fixture token; the DVR holds three recordings
    that start tomorrow and after. The portal follows the browser's color scheme, so each pass emulates one."""
    from playwright.sync_api import sync_playwright

    with showcase_server(out) as base, sync_playwright() as playwright:
        channels = request(base, '/api/v1/channels')['channels']
        day = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) + timedelta(days=1)
        for number, title in enumerate(['Friday Movie Night', 'Saturday Matinee', 'Sunday Cartoons']):
            start = day + timedelta(days=number, hours=number)
            request(base, '/api/v1/recordings', 'POST', {
                'channelId': channels[number]['id'], 'title': title, 'startsAt': start.strftime('%Y-%m-%dT%H:%M:%SZ'),
                'endsAt': (start + timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%SZ')})
        browser = playwright.chromium.launch(channel=args.channel)
        for scheme in ('dark', 'light'):
            context = browser.new_context(viewport={'width': 1440, 'height': 900}, color_scheme=scheme)
            page = context.new_page()
            page.on('pageerror', lambda error: print(f'page error: {error}', file=sys.stderr))
            page.goto(base + '/admin')
            page.fill('#token', TOKEN)
            page.press('#token', 'Enter')
            page.wait_for_url(base + '/admin')
            for name, path in ADMIN_PAGES.items():
                page.goto(base + path)
                page.wait_for_load_state('networkidle')
                page.screenshot(path=str(out / f'admin-{name}{"-light" if scheme == "light" else ""}.png'))
            context.close()
        browser.close()


def served(out, path):
    """How many times the server's log shows it answering [path]."""
    try:
        return (out / 'server.log').read_text().count(f'path={path} status=200')
    except FileNotFoundError:
        return 0


def wait_served(out, path, before, seconds=90):
    """Waits until the server has answered [path] more than [before] times, so a screen has its data before its picture;
    a platform that loads it another way costs only the wait."""
    deadline = time.monotonic() + seconds
    while served(out, path) <= before and time.monotonic() < deadline:
        time.sleep(0.5)


def tour(base, start, out, channel=True):
    """The guide with its preview, a channel full screen, the Library tab, a show's page and a movie's page: by keys
    where the platform is driven by keys, by taps where it takes them, and otherwise as far as settings reach; without
    [channel], a platform that cannot start on a channel skips it."""
    before = served(out, '/api/v1/guide/query')
    with start(startup='GUIDE') as app:
        wait_served(out, '/api/v1/guide/query', before)
        if app.logged:
            # An emulator without a GPU decodes the programs for some seconds after the server sends them, and draws
            # them some seconds after that.
            wait_for('the listings', lambda: app.logged('SofaKing: guide: '), 120)
        if app.preview:
            wait_for_session(base)
        time.sleep(PREVIEW_SECONDS if app.preview or app.logged else 3)
        app.shoot('guide')
        by_keys = app.keys and not app.touch
        if by_keys:
            app.keys('Enter')
            # OK on a playing channel shows its info bar, or opens the channel list when the bar is already up, as it
            # is on a TV just after the channel opens.
            time.sleep(6)
            app.keys('Enter')
            time.sleep(1.5)
            app.shoot('player')
    if not by_keys and channel:
        first = request(base, '/api/v1/channels')['channels'][0]['id']
        with start(startup='CHANNEL', startup_channel=first) as app:
            wait_for_session(base)
            time.sleep(PREVIEW_SECONDS)
            if app.touch:
                # A tap on the picture brings up the controls.
                app.tap(None)
                time.sleep(1.5)
            app.shoot('player')
    before = served(out, '/api/v1/library/home')
    with start(startup='LIBRARY', last_tab='LIBRARY') as app:
        wait_served(out, '/api/v1/library/home', before)
        # The shelves' artwork loads after them.
        time.sleep(5)
        app.shoot('library')
        if not app.keys:
            return
        if app.tap and not app.touch:
            # On a TV the spotlight leaves the TV Shows shelf below the screen, and the remote's Downs do not always
            # bring it up in time; the TV Shows chip opens the library with the show in view.
            app.tap('TV Shows')
            time.sleep(4)
            app.tap(showcase.SHOW['title'])
        elif app.tap:
            app.tap(showcase.SHOW['title'])
        else:
            # A start on the Library tab focuses the tab in the rail; Right reaches the chips, Down the shelves.
            app.keys('ArrowRight', 'ArrowDown', 'ArrowDown', 'Enter')
        time.sleep(4)
        app.shoot('show')
        app.keys('Escape')
        time.sleep(2)
        if app.tap and not app.touch:
            # Back from the show is the TV Shows grid; once more is the Library tab with the Movies shelf.
            app.keys('Escape')
            time.sleep(2)
        if app.tap:
            # The first two movies fit across a phone; Big Buck Bunny, which the keys reach, does not.
            app.tap(showcase.MOVIES['sintel']['title'])
        else:
            app.keys('ArrowUp', 'ArrowRight', 'ArrowRight', 'ArrowRight', 'Enter')
        time.sleep(4)
        app.shoot('movie')


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('platform', choices=['web', 'desktop', 'android-tv', 'android-phone', 'android-tablet', 'ios', 'tvos', 'admin'])
    parser.add_argument('--app', help='the build to run: a web build folder, the desktop launcher or the APK')
    parser.add_argument('--out', required=True)
    parser.add_argument('--channel', help='the browser Playwright runs by channel, such as chrome')
    parser.add_argument('--size', default='1280x720', type=lambda s: tuple(map(int, s.split('x'))), help='window size in points')
    parser.add_argument('--scale', default=2.0, type=float, help='pixels per point')
    parser.add_argument('--appearance', default='dark', choices=['dark', 'light'],
                        help='the app\'s Appearance setting; light pictures are named <platform>-<screen>-light.png')
    parser.add_argument('--name', help='the files\' prefix in place of the platform, such as ipad')
    args = parser.parse_args()
    args.name = args.name or args.platform
    if args.appearance == 'light' and args.platform in ('android-tv', 'ios', 'tvos', 'admin'):
        parser.error('only the web, desktop, Android phone and tablet apps have a light appearance here')
    args.suffix = '-light' if args.appearance == 'light' else ''
    args.appearance = args.appearance.upper()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    driver = {'web': web, 'admin': admin, 'desktop': desktop, 'ios': apple, 'tvos': apple}.get(args.platform, android)
    driver(args, out)


if __name__ == '__main__':
    main()
