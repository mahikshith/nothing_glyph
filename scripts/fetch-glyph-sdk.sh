#!/usr/bin/env bash
# Downloads Nothing's Glyph Matrix SDK AAR into app/libs/ (gitignored).
# The SDK licence forbids redistribution, so it is never committed or copied anywhere else.
# Source: https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$here/app/libs"
name="glyph-matrix-sdk-2.0.aar"
url="https://raw.githubusercontent.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit/main/$name"
mkdir -p "$dest"
if [ -s "$dest/$name" ]; then echo "$name already present"; exit 0; fi
for i in 1 2 3 4 5; do
  if curl -fsSL --retry 3 -o "$dest/$name.tmp" "$url" && [ -s "$dest/$name.tmp" ]; then
    mv "$dest/$name.tmp" "$dest/$name"
    echo "Downloaded $name to $dest"
    exit 0
  fi
  echo "attempt $i failed, retrying" >&2; sleep 2
done
echo "Could not download $url" >&2
exit 1
