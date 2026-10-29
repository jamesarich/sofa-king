"""Stages the documentation for the site: every Markdown page and docs image Git tracks, and any image a page draws with
an HTML <img>, at its repository path, plus the API reference page and the brand stylesheet, into build/docs-src for mkdocs.yml. Agent instruction files, .claude and .github stay out. Run it from
the repository root before `mkdocs build`."""

import pathlib
import posixpath
import re
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "docs-src"
SKIP_NAMES = {"AGENTS.md", "CLAUDE.md"}
IMAGES = (".png", ".jpg", ".jpeg", ".svg", ".gif", ".webp")
IMG_SRC = re.compile(r'<img src="([^":]+)"')
OPENAPI = ROOT / "backend/server/src/main/resources/api/openapi.json"


def tracked() -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, check=True, capture_output=True, text=True).stdout
    return out.splitlines()


def wanted(path: str) -> bool:
    if path.startswith((".github/", ".claude/")) or pathlib.PurePosixPath(path).name in SKIP_NAMES:
        return False
    return path.endswith(".md") or (path.startswith("docs/images/") and path.lower().endswith(IMAGES))


def main() -> None:
    shutil.rmtree(OUT, ignore_errors=True)
    files = tracked()
    pages = list(filter(wanted, files))
    # An image a page draws with an HTML <img>, such as the README's logo, wherever it lives.
    for page in [p for p in pages if p.endswith(".md")]:
        for src in IMG_SRC.findall((ROOT / page).read_text()):
            path = posixpath.normpath(posixpath.join(posixpath.dirname(page), src))
            if path in files and path not in pages:
                pages.append(path)
    for path in pages:
        target = OUT / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / path, target)
    api = OUT / "api"
    api.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(OPENAPI, api / "openapi.json")
    shutil.copyfile(ROOT / "docs/site/api.html", api / "index.html")
    styles = OUT / "stylesheets"
    styles.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / "docs/site/brand.css", styles / "brand.css")


if __name__ == "__main__":
    main()
