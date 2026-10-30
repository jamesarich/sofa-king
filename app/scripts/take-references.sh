#!/usr/bin/env bash
# Copies the screenshot references rendered by a CI run over app/androidApp/src/screenshotTestDefaultDebug/reference.
# The Linux render is the one CI compares against, so a reference taken anywhere else fails there.
#
# Usage: app/scripts/take-references.sh [--repo OWNER/NAME] <run-id>
set -euo pipefail

repo=""
run=""
while [ $# -gt 0 ]; do
  case "$1" in
    --repo)
      [ $# -ge 2 ] || { echo "--repo needs OWNER/NAME" >&2; exit 2; }
      repo="$2"
      shift 2
      ;;
    -h | --help)
      sed -n '2,5p' "$0"
      exit 0
      ;;
    -*)
      echo "unknown option: $1" >&2
      exit 2
      ;;
    *)
      [ -z "$run" ] || { echo "one run id only" >&2; exit 2; }
      run="$1"
      shift
      ;;
  esac
done
[ -n "$run" ] || { echo "usage: take-references.sh [--repo OWNER/NAME] <run-id>" >&2; exit 2; }

root="$(git rev-parse --show-toplevel)"
dest="$root/app/androidApp/src/screenshotTestDefaultDebug/reference"
[ -d "$dest" ] || { echo "no reference directory at $dest" >&2; exit 1; }
[ -n "$repo" ] || repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

gh run download "$run" --repo "$repo" -n reports-linux -D "$tmp"

rendered="$tmp/androidApp/build/intermediates/debug/testDebugScreenshotTestDefaultTestSuite/results/rendered"
[ -d "$rendered" ] || { echo "run $run uploaded no rendered images (reports-linux is uploaded when the job fails)" >&2; exit 1; }

count=0
while IFS= read -r -d '' file; do
  rel="${file#"$rendered"/}"
  mkdir -p "$dest/$(dirname "$rel")"
  cp "$file" "$dest/$rel"
  echo "copied $rel"
  count=$((count + 1))
done < <(find "$rendered" -name '*.png' -print0 | sort -z)

[ "$count" -gt 0 ] || { echo "no PNGs under $rendered" >&2; exit 1; }
echo
echo "$count references copied to ${dest#"$root"/}."
echo "Open each changed image and look at it before you commit: a reference records whatever rendered, right or wrong."
