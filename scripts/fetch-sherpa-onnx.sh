#!/usr/bin/env bash
# Puts sherpa-onnx's Android AAR (native libraries for every ABI plus the
# Kotlin API, com.k2fsa.sherpa.onnx.*) at app/libs/sherpa-onnx.aar, where
# app/build.gradle.kts picks it up. Not committed (.gitignore), and
# remote-build.sh never syncs over it.
#
# VERSION and SHA256 are the one place the sherpa-onnx version lives: bump
# them together, from the release page's asset digest. A download that doesn't
# match SHA256 stops the build.
#
# The AAR is cached in ~/android/downloads.
set -euo pipefail

VERSION="1.13.8"
SHA256="633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/libs/sherpa-onnx.aar"
STAMP="$ROOT/app/libs/.sherpa-onnx-version"

if [ "$(cat "$STAMP" 2>/dev/null)" = "$VERSION $SHA256" ] && [ -s "$DEST" ]; then
  exit 0
fi

NAME="sherpa-onnx-$VERSION.aar"
CACHED="$HOME/android/downloads/$NAME"
mkdir -p "$(dirname "$CACHED")" "$(dirname "$DEST")"

if [ ! -s "$CACHED" ]; then
  curl -fL --retry 3 -o "$CACHED.part" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/$NAME"
  mv "$CACHED.part" "$CACHED"
fi
sum=$(sha256sum "$CACHED" | cut -d' ' -f1)
if [ "$sum" != "$SHA256" ]; then
  echo "fetch-sherpa-onnx: $NAME has sha256 $sum, expected $SHA256; stopping" >&2
  rm -f "$CACHED"
  exit 1
fi

cp "$CACHED" "$DEST"
echo "$VERSION $SHA256" >"$STAMP"
echo "fetch-sherpa-onnx: $NAME (sha256 ok) in app/libs/sherpa-onnx.aar"
