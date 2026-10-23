#!/usr/bin/env python3
"""Press random remote keys on the production web app in a headless browser, against the server's installed
distribution serving fixture channels; fail when the app takes no keys, or on an uncaught error on the page, an error the
console logged, a failed request, a crashed page or a blank screen.

Needs Playwright for Python, FFmpeg and the server's distribution (`./gradlew :server:installDist` in backend/), and
the web build: `./gradlew :ui:wasmJsBrowserDistribution` or `:ui:jsBrowserDistribution`. Writes the console, the server
log, the keys and the last screen to --out.
"""
import argparse
import functools
import http.server
import json
import os
from pathlib import Path
import random
import sys
import threading
import time

from playwright.sync_api import TimeoutError as PlaywrightTimeout, sync_playwright

from fixture_server import TOKEN, request, serve, wait_for

# The keyboard's remote keys, weighted as AppDriver.REMOTE weighs the remote's for the JVM fuzz.
KEYS = [
    ('ArrowUp', 12), ('ArrowDown', 14), ('ArrowLeft', 10), ('ArrowRight', 12), ('Enter', 10), ('Escape', 6),
    ('PageUp', 2), ('PageDown', 2), ('Space', 1),
]
# A screenshot this small is one colour: the app drew nothing.
BLANK_BYTES = 4000
# A failed request logs a console error without its address, so requests are checked by their responses instead; a
# device with no saved preferences yet gets a 404 for them.
EXPECTED = (('/api/v1/preferences/', 404),)


class Static(http.server.SimpleHTTPRequestHandler):
    """The web build's files, with WebAssembly's type, which streaming compilation insists on."""
    extensions_map = {**http.server.SimpleHTTPRequestHandler.extensions_map, '.wasm': 'application/wasm', '.mjs': 'text/javascript'}

    def log_message(self, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--dist', required=True, help='the web build, such as ui/build/dist/wasmJs/productionExecutable')
    parser.add_argument('--out', required=True)
    parser.add_argument('--keys', type=int, default=int(os.environ.get('SOFAKING_WEB_FUZZ_KEYS') or 400))
    parser.add_argument('--seed', type=int, default=int(os.environ.get('SOFAKING_WEB_FUZZ_SEED') or time.time()))
    parser.add_argument('--channel', help="a browser Playwright runs by channel, such as chrome, which plays H.264")
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    print(f'Seed {args.seed}, {args.keys} keys, {args.dist}', flush=True)

    static = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Static, directory=args.dist))
    threading.Thread(target=static.serve_forever, daemon=True).start()
    page_url = f'http://127.0.0.1:{static.server_port}/'

    errors, console = [], []
    pressed = []
    with serve(out, SOFA_CORS_ORIGINS=page_url.rstrip('/')) as base, sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel=args.channel)
        page = browser.new_page(viewport={'width': 1280, 'height': 720})
        page.on('pageerror', lambda error: errors.append(f'page error: {error}'))
        page.on('crash', lambda _: errors.append('the page crashed'))

        def logged(message):
            console.append(f'{message.type}: {message.text}')
            if message.type == 'error' and not message.text.startswith('Failed to load resource'):
                errors.append(f'console error: {message.text}')
        page.on('console', logged)

        def answered(response):
            expected = any(path in response.url and response.status == status for path, status in EXPECTED)
            if response.status >= 400 and not expected:
                errors.append(f'{response.request.method} {response.url} answered {response.status}')
        page.on('response', answered)
        # Paired as a build before DataStore kept its settings: the app moves them over on its first read.
        settings = {'backend_url': {'t': 's', 'v': base}, 'api_token': {'t': 's', 'v': TOKEN}}
        page.add_init_script(f'localStorage.getItem("sofaking.preferences") || localStorage.setItem("sofaking.settings", {json.dumps(json.dumps(settings))})')
        try:
            with page.expect_response(lambda response: '/api/v1/channels' in response.url, timeout=90_000):
                page.goto(page_url)
        except PlaywrightTimeout:
            errors.append('the app never asked the server for its channels')
        # The guide draws once the lineup is in.
        time.sleep(5)
        # Keys go to the focused element, and the page opens with its body focused, so the app must take focus itself.
        if not errors and page.evaluate('document.activeElement === document.body'):
            errors.append('the app took no keys: the page body kept the focus')

        rng = random.Random(args.seed)
        names, weights = zip(*KEYS)
        blank = 0
        for index, key in enumerate(rng.choices(names, weights, k=args.keys)):
            if errors:
                break
            page.keyboard.press(key)
            pressed.append(key)
            page.wait_for_timeout(150)
            if index % 25 == 24:
                blank = blank + 1 if len(page.screenshot()) < BLANK_BYTES else 0
                if blank >= 2:
                    errors.append(f'the screen stayed blank after key {index + 1}')
                    break
        page.screenshot(path=str(out / 'screen.png'))
        open_before = [s for s in request(base, '/api/v1/admin/sessions') if not s['external']]
        page.close()
        browser.close()
        try:
            wait_for('the page to release its sessions',
                     lambda: not [s for s in request(base, '/api/v1/admin/sessions') if not s['external']], 20)
            left = 0
        except SystemExit:
            left = len([s for s in request(base, '/api/v1/admin/sessions') if not s['external']])

    (out / 'console.txt').write_text('\n'.join(console) + '\n')
    (out / 'keys.txt').write_text(' '.join(pressed) + '\n')
    summary = [f'## Web fuzz: {Path(args.dist).parent.name}, seed {args.seed}, {len(pressed)} of {args.keys} keys', '',
               f'- Result: {"; ".join(errors[:3]) if errors else "no page error, console error, failed request or blank screen"}',
               f'- Sessions open while the page was up: {len(open_before)}; left open after it closed: {left}',
               f'- Console messages: {len(console)} (console.txt in the artifact)']
    print('\n'.join(summary))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as step:
            step.write('\n'.join(summary) + '\n')
    if errors:
        print(f'The last keys: {" ".join(pressed[-20:])}', file=sys.stderr)
        print(f'Replay with SOFAKING_WEB_FUZZ_SEED={args.seed} SOFAKING_WEB_FUZZ_KEYS={args.keys}', file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
