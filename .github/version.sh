#!/bin/sh
# Prints the build's version as GITHUB_OUTPUT lines: `version` is the catalog's `sofaking` plus `suffix`, which is
# -dev.<code>+<short commit>, and `code` is the number of commits behind HEAD. The app's snapshot and the server image
# run this, so both built from one commit carry one version, and the code grows with every merge to main. It needs the
# whole history (fetch-depth: 0); a shallow checkout counts one commit.
set -eu
root=$(dirname "$0")/..
base=$(sed -n 's/^sofaking = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")
code=$(git -C "$root" rev-list --count HEAD)
commit=$(git -C "$root" rev-parse --short=7 HEAD)
echo "code=$code"
echo "suffix=-dev.$code+$commit"
echo "version=$base-dev.$code+$commit"
