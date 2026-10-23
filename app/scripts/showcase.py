#!/usr/bin/env python3
"""A showcase lineup for screenshots: twelve made-up channels in four groups with a day of made-up programs, their logos
and program images, and a Movies and a TV shows library, all cut from the Blender Foundation's open movies (Big Buck
Bunny, Elephants Dream, Sintel, Tears of Steel and Caminandes, each CC BY), so a screenshot holds no real server's
lineup, address or token. fixture_server.serve takes it as its build.

prepare() downloads the movies' excerpts once into a cache folder (about 300 MB of reads, ranges only where the host
allows them) and encodes them; later runs reuse it. Needs FFmpeg with drawtext for the logos' and posters' lettering.
"""
import functools
import hashlib
import json
import os
from pathlib import Path
import random
import shutil
import subprocess
import time
from datetime import datetime, timedelta, timezone
from xml.sax.saxutils import escape

CACHE = Path(os.environ.get('SOFAKING_SHOWCASE_CACHE') or Path.home() / '.cache/sofa-king-showcase')
ARCHIVE = 'https://archive.org/download'
BLENDER = 'https://download.blender.org/demo/movies'

# Each movie: where it is, its length in minutes, and its credits for the library's .nfo.
MOVIES = {
    'bbb': dict(url=f'{ARCHIVE}/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4', title='Big Buck Bunny', year=2008,
                runtime=10, director='Sacha Goedegebure', genres=['Animation', 'Comedy', 'Family'],
                plot='A giant rabbit with a heart bigger than himself spends a lazy day in the meadow, until three rodents '
                     'pick on the wrong bunny and he plans a comeback worthy of a cartoon.'),
    'ed': dict(url=f'{ARCHIVE}/ElephantsDream/ed_hd.mp4', title='Elephants Dream', year=2006, runtime=11,
               director='Bassam Kurdali', genres=['Animation', 'Science Fiction'], cast=['Tygo Gernandt', 'Cas Jansen'],
               plot='Two men explore a vast machine of a world: Proog shows the young Emo its wonders, and Emo starts to '
                    'doubt that any of it is what Proog says it is.'),
    'sintel': dict(url=f'{BLENDER}/Sintel.2010.1080p.mkv', title='Sintel', year=2010, runtime=15, director='Colin Levy',
                   genres=['Animation', 'Fantasy', 'Adventure'], cast=['Halina Reijn', 'Thom Hoffman'],
                   plot='A lonely young woman nurses a wounded baby dragon back to health. When he is taken from her, she '
                        'crosses snowfields, deserts and swamps to find him again.'),
    'tos': dict(url=f'{BLENDER}/ToS/tears_of_steel_720p.mov', title='Tears of Steel', year=2012, runtime=12, director='Ian Hubert',
                genres=['Science Fiction', 'Action'], cast=['Derek de Lint', 'Sergio Hasselbaink', 'Rogier Schippers'],
                plot='In a future Amsterdam overrun by robots, a band of warriors and scientists stage one last '
                     'experiment: to win back a breakup that set it all in motion.'),
}
EPISODES = [
    dict(url=f'{ARCHIVE}/Caminandes1LlamaDrama/01_llama_drama_1080p.mp4', title='Llama Drama', aired='2013-01-12',
         plot='Koro the llama wants the grass on the other side of the road, and the road has other ideas.'),
    dict(url=f'{ARCHIVE}/Caminandes2GranDillama/02_gran_dillama_1080p.mp4', title='Gran Dillama', aired='2013-11-22',
         plot='Koro tries every trick he knows to reach the greener pasture behind an electric fence.'),
    dict(url=f'{ARCHIVE}/CaminandesLlamigos/Caminandes_%20Llamigos-1080p.mp4', title='Llamigos', aired='2016-03-08',
         plot='Winter in Patagonia: Koro meets Oti, a pesky penguin with an eye on the same red berries.'),
]
SHOW = dict(title='Caminandes', year=2013, director='Pablo Vazquez', genres=['Animation', 'Comedy', 'Family'],
            plot='Koro the llama roams the Patagonian plains in a series of short comedies, always one fence or one '
                 'penguin away from what he wants.')

# The lineup: number, name, group, the movie and second it plays from, the logo's colors and the program titles.
CHANNELS = [
    (2, 'Sofa King Cinema', 'Movies', 'sintel', 230, '#FFB547', '#14111F'),
    (3, 'Bunny Hop', 'Kids', 'bbb', 70, '#8BD17C', '#10301A'),
    (4, 'Steel City', 'Sci-Fi', 'tos', 300, '#7FB2FF', '#0B1A33'),
    (5, 'Dreamscape', 'Movies', 'ed', 150, '#C9B8FF', '#21173D'),
    (6, 'Llama Land', 'Kids', 'llama2', 20, '#FFD36E', '#3A2A05'),
    (7, 'Popcorn Classics', 'Movies', 'sintel', 520, '#FF7A6E', '#3A0E0A'),
    (8, 'Toon Junction', 'Kids', 'bbb', 330, '#6EE0FF', '#05303A'),
    (9, 'Future Shock', 'Sci-Fi', 'tos', 470, '#B6F36A', '#1D2E05'),
    (10, 'High Trails', 'Nature', 'llama3', 40, '#9AD3BC', '#0F2E24'),
    (11, 'Art House', 'Movies', 'ed', 420, '#F5A3D7', '#3A0F2C'),
    (12, 'Matinee', 'Movies', 'sintel', 760, '#FFE08A', '#2E2405'),
    (13, 'Wild Meadows', 'Nature', 'bbb', 470, '#A7E07A', '#1B2E0C'),
]
CLIP_SECONDS = 60

# Programs by group: title, episode titles (or none for a movie), category, rating, description.
PROGRAMS = {
    'Movies': [
        ('Sintel', None, 'Movie', 'TV-PG', MOVIES['sintel']['plot']),
        ('Elephants Dream', None, 'Movie', 'TV-PG', MOVIES['ed']['plot']),
        ('Tears of Steel', None, 'Movie', 'TV-14', MOVIES['tos']['plot']),
        ('The Lighthouse Keeper', None, 'Movie', 'TV-PG', 'A keeper on a northern rock takes in a stranded sailor and '
         'the storm that came with him.'),
        ('Harbor Lights', None, 'Movie', 'TV-14', 'A harbor pilot and a night-shift reporter chase the same story '
         'through one long foggy night.'),
        ('A Winter Crossing', None, 'Movie', 'TV-PG', 'Two sisters drive a vintage camper across the Rockies to make '
         'their father\'s last wish come true.'),
        ('Northbound', None, 'Movie', 'TV-14', 'A freight train, a lost dog and a conductor with one run left before '
         'retirement.'),
    ],
    'Kids': [
        ('Big Buck Bunny', None, 'Children', 'TV-G', MOVIES['bbb']['plot']),
        ('Caminandes', ['Llama Drama', 'Gran Dillama', 'Llamigos'], 'Children', 'TV-G', SHOW['plot']),
        ('Meadow Friends', ['The Big Acorn', 'Rainy Day Races', 'Butterfly Parade', 'The Lost Kite'], 'Children', 'TV-Y',
         'Squirrel, Rabbit and their friends turn a day in the meadow into an adventure.'),
        ('Puddle Jumpers', ['Splash Down', 'The Great Mud Slide', 'Rainbow Hunt'], 'Children', 'TV-Y',
         'Three young frogs learn something new about the pond every rainy day.'),
        ('The Busy Burrow', ['Moving Day', 'The Tunnel Map', 'Snack Time'], 'Children', 'TV-Y7',
         'A family of moles builds the best burrow under the hill, one tunnel at a time.'),
    ],
    'Sci-Fi': [
        ('Tears of Steel', None, 'Movie', 'TV-14', MOVIES['tos']['plot']),
        ('Orbit Patrol', ['Dead Reckoning', 'Silent Running', 'The Long Burn', 'Ghost Signal'], 'Science fiction', 'TV-14',
         'The crew of a battered tug keeps the shipping lanes around Saturn clear, whatever drifts into them.'),
        ('The Signal', ['First Contact', 'Static', 'Echoes'], 'Science fiction', 'TV-14',
         'A radio astronomer hears a pattern in the noise, and the night shift will never be quiet again.'),
        ('Robot Garden', ['Seedlings', 'Overgrowth', 'Harvest'], 'Science fiction', 'TV-PG',
         'Engineers and botanists race to grow food on a station that was never meant to hold a garden.'),
    ],
    'Nature': [
        ('Caminandes', ['Llama Drama', 'Gran Dillama', 'Llamigos'], 'Nature', 'TV-G', SHOW['plot']),
        ('Wild Patagonia', ['The Steppe', 'Ice Fields', 'The Coast'], 'Nature', 'TV-G',
         'Guanacos, condors and penguins share the windswept end of South America.'),
        ('Meadow Life', ['Spring', 'Summer', 'Autumn'], 'Nature', 'TV-G',
         'A year in an English meadow, from the first bees to the last hedgehog of autumn.'),
        ('High Andes', ['Above the Clouds', 'Salt Flats'], 'Nature', 'TV-G',
         'Life at the edge of the sky, on the world\'s longest mountain range.'),
    ],
}


# What the cache holds: the sources, the lineup and how they are cut. Bump the first number after changing the encoding
# or the images.
VERSION = hashlib.sha256(json.dumps([2, MOVIES, EPISODES, SHOW, CHANNELS, CLIP_SECONDS]).encode()).hexdigest()[:12]


def ffmpeg(*args, timeout=600):
    """Runs FFmpeg; one reading from the web tries again, three times with longer waits, as the movies' hosts turn away a
    burst of requests for a while."""
    command = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', *map(str, args)]
    for wait in (30, 90, 240, None):
        try:
            return subprocess.run(command, check=True, timeout=timeout)
        except subprocess.CalledProcessError:
            if wait is None or not any(arg.startswith('https://') for arg in command):
                raise
            time.sleep(wait)


@functools.cache
def has_drawtext():
    filters = subprocess.run(['ffmpeg', '-hide_banner', '-filters'], capture_output=True, text=True).stdout
    return ' drawtext ' in filters


def lettering(text, size, color, y):
    """A drawtext filter for [text], centered across, or nothing where FFmpeg was built without it."""
    if not has_drawtext():
        return None
    text = text.replace('\\', '\\\\').replace(':', '\\:').replace("'", "’")
    return f"drawtext=text='{text}':font=Sans Bold:fontsize={size}:fontcolor={color}:x=(w-tw)/2:y={y}"


def chain(*filters):
    return ','.join(f for f in filters if f)


def source(movie):
    """A movie's or an episode's address by its key: a MOVIES key, or llama1 to llama3 for the episodes."""
    if movie.startswith('llama'):
        return EPISODES[int(movie[5:]) - 1]['url']
    return MOVIES[movie]['url']


def encode(url, start, seconds, target, height=720):
    """[seconds] of [url] from [start] as H.264 and AAC, [height] lines high, in [target]'s container."""
    ffmpeg('-ss', start, '-i', url, '-t', seconds, '-map', '0:v:0', '-map', '0:a:0?', '-vf', f'scale=-2:{height},fps=25',
           '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '25', '-maxrate', '3M', '-bufsize', '6M', '-g', '50',
           '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '128k', '-ac', '2', '-ar', '48000', target)


def segment(clip, folder):
    """[clip] cut into HLS segments of about two seconds in [folder], with their names and lengths in segments.json, for
    the fixture server's live playlists, which every player takes (the web and Apple players take no MPEG-TS)."""
    folder.mkdir(parents=True)
    ffmpeg('-i', clip, '-c', 'copy', '-f', 'hls', '-hls_time', 2, '-hls_list_size', 0, '-hls_segment_filename',
           folder / 'seg-%03d.ts', folder / 'index.m3u8')
    lines = (folder / 'index.m3u8').read_text().splitlines()
    segments = [(lines[i + 1], float(line[8:].rstrip(','))) for i, line in enumerate(lines) if line.startswith('#EXTINF:')]
    (folder / 'segments.json').write_text(json.dumps(segments))


def still(url, at, target, width=1280, height=720, *filters):
    """One frame of [url] at [at] seconds, cropped to fill [width] by [height]."""
    ffmpeg('-ss', at, '-i', url, '-frames:v', 1, '-vf',
           chain(f'scale={width}:{height}:force_original_aspect_ratio=increase', f'crop={width}:{height}', *filters),
           '-q:v', 3, target, timeout=120)


def prepare(cache=CACHE):
    """Downloads and encodes everything the showcase serves into [cache], once; a finished cache has `ready`."""
    cache = Path(cache) / VERSION
    if (cache / 'ready').exists():
        return cache
    shutil.rmtree(cache, ignore_errors=True)
    for folder in ('clips', 'live', 'images', 'logos', 'library'):
        (cache / folder).mkdir(parents=True)
    for number, name, group, movie, start, background, ink in CHANNELS:
        encode(source(movie), start, CLIP_SECONDS, cache / 'clips' / f'{number}.ts')
        segment(cache / 'clips' / f'{number}.ts', cache / 'live' / str(number))
        initials = ''.join(word[0] for word in name.split() if word[0].isalnum())[:3]
        ffmpeg('-f', 'lavfi', '-i', f'color=c={ink}:s=256x256', '-frames:v', 1, '-vf',
               chain(f'drawbox=x=12:y=12:w=232:h=232:color={background}:t=fill',
                     lettering(initials, 112 if len(initials) < 3 else 84, ink, '(h-th)/2')), cache / 'logos' / f'{number}.png')
    # Program images: frames from across each movie, so every program has one.
    picks = {'bbb': [60, 150, 260, 400, 520], 'ed': [90, 200, 330, 480], 'sintel': [120, 300, 450, 640, 790],
             'tos': [100, 240, 390, 520], 'llama1': [30, 60], 'llama2': [40, 110], 'llama3': [50, 120]}
    for movie, seconds in picks.items():
        for index, at in enumerate(seconds):
            still(source(movie), at, cache / 'images' / f'{movie}-{index}.jpg', 640, 360)

    movies = cache / 'library/Movies'
    for key, movie in MOVIES.items():
        folder = movies / f'{movie["title"]} ({movie["year"]})'
        folder.mkdir(parents=True)
        encode(movie['url'], 60, 180, folder / f'{folder.name}.mp4')
        still(movie['url'], picks[key][1], folder / 'poster.jpg', 600, 900,
              'drawbox=x=0:y=ih*0.72:w=iw:h=ih*0.28:color=black@0.55:t=fill', lettering(movie['title'], 58, 'white', 'h*0.80'))
        still(movie['url'], picks[key][2], folder / 'fanart.jpg')
        (folder / 'movie.nfo').write_text(nfo('movie', movie, runtime=movie['runtime']))
    show = cache / 'library/TV' / f'{SHOW["title"]} ({SHOW["year"]})'
    season = show / 'Season 01'
    season.mkdir(parents=True)
    still(EPISODES[2]['url'], 70, show / 'poster.jpg', 600, 900,
          'drawbox=x=0:y=ih*0.72:w=iw:h=ih*0.28:color=black@0.55:t=fill', lettering(SHOW['title'], 58, 'white', 'h*0.80'))
    still(EPISODES[1]['url'], 60, show / 'fanart.jpg')
    (show / 'tvshow.nfo').write_text(nfo('tvshow', SHOW))
    for number, episode in enumerate(EPISODES, 1):
        name = f'{SHOW["title"]} - S01E{number:02d} - {episode["title"]}'
        encode(episode['url'], 0, 600, season / f'{name}.mp4')
        (season / f'{name}.nfo').write_text(nfo('episodedetails', episode, season=1, episode=number, aired=episode['aired']))
    (cache / 'ready').write_text(json.dumps({'movies': list(MOVIES), 'episodes': len(EPISODES)}))
    return cache


def nfo(root, item, **fields):
    """A Kodi .nfo with [item]'s title, plot and credits and any further [fields]."""
    lines = [f'<{root}>', f'  <title>{escape(item["title"])}</title>', f'  <plot>{escape(item["plot"])}</plot>']
    if 'year' in item:
        lines.append(f'  <year>{item["year"]}</year>')
    lines += [f'  <{tag}>{escape(str(value))}</{tag}>' for tag, value in fields.items()]
    lines += [f'  <genre>{escape(genre)}</genre>' for genre in item.get('genres', [])]
    if 'director' in item:
        lines.append(f'  <director>{escape(item["director"])}</director>')
    lines.append('  <studio>Blender Foundation</studio>')
    lines += [f'  <actor><name>{escape(name)}</name></actor>' for name in item.get('cast', [])]
    return '\n'.join(lines + [f'</{root}>', ''])


def build(media, upstream, library, cache=None, images=None):
    """Fills the fixture server's [media] folder and its [library] folder from the prepared cache. The programs' images
    load from [images], the address the app reaches the fixture's files at (an Android emulator reaches the host as
    10.0.2.2), and the channels' logos from [upstream] through the server."""
    cache = Path(cache or prepare())
    images = images or upstream
    for path in (cache / 'images').iterdir():
        shutil.copy(path, media / path.name)
    for path in (cache / 'logos').iterdir():
        shutil.copy(path, media / f'logo-{path.name}')
    playlist = ['#EXTM3U']
    for number, name, group, *_ in CHANNELS:
        live = media / f'live-{number}'
        shutil.copytree(cache / 'live' / str(number), live)
        playlist += [f'#EXTINF:-1 tvg-id="ch{number}" tvg-chno="{number}" tvg-logo="{upstream}/logo-{number}.png" '
                     f'group-title="{group}",{name}', f'{upstream}/live/{number}.m3u8']
    (media / 'feed.m3u').write_text('\n'.join(playlist) + '\n')
    (media / 'guide.xml').write_text(guide(images, cache))
    shutil.copytree(cache / 'library', library, dirs_exist_ok=True)


def guide(images, cache):
    """A day of programs on every channel from the hour before now, in slots of 30 minutes to two hours, the same on
    each run within a day. The second program on each channel, the one on now, is the movie the channel plays."""
    start = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) - timedelta(hours=1)
    stamp = lambda t: t.strftime('%Y%m%d%H%M%S +0000')
    frames = {}
    for path in sorted((cache / 'images').glob('*.jpg')):
        frames.setdefault(TITLES[path.stem.split('-')[0]], []).append(path.stem)
    out = ['<tv>']
    for number, name, *_ in CHANNELS:
        out.append(f'<channel id="ch{number}"><display-name>{escape(name)}</display-name></channel>')
    for number, name, group, movie, *_ in CHANNELS:
        rng = random.Random(f'{start:%Y%m%d}-{number}')
        pool = PROGRAMS[group]
        own = next((program for program in pool if program[0] == TITLES[movie]), None)
        at = start - timedelta(minutes=rng.choice([0, 15, 30, 45]))
        count = 0
        while at < start + timedelta(hours=26):
            title, episodes, category, rating, description = own if count == 1 and own else rng.choice(pool)
            length = 30 if episodes or title in ('Big Buck Bunny',) else 90
            pictures = frames.get(title) or frames[TITLES[movie]]
            parts = [f'<programme channel="ch{number}" start="{stamp(at)}" stop="{stamp(at + timedelta(minutes=length))}">',
                     f'<title>{escape(title)}</title>']
            if episodes:
                episode = rng.randrange(len(episodes))
                season = 1 if title == 'Caminandes' else rng.randint(1, 3)
                parts += [f'<sub-title>{escape(episodes[episode])}</sub-title>',
                          f'<episode-num system="xmltv_ns">{season - 1}.{episode}.</episode-num>']
            parts += [f'<desc>{escape(description)}</desc>', f'<category>{escape(category)}</category>',
                      f'<icon src="{images}/{rng.choice(pictures)}.jpg"/>',
                      f'<rating system="VCHIP"><value>{rating}</value></rating>']
            if count == 1 and rng.random() < 0.5:
                parts.append('<new/>')
            out.append(''.join(parts) + '</programme>')
            at += timedelta(minutes=length)
            count += 1
    out.append('</tv>')
    return '\n'.join(out) + '\n'


# The movie behind each clip and image key, by its title in the guide.
TITLES = {'bbb': 'Big Buck Bunny', 'ed': 'Elephants Dream', 'sintel': 'Sintel', 'tos': 'Tears of Steel',
          'llama1': 'Caminandes', 'llama2': 'Caminandes', 'llama3': 'Caminandes'}
LIBRARIES = [('Movies', 'movies', 'Movies'), ('TV Shows', 'shows', 'TV')]


if __name__ == '__main__':
    print(prepare())
