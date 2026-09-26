#!/usr/bin/env bash
# Puts sherpa-onnx's prebuilt Android libraries (arm64-v8a) in
# app/src/main/jniLibs/, where Gradle packs them into the APK. They are not
# committed (.gitignore), and remote-build.sh never syncs over them.
#
# The version must match the Kotlin bindings vendored in
# app/src/main/kotlin/com/k2fsa/sherpa/onnx/: bump both together.
#
# The tarball is cached in ~/android/downloads; its checksum is recorded in
# ~/android/checksums.txt the first time, and a later download that differs
# stops the build.
set -euo pipefail

VERSION="1.12.28"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/jniLibs/arm64-v8a"
STAMP="$ROOT/app/src/main/jniLibs/.sherpa-onnx-version"

if [ "$(cat "$STAMP" 2>/dev/null)" = "$VERSION" ]; then
  exit 0
fi

BASE="$HOME/android"
NAME="sherpa-onnx-v$VERSION-android.tar.bz2"
TARBALL="$BASE/downloads/$NAME"
SUMS="$BASE/checksums.txt"
mkdir -p "$BASE/downloads"
touch "$SUMS"

[ -s "$TARBALL" ] || curl -fL --retry 3 -o "$TARBALL" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/$NAME"
sum=$(sha256sum "$TARBALL" | cut -d' ' -f1)
known=$(awk -v n="$NAME" '$2 == n { print $1 }' "$SUMS")
if [ -z "$known" ]; then
  echo "$sum $NAME" >>"$SUMS"
  echo "fetch-sherpa-onnx: recorded $NAME sha256 $sum"
elif [ "$known" != "$sum" ]; then
  echo "fetch-sherpa-onnx: $NAME does not match its recorded checksum ($known), stopping" >&2
  exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
tar -xjf "$TARBALL" -C "$tmp"
src=$(find "$tmp" -type d -path '*jniLibs/arm64-v8a' | head -1)
[ -n "$src" ] || { echo "fetch-sherpa-onnx: no jniLibs/arm64-v8a in $NAME" >&2; exit 1; }
rm -rf "$DEST"
mkdir -p "$DEST"
cp "$src"/*.so "$DEST/"
echo "$VERSION" >"$STAMP"
echo "fetch-sherpa-onnx: $(ls "$DEST"/*.so | wc -l) libraries for v$VERSION in app/src/main/jniLibs/arm64-v8a"
