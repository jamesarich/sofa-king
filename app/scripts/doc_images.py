#!/usr/bin/env python3
"""Turns the screenshots job's PNGs into the documentation's WebP images and finds the ones that changed.

doc-images.json maps each PNG name screenshots.py writes to the file under docs/images it becomes and its width in
pixels. Needs ImageMagick (`magick`, or `convert` and `compare` on version 6) with WebP support.

- convert --src DIR --out DIR: finds each mapped PNG anywhere under --src and writes its WebP to --out at QUALITY. A
  mapped PNG that is missing is reported and skipped, so a platform whose job failed leaves its images alone.
- changes --new DIR --old DIR [--copy] [--body FILE]: lists the WebP files in --new that are not in --old, or that
  differ from it in more than CHANGED of their pixels (each channel allowed to move FUZZ, so the encoder's noise and
  anti-aliasing do not count). With --copy it copies them over --old; with --body it writes a Markdown table for a pull
  request. Prints the number of changed files.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys

MAPPING = Path(__file__).with_name('doc-images.json')
# The existing images carry no record of their quality; 80 matches their size.
QUALITY = 80
FUZZ = '2%'
CHANGED = 0.005


def magick(tool, *args):
    """Runs an ImageMagick tool, `magick <tool>` on version 7 and `<tool>` on 6; [tool] is convert, compare or identify."""
    command = ['magick', tool] if shutil.which('magick') else [tool]
    return subprocess.run([*command, *args], capture_output=True, text=True)


def convert(src, out):
    mapping = json.loads(MAPPING.read_text())
    out.mkdir(parents=True, exist_ok=True)
    found = {path.name: path for path in src.rglob('*.png')}
    done = 0
    for png, target in mapping.items():
        if png not in found:
            print(f'::notice::{png} was not rendered')
            continue
        run = magick('convert', str(found[png]), '-resize', f'{target["width"]}x', '-quality', str(QUALITY), str(out / target['webp']))
        if run.returncode:
            raise SystemExit(f'Could not convert {png}: {run.stderr}')
        done += 1
    print(f'{done} of {len(mapping)} images converted')
    if not done:
        raise SystemExit('No mapped image was rendered')


def size(path):
    return tuple(map(int, magick('identify', '-format', '%w %h', str(path)).stdout.split()[:2]))


def differs(new, old):
    """The share of pixels that differ, or 1 when the pictures differ in size."""
    if size(new) != size(old):
        return 1.0
    run = magick('compare', '-metric', 'AE', '-fuzz', FUZZ, str(new), str(old), 'null:')
    if run.returncode > 1:
        raise SystemExit(f'Could not compare {new.name}: {run.stderr}')
    width, height = size(new)
    return float(run.stderr.split()[0]) / (width * height)


def changes(new, old, copy, body):
    rows = []
    for image in sorted(new.glob('*.webp')):
        target = old / image.name
        if not target.exists():
            rows.append((image, 'new', None))
        elif (share := differs(image, target)) > CHANGED:
            rows.append((image, 'changed', share))
    for image, _, _ in rows:
        if copy:
            shutil.copyfile(image, old / image.name)
    if body:
        lines = ['| Image | Change |', '| --- | --- |']
        for image, kind, share in rows:
            lines.append(f'| `docs/images/{image.name}` | {"new" if kind == "new" else f"{share:.1%} of pixels"} |')
        body.write_text('\n'.join(lines) + '\n')
    print(len(rows))
    for image, kind, share in rows:
        print(f'{image.name}: {kind}' + ('' if share is None else f' ({share:.1%})'), file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest='command', required=True)
    to_webp = commands.add_parser('convert')
    to_webp.add_argument('--src', required=True, type=Path)
    to_webp.add_argument('--out', required=True, type=Path)
    find = commands.add_parser('changes')
    find.add_argument('--new', required=True, type=Path)
    find.add_argument('--old', required=True, type=Path)
    find.add_argument('--copy', action='store_true')
    find.add_argument('--body', type=Path)
    args = parser.parse_args()
    if args.command == 'convert':
        convert(args.src, args.out)
    else:
        changes(args.new, args.old, args.copy, args.body)


if __name__ == '__main__':
    main()
