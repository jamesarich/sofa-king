"""MkDocs hook: a relative link from a page to a repository file the site does not carry, such as a script or a
workflow, becomes a link to that file on GitHub, so the Markdown keeps the links that work on GitHub."""

import posixpath
import re

REPO = "https://github.com/jamesarich/sofa-king/blob/main/"
LINK = re.compile(r"(\]\()([^)\s#]+)(#[^)\s]*)?(\))")


def on_page_markdown(markdown, page, config, files):
    base = posixpath.dirname(page.file.src_uri)

    def rewrite(match):
        target = match.group(2)
        if "://" in target or target.startswith(("mailto:", "/")):
            return match.group(0)
        path = posixpath.normpath(posixpath.join(base, target))
        if path.startswith("..") or files.get_file_from_path(path) is not None:
            return match.group(0)
        # A directory link names its README on the site when there is one.
        if files.get_file_from_path(posixpath.join(path, "README.md")) is not None:
            return f"{match.group(1)}{target.rstrip('/')}/README.md{match.group(3) or ''}{match.group(4)}"
        return f"{match.group(1)}{REPO}{path}{match.group(3) or ''}{match.group(4)}"

    return LINK.sub(rewrite, markdown)
