#!/usr/bin/env bash
# Renders the Google TV banner (320x180dp) with the app name, which a layer-list drawable cannot carry.
# Needs ImageMagick 7 and Noto Sans (OFL). Run from the repo root; output lands in androidApp/src/main/res.
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
bold=/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf
regular=/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf
cat > "$work/logo.svg" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" width="480" height="480" viewBox="0 0 120 120">
  <path fill="#B3A1EA" d="M22,46 Q22,40 28,40 L92,40 Q98,40 98,46 L98,70 L22,70 Z"/>
  <path fill="#C9B8FF" d="M10,62 Q10,58 14,58 L106,58 Q110,58 110,62 L110,92 L10,92 Z"/>
  <path fill="#C9B8FF" d="M6,58 Q6,52 12,52 L20,52 L20,96 L6,96 Z"/>
  <path fill="#C9B8FF" d="M100,52 L108,52 Q114,52 114,58 L114,96 L100,96 Z"/>
  <path fill="#9C8AD4" d="M20,72 L100,72 L100,75 L20,75 Z"/>
  <path fill="#9C8AD4" d="M14,96 L14,104 L20,104 L20,96 Z"/>
  <path fill="#9C8AD4" d="M100,96 L100,104 L106,104 L106,96 Z"/>
  <path fill="#FFB547" d="M36,46 L36,30 L48,38 L60,20 L72,38 L84,30 L84,46 Z"/>
  <path fill="#E0902A" d="M36,40 L84,40 L84,46 L36,46 Z"/>
  <path fill="#14111F" d="M60,42 L62,44 L60,46 L58,44 Z"/>
</svg>
SVG
# scale is the pixel size of one dp: xhdpi (the Chromecast with Google TV) is 2, mdpi is 1.
render() {
  local out=$1 scale=$2
  magick -background none -density 384 "$work/logo.svg" -resize "$((108 * scale))x$((108 * scale))" "$work/logo.png"
  magick -size "$((320 * scale))x$((180 * scale))" gradient:'#2A2440-#14111F' \
    "$work/logo.png" -gravity west -geometry "+$((28 * scale))+0" -composite \
    -font "$bold" -pointsize "$((31 * scale))" -fill '#F4EFFA' -gravity west -annotate "+$((146 * scale))-$((8 * scale))" 'Sofa King' \
    -font "$regular" -pointsize "$((10 * scale)).5" -fill '#C9B8FF' -annotate "+$((148 * scale))+$((19 * scale))" 'Live TV, right where you left it.' \
    -define png:exclude-chunk=all "$out"
}
render androidApp/src/main/res/drawable-xhdpi/banner.png 2
render androidApp/src/main/res/drawable-mdpi/banner.png 1
