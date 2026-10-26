#!/usr/bin/env python3
"""Run the server's installed distribution against fixture channels, for tests that drive an app on a device or
simulator: eight channels from one FFmpeg clip (the first as HLS, the rest as endless MPEG-TS), six hours of guide, and
the lineup adopted. With --shows it also serves a TV shows library of four six-minute episodes that share a theme and
end credits, whose intros, credits and scrub thumbnails the server finds and makes, with subtitles and spoiler-laden
summaries; with --movies, a Movies library of 84 titles from A to Z with genres and a trailer, for the letter jump,
More like this and Extras; tv-monkey.py asks instead for a short show of two 20-second episodes, which
its Maestro flows play to the end. Run directly, it serves until stopped and writes the server's address and token
to <out>/server.env, for `xcodebuild test` and the like; tv-monkey.py imports it.

Needs FFmpeg and the server's distribution (`./gradlew :server:installDist` in backend/).
"""
import argparse
import contextlib
import functools
import http.server
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from urllib.error import URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / 'backend/server/build/install/server/bin/server'
TOKEN = 'local-fixture-token-' * 3
CHANNELS = 8
NAMES = ['News Now', 'Sofa Sports', 'Movies', 'Kids', 'Local 4', 'Weather', 'Music', 'Classics']
# The channel filed under Kids, which kids mode shows, and the games on Sofa Sports, which a team rule records.
KIDS = 3
TEAMS = ['Bears at Packers', 'Lions at Vikings', 'Packers at Lions']
# The fixture clip's length, and the most the endless channels send at a time.
CLIP_SECONDS = 10
FPS = 25
# The clip's captions: a new one this often, in seconds, which CLIP_SECONDS divides.
CAPTION_SECONDS = 2
# The start code and type of an H.264 access unit delimiter, which x264 writes before each frame when asked.
DELIMITER = b'\x00\x00\x00\x01\x09'
PIECE = 188 * 64
# How many segments a live HLS playlist lists.
LIVE_WINDOW = 5
# The fixture show's episodes: a cold open, then the theme they share, then the rest and the credits they share, in
# seconds. The server reads the first 30% of an episode for its intro and its last tenth for its credits, and makes scrub
# thumbnails for five minutes or more from keyframes, which the episodes carry every 2 seconds (-g 20 at 10 fps).
EPISODES = 4
COLD_OPEN, THEME, CREDITS, EPISODE = 10, 45, 30, 360
# The fixture movies: enough titles for the library's row of first letters (80 or more), three genres between them.
MOVIE_WORDS = ['Amber', 'Bright', 'Copper', 'Distant', 'Echo', 'Falling', 'Golden', 'Hidden', 'Iron', 'Jade', 'Kind',
               'Lunar', 'Midnight', 'Northern', 'Open', 'Paper', 'Quiet', 'Red', 'Silver', 'Tidal', 'Upper', 'Velvet',
               'Winter', 'Xenon', 'Yellow', 'Zero']
GENRES = ['Drama', 'Comedy', 'Science Fiction']
# The short show's two episodes, long enough for a test to pause and seek in and short enough to reach Up next.
EPISODE_SECONDS = 20


class Upstream(http.server.SimpleHTTPRequestHandler):
    """Serves the fixture files, and /endless/<n>.ts as a live MPEG-TS stream that repeats one clip until the reader goes,
    at the clip's own pace as a broadcast arrives (a whole clip at once overflows the server's buffer for a new reader),
    and /live/<n>.m3u8 as a live HLS playlist of the segments in live-<n>/. Every file allows any origin, so a web page
    can load the guide's images."""

    def do_GET(self):
        if self.path.startswith('/live/'):
            return self.live()
        if not self.path.startswith('/endless/'):
            return super().do_GET()
        # FFmpeg loops the clip with its timestamps carried on, as a broadcast's keep rising; the clip's own bytes
        # repeated would jump back to the start every loop, which a player takes for a broken stream.
        loop = subprocess.Popen(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-re', '-stream_loop', '-1', '-i',
                                 str(Path(self.directory) / 'clip.ts'), '-c', 'copy', '-f', 'mpegts', 'pipe:1'],
                                stdout=subprocess.PIPE)
        try:
            self.send_response(200)
            self.send_header('Content-Type', 'video/mp2t')
            self.end_headers()
            while piece := loop.stdout.read1(PIECE):
                self.wfile.write(piece)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            loop.kill()
            loop.wait()

    def live(self):
        """/live/<n>.m3u8 as a live HLS playlist that loops the segments in live-<n>/ (each listed with its length in
        live-<n>/segments.json) as if they had been airing since the epoch, with a discontinuity at each loop."""
        folder = Path(self.directory) / f'live-{Path(self.path).stem}'
        if not (folder / 'segments.json').exists():
            return self.send_error(404)
        segments = json.loads((folder / 'segments.json').read_text())
        target = max(seconds for _, seconds in segments)
        now = int(time.time() / target)
        first = now - LIVE_WINDOW + 1
        lines = ['#EXTM3U', '#EXT-X-VERSION:3', f'#EXT-X-TARGETDURATION:{round(target + 0.5)}',
                 f'#EXT-X-MEDIA-SEQUENCE:{first}', f'#EXT-X-DISCONTINUITY-SEQUENCE:{first // len(segments)}']
        for number in range(first, now + 1):
            name, seconds = segments[number % len(segments)]
            if number % len(segments) == 0 and number != first:
                lines.append('#EXT-X-DISCONTINUITY')
            lines += [f'#EXTINF:{seconds:.3f},', f'/live-{Path(self.path).stem}/{name}']
        body = ('\n'.join(lines) + '\n').encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/vnd.apple.mpegurl')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-cache')
        self.end_headers()
        self.wfile.write(body)

    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        super().end_headers()

    def log_message(self, *args):
        pass


def request(base, path, method='GET', body=None, timeout=5):
    data = None if body is None else json.dumps(body).encode()
    req = Request(base + path, data=data, method=method,
                  headers={'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json'})
    with urlopen(req, timeout=timeout) as response:
        content = response.read().decode()
        return json.loads(content) if content else None


def wait_for(what, check, seconds):
    deadline = time.monotonic() + seconds
    while True:
        try:
            result = check()
            if result:
                return result
        except (URLError, TimeoutError, ConnectionError):
            pass
        if time.monotonic() > deadline:
            raise SystemExit(f'Timed out waiting for {what}')
        time.sleep(0.5)


def fixtures(media, upstream, library=None, quiet=False):
    """A ten-second clip with captions for the TS channels, the same clip as HLS for the first channel, an M3U and six
    hours of guide. The clip's tone is silent when [quiet], for a simulator that plays through the Mac's speakers."""
    video = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', f'testsrc2=size=640x360:rate={FPS}',
                            '-t', str(CLIP_SECONDS), '-c:v', 'libx264', '-preset', 'ultrafast', '-g', str(FPS),
                            '-x264-params', 'aud=1', '-f', 'h264', 'pipe:1'], check=True, timeout=60, capture_output=True).stdout
    (media / 'clip.h264').write_bytes(captioned(video))
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-framerate', str(FPS), '-f', 'h264', '-i', str(media / 'clip.h264'),
                    '-f', 'lavfi', '-i', f'sine=frequency=440:sample_rate=48000,volume={0 if quiet else 1}', '-t', str(CLIP_SECONDS),
                    '-c:v', 'copy', '-c:a', 'aac', '-f', 'mpegts', str(media / 'clip.ts')], check=True, timeout=60)
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', str(media / 'clip.ts'), '-c', 'copy',
                    '-hls_time', '2', '-hls_playlist_type', 'vod', str(media / 'live.m3u8')], check=True, timeout=60)
    playlist = ['#EXTM3U']
    for i in range(CHANNELS):
        url = f'{upstream}/live.m3u8' if i == 0 else f'{upstream}/endless/{i}.ts'
        group = 'Kids' if i == KIDS else 'News' if i % 2 == 0 else 'Sports'
        playlist += [f'#EXTINF:-1 tvg-id="c{i}" tvg-chno="{i + 2}" group-title="{group}",{NAMES[i]}', url]
    (media / 'feed.m3u').write_text('\n'.join(playlist) + '\n')
    start = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) - timedelta(hours=1)
    stamp = lambda t: t.strftime('%Y%m%d%H%M%S +0000')
    programs = []
    for i in range(CHANNELS):
        for slot in range(12):
            begin = start + timedelta(minutes=30 * slot)
            title, extra = program(i, slot)
            sub = f'<sub-title>{TEAMS[slot % len(TEAMS)]}</sub-title>' if i == 1 else ''
            programs.append(f'<programme channel="c{i}" start="{stamp(begin)}" stop="{stamp(begin + timedelta(minutes=30))}">'
                              f'<title>{title}</title>{sub}<desc>A fixture program.</desc>{extra}</programme>')
    (media / 'guide.xml').write_text('<tv>' + ''.join(programs) + '</tv>')


def program(channel, slot):
    """The title of [channel]'s program in [slot], and the XMLTV elements after its description, which give On later
    each of its kinds: games on Sofa Sports (whose sub-titles name the teams), movies, a kids' channel and premieres on
    Local 4; and Classics repeats three titles, so a program has other airings."""
    if channel == 1:
        return f'{NAMES[channel]} show {slot + 1}', '<category>Sports</category>'
    if channel == 2:
        return f'{NAMES[channel]} show {slot + 1}', '<category>Movie</category><category>Drama</category>'
    if channel == KIDS:
        return f'{NAMES[channel]} show {slot + 1}', '<category>Children</category>'
    if channel == 4 and slot % 3 == 2:
        return f'{NAMES[channel]} show {slot + 1}', '<category>Premiere</category><new/>'
    if channel == 7:
        return f'{NAMES[channel]} show {slot % 3 + 1}', '<previously-shown/>' if slot >= 3 else ''
    return f'{NAMES[channel]} show {slot + 1}', ''


def captioned(video):
    """[video], an H.264 stream with an access unit delimiter before each frame, with CEA-608 captions in it as players
    read them from a broadcast: a pop-on caption every [CAPTION_SECONDS], one byte pair a frame in an SEI message after
    each delimiter."""
    frames = video.split(DELIMITER)
    pairs = []
    for k in range(CLIP_SECONDS // CAPTION_SECONDS):
        start = k * CAPTION_SECONDS * FPS
        text = f'Fixture caption {k + 1}'.encode()
        text += b' ' * (len(text) % 2)
        codes = [(0x14, 0x20), (0x14, 0x2E), (0x14, 0x60)] + list(zip(text[::2], text[1::2])) + [(0x14, 0x2F)]
        pairs += [None] * (start - len(pairs)) + codes
    out = [frames[0]]
    for n, frame in enumerate(frames[1:]):
        pair = pairs[n] if n < len(pairs) else None
        # The delimiter's own byte, then the caption.
        out.append(DELIMITER + frame[:1] + sei(pair) + frame[1:])
    return b''.join(out)


def sei(pair):
    """An SEI NAL unit carrying one CEA-608 byte pair on field 1 as ATSC A/53 caption data, or padding for None."""
    odd = lambda b: b | (0x80 if bin(b).count('1') % 2 == 0 else 0)
    cc = bytes([0xFC, odd(pair[0]), odd(pair[1])]) if pair else bytes([0xF8, 0x80, 0x80])
    payload = bytes([0xB5, 0x00, 0x31]) + b'GA94' + bytes([0x03, 0x40 | 1, 0xFF]) + cc + bytes([0xFF])
    rbsp = bytes([0x04, len(payload)]) + payload + bytes([0x80])
    escaped = bytearray()
    for b in rbsp:
        # Emulation prevention: no 00 00 followed by 00 to 03 inside a NAL unit.
        if len(escaped) >= 2 and escaped[-1] == 0 and escaped[-2] == 0 and b <= 3:
            escaped.append(3)
        escaped.append(b)
    return b'\x00\x00\x00\x01\x06' + bytes(escaped)


def tune(a, b, seconds):
    """[seconds] of a tune of quarter-second notes, which [a] and [b] pick: Chromaprint tells one tune from another."""
    note = f'220*pow(2,mod(floor(t*4)*{a}+{b},24)/12)'
    return ['-f', 'lavfi', '-i', f"aevalsrc='0.4*sin(2*PI*{note}*t)+0.2*sin(4*PI*{note}*t)':s=48000:d={seconds}"]


def show(library):
    """[EPISODES] episodes of one show, each a cold open and a body of its own around the same theme, which the server
    finds as their intro (about COLD_OPEN to COLD_OPEN + THEME seconds in), ending in the same credits. Each has English
    subtitles beside it, a cue every five seconds, and an .nfo whose summary gives the episode away."""
    muxers = subprocess.run(['ffmpeg', '-hide_banner', '-muxers'], capture_output=True, text=True, check=True).stdout
    if ' chromaprint ' not in muxers:
        raise SystemExit('--shows needs an FFmpeg built with Chromaprint (--enable-chromaprint), which finds the intros')
    folder = library / 'Fixture Show'
    season = folder / 'Season 01'
    season.mkdir(parents=True)
    (folder / 'tvshow.nfo').write_text('<tvshow><title>Fixture Show</title><plot>A show made for tests.</plot>'
                                       '<genre>Comedy</genre></tvshow>')
    body = EPISODE - COLD_OPEN - THEME - CREDITS
    clock = lambda at: f'{at // 3600:02d}:{at // 60 % 60:02d}:{at % 60:02d},000'
    for n in range(1, EPISODES + 1):
        name = f'Fixture Show - S01E{n:02d} - Episode {n}'
        picture = ['-f', 'lavfi', '-i', f'testsrc=size=320x180:rate=10:d={EPISODE}']
        sound = tune(13 + 2 * n, 1 + 3 * n, COLD_OPEN) + tune(7, 3, THEME) + tune(11 + 2 * n, 5 + n, body) + tune(5, 9, CREDITS)
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', *picture, *sound, '-filter_complex',
                        '[1:a][2:a][3:a][4:a]concat=n=4:v=0:a=1[a]', '-map', '0:v', '-map', '[a]', '-c:v', 'libx264',
                        '-pix_fmt', 'yuv420p', '-preset', 'ultrafast', '-g', '20', '-c:a', 'aac', str(season / f'{name}.mkv')], check=True, timeout=180)
        cues = [f'{k + 1}\n{clock(at)} --> {clock(at + 4)}\nLine {k + 1} of episode {n}\n' for k, at in enumerate(range(0, EPISODE, 5))]
        (season / f'{name}.en.srt').write_text('\n'.join(cues))
        (season / f'{name}.nfo').write_text(f'<episodedetails><title>Episode {n}</title><season>1</season><episode>{n}</episode>'
                                            f'<plot>Spoiler: in episode {n} the butler did it.</plot></episodedetails>')


def intros(base, library):
    """The fixture show's episodes, once each has its intro, its credits and its scrub thumbnails."""
    shows = request(base, f'/api/v1/libraries/{library}/items')['items']
    seasons = shows and request(base, f"/api/v1/library/items/{shows[0]['id']}")['children']
    episodes = seasons and request(base, f"/api/v1/library/items/{seasons[0]['id']}")['children']
    found = [request(base, f"/api/v1/library/items/{episode['id']}") for episode in episodes or []]
    done = lambda item: {s['kind'] for s in item['segments']} == {'intro', 'credits'} and item.get('scrub')
    return len(found) == EPISODES and all(done(item) for item in found) and found


def movies(library):
    """A Movies library of one short clip under 84 titles, three to a first letter and some with a leading article or
    a digit, each in its own folder with an .nfo of two genres; the first has a trailer in Trailers and a featurette."""
    clip = library / 'clip.mp4'
    library.mkdir(parents=True)
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=size=320x180:rate=10',
                    '-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=stereo', '-t', '4', '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
                    '-preset', 'ultrafast',
                    '-c:a', 'aac', '-movflags', '+faststart', str(clip)], check=True, timeout=60)
    titles = [f'{word} {part}' for word in MOVIE_WORDS for part in ('Road', 'House', 'Sky')]
    titles[1], titles[4] = 'The ' + titles[1], 'A ' + titles[4]
    titles += ['2 Hearts', '7 Doors', '10 Rivers', '99 Lanterns', '1984 Again', '3 Bridges']
    for i, title in enumerate(titles):
        name = f'{title} ({1960 + i})'
        folder = library / name
        folder.mkdir()
        shutil.copyfile(clip, folder / f'{name}.mp4')
        genres = ''.join(f'<genre>{GENRES[(i + k) % len(GENRES)]}</genre>' for k in range(2))
        (folder / f'{name}.nfo').write_text(f'<movie><title>{title}</title><year>{1960 + i}</year>{genres}'
                                            f'<plot>Fixture movie {i + 1}.</plot><rating>{5 + i % 5}.5</rating></movie>')
        if i == 0:
            (folder / 'Trailers').mkdir()
            shutil.copyfile(clip, folder / 'Trailers' / f'{title} trailer.mp4')
            shutil.copyfile(clip, folder / f'{name}-featurette.mp4')
    clip.unlink()
    return len(titles)


def short_show(library):
    """A show of two short episodes, MP4 with H.264 and AAC, which the debug Android app plays as it is."""
    season = library / 'Short Show' / 'Season 01'
    season.mkdir(parents=True)
    for episode in (1, 2):
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=25',
                        '-f', 'lavfi', '-i', f'sine=frequency={440 * episode}:sample_rate=48000', '-t', str(EPISODE_SECONDS),
                        '-c:v', 'libx264', '-preset', 'ultrafast', '-g', '25', '-c:a', 'aac', '-movflags', '+faststart',
                        str(season / f'Short Show - S01E0{episode}.mp4')], check=True, timeout=60)


def show_episodes(base):
    """The short show's episode ids, in order, from a server started with short=True."""
    library = next(l for l in request(base, '/api/v1/admin/libraries') if l['name'] == 'Short show')
    show = request(base, f'/api/v1/libraries/{library["id"]}/items')['items'][0]
    season = request(base, f'/api/v1/library/items/{show["id"]}')['children'][0]
    episodes = request(base, f'/api/v1/library/items/{season["id"]}')['children']
    return [e['id'] for e in sorted(episodes, key=lambda e: e.get('episode') or 0)]


@contextlib.contextmanager
def serve(out, build=None, channels=CHANNELS, libraries=(), shows=False, short=False, movies_library=False, quiet=False,
          virtual=False, **settings):
    """Starts the fixture channels and the server, with its log in <out>/server.log and any further `SOFA_` [settings],
    and yields the server's base URL once the lineup is adopted, with [shows] the fixture show's intros found, with
    [short] the short show scanned, with [movies_library] the fixture movies scanned, and with [virtual] a virtual channel
    in the lineup (see virtual_channel); stops both on the way out.
    [build] writes the channels' files, playlist and guide into the folder it is given and serves them from the address
    it is given, the [channels] the lineup then holds, and the server's media folder, which it fills for [libraries]; the
    fixture channels by default, silent when [quiet]. Each of [libraries], a (name, kind, folder) triple, is added from
    that folder once the lineup is in, and scanned before this yields. The show's theme keeps its sound, which finds the
    intros."""
    if not SERVER.exists():
        raise SystemExit('Build :server:installDist in backend/ first')
    with tempfile.TemporaryDirectory(prefix='sofa-fixture-') as temporary:
        directory = Path(temporary)
        media = directory / 'media'
        media.mkdir()
        fixture = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Upstream, directory=str(media)))
        threading.Thread(target=fixture.serve_forever, daemon=True).start()
        upstream = f'http://127.0.0.1:{fixture.server_port}'
        library = directory / 'library'
        library.mkdir()
        (build or functools.partial(fixtures, quiet=quiet))(media, upstream, library)
        if shows:
            show(library / 'shows')
        if short:
            short_show(library / 'short')
        titles = movies(library / 'movies') if movies_library else 0
        sources = directory / 'sources.json'
        providers = [{'id': 'fixture', 'playlistUrl': upstream + '/feed.m3u', 'guideUrl': upstream + '/guide.xml', 'probe': False}]
        if virtual:
            providers.append({'id': 'virtual', 'type': 'native', 'service': 'virtual'})
        sources.write_text(json.dumps({'providers': providers}))
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        base = f'http://127.0.0.1:{port}'
        # The fixture serves from loopback, which the server refuses unless told it is a development fixture. The DVR
        # keeps no free space, so a full CI disk still records.
        env = dict(os.environ, SOFA_HOST='127.0.0.1', SOFA_PORT=str(port), SOFA_DATA_DIR=str(directory / 'data'),
                   SOFA_SOURCES=str(sources), SOFA_API_TOKEN=TOKEN, SOFA_ALLOW_LOOPBACK_UPSTREAMS='true', SOFA_UPDATE_IMAGE='',
                   SOFA_RECORDINGS_FREE_GIB='0', SOFA_MEDIA_DIR=str(library), **settings)
        server_log = (out / 'server.log').open('w')
        server = subprocess.Popen([str(SERVER)], env=env, stdout=server_log, stderr=server_log, start_new_session=True)
        try:
            wait_for('the fixture import', lambda: all(p['lastSuccessAt'] for p in request(base, '/api/v1/providers')), 60)
            request(base, '/api/v1/admin/lineup/adopt', 'POST', {'providerId': 'fixture'})
            wait_for('the lineup', lambda: len(request(base, '/api/v1/channels')['channels']) == channels, 30)
            if virtual:
                virtual_channel(base)
            for name, kind, folder in libraries:
                added = request(base, '/api/v1/admin/libraries', 'POST', {'name': name, 'kind': kind, 'path': str(library / folder)})
                request(base, f'/api/v1/admin/libraries/{added["id"]}/scan', 'POST', timeout=300)
            if shows:
                created = request(base, '/api/v1/admin/libraries', 'POST', {'name': 'TV Shows', 'kind': 'shows', 'path': str(library / 'shows')})
                wait_for('the fixture show\'s intros, credits and scrub thumbnails', lambda: intros(base, created['id']), 300)
            if movies_library:
                created = request(base, '/api/v1/admin/libraries', 'POST', {'name': 'Movies', 'kind': 'movies', 'path': str(library / 'movies')})
                wait_for('the fixture movies', lambda: len(request(base, f'/api/v1/libraries/{created["id"]}/items?limit=500')['items']) == titles, 120)
            if short:
                created = request(base, '/api/v1/admin/libraries', 'POST', {'name': 'Short show', 'kind': 'shows', 'path': str(library / 'short')})
                wait_for('the short show', lambda: request(base, f'/api/v1/libraries/{created["id"]}/items')['items'], 60)
            yield base
        finally:
            if server.poll() is None:
                os.killpg(server.pid, signal.SIGTERM)
                server.wait(timeout=15)
            server_log.close()
            fixture.shutdown()


def virtual_channel(base):
    """Adds a virtual channel to the lineup that airs Sofa Sports live from the start of this hour, its live slot, and
    the Drama genre for the rest of the day, then adopts it."""
    sports = next(c['id'] for c in request(base, '/api/v1/channels')['channels'] if c['name'] == NAMES[1])
    hour = datetime.now().astimezone().replace(minute=0, second=0, microsecond=0)
    slot = lambda kind, value, start: {'source': {'kind': kind, 'value': value}, 'shuffle': False, 'start': start.strftime('%H:%M')}
    request(base, '/api/v1/admin/virtual-channels', 'POST', {
        'name': 'Sofa Live', 'number': '20', 'padMinutes': 0,
        'slots': [slot('channel', sports, hour), slot('genre', 'Drama', hour + timedelta(hours=1))]})
    # The virtual provider lists the new channel once it has imported again.
    wait_for('the virtual channel', lambda: request(base, '/api/v1/admin/lineup/adopt', 'POST', {'providerId': 'virtual'})['added'], 60)


def stop(*_):
    """Stopping the script stops the server too."""
    raise SystemExit(0)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--out', required=True)
    parser.add_argument('--shows', action='store_true', help='also serve a TV shows library whose episodes have an intro')
    parser.add_argument('--movies', action='store_true', help='also serve a Movies library of 84 titles with genres and a trailer')
    parser.add_argument('--quiet', action='store_true', help="silence the channels' tone")
    parser.add_argument('--virtual', action='store_true', help='also put a virtual channel with a live slot in the lineup')
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    ready = out / 'server.env'
    ready.unlink(missing_ok=True)
    signal.signal(signal.SIGTERM, stop)
    with serve(out, shows=args.shows, movies_library=args.movies, quiet=args.quiet, virtual=args.virtual) as base:
        ready.write_text(f'SOFAKING_SERVER={base}\nSOFAKING_TOKEN={TOKEN}\n')
        print(f'Serving {base}', flush=True)
        try:
            while True:
                time.sleep(3600)
        except KeyboardInterrupt:
            pass


if __name__ == '__main__':
    main()
