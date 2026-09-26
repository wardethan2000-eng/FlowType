#!/usr/bin/env bash
# Run the Android builds on the build box instead of this laptop.
#
#   scripts/remote-build.sh setup             one-time: JDK 17 + Android SDK on the box
#   scripts/remote-build.sh apk               debug APK, copied back to out/flowtype-debug.apk
#   scripts/remote-build.sh test [ARGS...]    JVM unit tests (ARGS go to gradle)
#   scripts/remote-build.sh install           adb install out/flowtype-debug.apk HERE, to the phone
#   scripts/remote-build.sh run -- CMD...     any command, in the synced tree
#   scripts/remote-build.sh fetch PATH...     copy paths from the box's tree back here
#   scripts/remote-build.sh queue             what the box is running, and what waits
#
# Why: the laptop has 7.4 GB, and a Gradle + Kotlin build wants 2-3 GB of its
# own. The build box is CT 142 `builder` on pve1 (6 cores, 8 GB), the same box
# and the same queue DecalForge's builds use.
#
# How: the working tree is rsynced to ~/work/<this checkout's folder name> on the
# box, the command runs there through the box's one queue
# (scripts/builder/queue-run, a copy of DecalForge's: every checkout of either
# repo waits in the same line, at most two jobs at once), and the APK comes back
# to out/. Git metadata, build outputs, Gradle's caches and the sherpa-onnx
# native libraries are never sent; the box keeps its own copies, so builds stay
# incremental.
#
# The box's toolchain lives under ~/android (scripts/builder/setup-android-sdk.sh
# puts it there): JDK 17, and the Android SDK platforms and build tools this
# project compiles against. Gradle itself comes from the wrapper.
#
# Debug APKs are signed with the box's ~/.android/debug.keystore. Keep that file:
# an APK signed with a different key will not install over the old one, and
# uninstalling to get past that drops the accessibility permission.
#
# Env: FLOWTYPE_BUILDER — the ssh host (default `decalforge-builder`, the alias
#      in ~/.ssh/config that jumps through pve1).
set -euo pipefail

HOST="${FLOWTYPE_BUILDER:-decalforge-builder}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NAME="$(basename "$ROOT")"
REMOTE="work/$NAME"
APK_REMOTE="app/build/outputs/apk/debug/app-debug.apk"
APK_LOCAL="out/flowtype-debug.apk"

usage() {
  sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

[ $# -ge 1 ] || usage
mode="$1"
shift

sync_up() {
  ssh -o BatchMode=yes "$HOST" "mkdir -p $REMOTE"
  # --delete keeps the copy an exact image of this tree, but never touches the
  # excluded paths on the far side: the build outputs there are what make
  # builds incremental, and jniLibs/ is what fetch-sherpa-onnx.sh unpacked.
  rsync -az --delete \
    --exclude='.git' \
    --exclude='.gradle/' \
    --exclude='.kotlin/' \
    --exclude='build/' \
    --exclude='app/build/' \
    --exclude='app/src/main/jniLibs/' \
    --exclude='local.properties' \
    --exclude='out/' \
    "$ROOT/" "$HOST:$REMOTE/"
}

# Runs the given command in the synced tree on the box, with the JDK and SDK
# on PATH, niced, through the box's one queue. No Gradle or Kotlin daemon: one
# would outlive the job and sit on a gigabyte or two of the box's memory.
# Arguments are passed through `printf %q`, so they arrive as they were typed.
remote() {
  local cmd
  cmd=$(printf '%q ' "$@")
  ssh -o BatchMode=yes "$HOST" "export JAVA_HOME=\$HOME/android/jdk ANDROID_HOME=\$HOME/android/sdk \
    GRADLE_OPTS='-Dorg.gradle.daemon=false -Dkotlin.compiler.execution.strategy=in-process' && \
    export PATH=\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:/usr/local/bin:\$PATH && \
    cd $REMOTE && exec bash scripts/builder/queue-run $NAME $mode $cmd"
}

started=$(date +%s)
case "$mode" in
  setup)
    sync_up
    ssh -o BatchMode=yes "$HOST" "bash $REMOTE/scripts/builder/setup-android-sdk.sh"
    ;;
  apk)
    sync_up
    remote bash -c 'scripts/fetch-sherpa-onnx.sh && ./gradlew --console=plain assembleDebug'
    mkdir -p "$ROOT/out"
    rsync -az "$HOST:$REMOTE/$APK_REMOTE" "$ROOT/$APK_LOCAL"
    echo "remote-build: APK copied back:"
    (cd "$ROOT" && ls -l "$APK_LOCAL" && sha256sum "$APK_LOCAL")
    ;;
  test)
    sync_up
    remote ./gradlew --console=plain testDebugUnitTest "$@"
    ;;
  install)
    # adb is light; it runs here, where the phone is (USB, or `adb pair` /
    # `adb connect` for wireless debugging).
    [ -f "$ROOT/$APK_LOCAL" ] || { echo "no $APK_LOCAL yet: run \`$0 apk\` first" >&2; exit 1; }
    adb install -r "$ROOT/$APK_LOCAL"
    ;;
  run)
    [ "${1:-}" = "--" ] && shift
    [ $# -ge 1 ] || usage
    sync_up
    remote "$@"
    ;;
  queue)
    sync_up
    ssh -o BatchMode=yes "$HOST" "bash $REMOTE/scripts/builder/queue-run --status"
    exit 0
    ;;
  fetch)
    [ $# -ge 1 ] || usage
    for path in "$@"; do
      path="${path%/}"
      mkdir -p "$ROOT/$(dirname "$path")"
      rsync -az "$HOST:$REMOTE/$path" "$ROOT/$(dirname "$path")/"
      echo "remote-build: fetched $path"
    done
    ;;
  *)
    usage
    ;;
esac
echo "remote-build: $mode finished in $(( $(date +%s) - started ))s on $HOST"
