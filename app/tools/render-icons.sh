#!/usr/bin/env bash
# Renders the app icons for the desktop packages and the web page from the crown-on-a-sofa mark, the same SVG
# the TV banner uses. Needs ImageMagick 7 (`magick`), or an ImageMagick 6 `convert` built with SVG and ICNS
# support. Runs from any directory; output lands in app/ui/icons and app/ui/src/webMain/resources.
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
if command -v magick >/dev/null; then im=magick; else im=convert; fi
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
# Renders the mark at one pixel size on a transparent square (web and Linux) or on the app's background (Apple).
render() {
  local out=$1 size=$2 background=${3:-none}
  "$im" -background "$background" -density 1024 "$work/logo.svg" -resize "${size}x${size}" \
    -gravity center -extent "${size}x${size}" -define png:exclude-chunk=all "$out"
}
mkdir -p ui/icons
render ui/icons/sofa-king.png 512
# Windows: one .ico holding every size the shell asks for.
for size in 16 24 32 48 64 128 256; do render "$work/ico-$size.png" "$size"; done
"$im" "$work"/ico-{16,24,32,48,64,128,256}.png ui/icons/sofa-king.ico
# macOS: ImageMagick derives the ICNS sizes from one 1024px image; macOS draws its own rounded mask over it.
render "$work/icns.png" 1024 '#14111F'
"$im" "$work/icns.png" ui/icons/sofa-king.icns
# Web: the SVG itself as the favicon, the PWA icons and the Apple touch icon, which iOS shows unmasked.
web=ui/src/webMain/resources
cp "$work/logo.svg" "$web/favicon.svg"
render "$web/icon-192.png" 192
render "$web/icon-512.png" 512
render "$web/apple-touch-icon.png" 180 '#14111F'
