#!/usr/bin/env bash
# One-time setup of the Android toolchain on the build box, run through
# `scripts/remote-build.sh setup` (safe to run again: it skips what is there).
#
#   ~/android/jdk   JDK 17 (Eclipse Temurin), unless one is already there
#   ~/android/sdk   Android command-line tools, platforms 34 and 35, build tools
#
# Everything goes under ~/android, as the build user: no root, nothing in the
# system's paths, and deleting ~/android undoes all of it. About 1.5 GB here,
# plus 1-2 GB of Gradle caches in ~/.gradle after the first build.
#
# The SDK packages are pinned below; bump them together with compileSdk and
# targetSdk in app/build.gradle.kts. The download checksums are recorded in
# ~/android/checksums.txt the first time, and a later download that differs
# stops the script.
set -euo pipefail

BASE="$HOME/android"
JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
PACKAGES=(
  "platforms;android-34"
  "platforms;android-35"
  "build-tools;34.0.0"
  "build-tools;35.0.0"
  "platform-tools"
)

mkdir -p "$BASE/downloads"
sums="$BASE/checksums.txt"
touch "$sums"

# Downloads $1 to $2 and checks it against the checksum recorded for $2's name,
# recording it on first sight.
fetch() {
  local url="$1" file="$2" name sum known
  name=$(basename "$file")
  [ -s "$file" ] || curl -fL --retry 3 -o "$file" "$url"
  sum=$(sha256sum "$file" | cut -d' ' -f1)
  known=$(awk -v n="$name" '$2 == n { print $1 }' "$sums")
  if [ -z "$known" ]; then
    echo "$sum $name" >>"$sums"
    echo "setup: recorded $name sha256 $sum"
  elif [ "$known" != "$sum" ]; then
    echo "setup: $name does not match its recorded checksum ($known), stopping" >&2
    exit 1
  fi
}

if [ -x "$BASE/jdk/bin/java" ]; then
  echo "setup: JDK already at $BASE/jdk"
else
  fetch "$JDK_URL" "$BASE/downloads/temurin-17.tar.gz"
  rm -rf "$BASE/jdk.tmp" && mkdir -p "$BASE/jdk.tmp"
  tar -xzf "$BASE/downloads/temurin-17.tar.gz" -C "$BASE/jdk.tmp" --strip-components=1
  mv "$BASE/jdk.tmp" "$BASE/jdk"
fi
"$BASE/jdk/bin/java" -version 2>&1 | head -1

export JAVA_HOME="$BASE/jdk"
sdkmanager="$BASE/sdk/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$sdkmanager" ]; then
  echo "setup: command-line tools already installed"
else
  fetch "$TOOLS_URL" "$BASE/downloads/commandlinetools.zip"
  rm -rf "$BASE/tools.tmp" && mkdir -p "$BASE/tools.tmp" "$BASE/sdk/cmdline-tools"
  (cd "$BASE/tools.tmp" && "$JAVA_HOME/bin/jar" xf "$BASE/downloads/commandlinetools.zip")
  chmod +x "$BASE/tools.tmp/cmdline-tools/bin/"*
  rm -rf "$BASE/sdk/cmdline-tools/latest"
  mv "$BASE/tools.tmp/cmdline-tools" "$BASE/sdk/cmdline-tools/latest"
  rmdir "$BASE/tools.tmp"
fi

# `yes` dies of SIGPIPE when sdkmanager stops reading; under pipefail that
# would end the script, so its status is ignored.
{ yes || true; } | "$sdkmanager" --sdk_root="$BASE/sdk" --licenses >/dev/null
"$sdkmanager" --sdk_root="$BASE/sdk" --install "${PACKAGES[@]}"
"$sdkmanager" --sdk_root="$BASE/sdk" --list_installed

# Gradle's own settings for this user: no daemon (it would outlive each job),
# and a heap that leaves room for a DecalForge job in the queue's other slot.
mkdir -p "$HOME/.gradle"
if ! grep -q '^org.gradle.jvmargs' "$HOME/.gradle/gradle.properties" 2>/dev/null; then
  cat >>"$HOME/.gradle/gradle.properties" <<'EOF'
org.gradle.daemon=false
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m
org.gradle.workers.max=3
kotlin.daemon.jvmargs=-Xmx1g
EOF
  echo "setup: wrote ~/.gradle/gradle.properties"
fi

du -sh "$BASE" | sed 's/^/setup: ~\/android uses /'
echo "setup: done"
