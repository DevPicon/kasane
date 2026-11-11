#!/bin/bash
set -euo pipefail

# Rasterize the original vector and build the macOS icon without changing its pixels by hand.
root=$(cd "$(dirname "$0")/.." && pwd)
command -v rsvg-convert >/dev/null || { echo 'Install librsvg (rsvg-convert) to regenerate the icon.' >&2; exit 1; }
command -v iconutil >/dev/null || { echo 'Regeneration requires macOS iconutil.' >&2; exit 1; }
temp=$(mktemp -d)
trap 'rm -rf "$temp"' EXIT
iconset="$temp/Hub.iconset"
mkdir -p "$iconset"
for size in 16 32 128 256 512; do
  rsvg-convert -w "$size" -h "$size" "$root/assets/icons/hub.svg" -o "$iconset/icon_${size}x${size}.png"
  doubled=$((size * 2))
  rsvg-convert -w "$doubled" -h "$doubled" "$root/assets/icons/hub.svg" -o "$iconset/icon_${size}x${size}@2x.png"
done
iconutil -c icns "$iconset" -o "$root/assets/icons/hub.icns"
cp "$iconset/icon_256x256.png" "$root/src/main/resources/hub-icon.png"
