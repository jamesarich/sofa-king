#!/usr/bin/env bash
# Builds Comskip from its pinned upstream commit with backend/comskip/ffmpeg-8.patch and installs it under PREFIX
# (default /usr/local), for Ubuntu releases that no longer package it. Usage: build-comskip.sh [PREFIX]
# Build dependencies (Debian and Ubuntu): build-essential autoconf automake libtool pkg-config libargtable2-dev
# libavformat-dev libavcodec-dev libavutil-dev libswscale-dev. The SDL viewer is left out.
set -euo pipefail

commit=a140b6ac8bc8f596729e9052819affc779c3b377
sha256=5563d65c0cc5f76883e26b9e460348b4826cc496d10f8d53b25e7648e47f87c2

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
patch_file="$here/../comskip/ffmpeg-8.patch"
prefix="${1:-/usr/local}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

curl -fsSL --retry 3 -o "$work/comskip.tar.gz" "https://github.com/erikkaashoek/Comskip/archive/$commit.tar.gz"
actual="$( (sha256sum "$work/comskip.tar.gz" 2>/dev/null || shasum -a 256 "$work/comskip.tar.gz") | cut -d' ' -f1)"
if [ "$actual" != "$sha256" ]; then
  echo "build-comskip: tarball checksum $actual does not match the pinned $sha256" >&2
  exit 1
fi

mkdir "$work/src"
tar -xzf "$work/comskip.tar.gz" -C "$work/src" --strip-components=1
cd "$work/src"
patch -p1 < "$patch_file"

autoreconf -fi
./configure --prefix="$prefix" --disable-gui
make -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)"
make install
