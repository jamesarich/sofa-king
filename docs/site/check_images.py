"""Checks the images the Markdown pages draw. Every tracked page's local `<img src>` and `![](path)` must name a file
Git tracks; MkDocs checks only Markdown links, so a missing image otherwise ships as a broken picture. A file under
docs/images that no page draws is a warning, not a failure: the screenshots job adds images ahead of the prose that
shows them. Run it from anywhere; it exits 1 on a missing file."""

import pathlib
import posixpath
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
IMG_SRC = re.compile(r"""<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""", re.IGNORECASE | re.DOTALL)
MARKDOWN_IMAGE = re.compile(r"!\[[^\]]*\]\(\s*<?([^)\s>]+)")
CODE = re.compile(r"^```.*?^```|`[^`\n]*`", re.DOTALL | re.MULTILINE)
REMOTE =("http://", "https://", "data:", "mailto:", "//", "#")


def tracked() -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, check=True, capture_output=True, text=True).stdout
    return out.splitlines()


def references(page: str) -> list[str]:
    """The repository paths of the local images [page] draws."""
    text = CODE.sub("", (ROOT / page).read_text(encoding="utf-8"))
    found = []
    for src in IMG_SRC.findall(text) + MARKDOWN_IMAGE.findall(text):
        if src.startswith(REMOTE):
            continue
        src = re.split(r"[?#]", src)[0]
        found.append(posixpath.normpath(src.lstrip("/") if src.startswith("/") else posixpath.join(posixpath.dirname(page), src)))
    return found


def main() -> int:
    files = tracked()
    present = set(files)
    drawn: set[str] = set()
    missing = []
    for page in (f for f in files if f.endswith(".md")):
        for path in references(page):
            drawn.add(path)
            if path not in present:
                missing.append((page, path))
    for page, path in missing:
        print(f"::error file={page}::{path} is not a tracked file")
    unused = sorted(f for f in files if f.startswith("docs/images/") and f not in drawn)
    for path in unused:
        print(f"::warning file={path}::no page draws this image")
    print(f"{len(drawn)} images drawn, {len(missing)} missing, {len(unused)} in docs/images that no page draws")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
