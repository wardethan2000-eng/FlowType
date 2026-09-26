#!/usr/bin/env bash
# Downloads the debug APK GitHub Actions built for a commit (default: HEAD,
# which must be pushed) to out/flowtype-debug.apk, waiting for the run to
# finish. Then `scripts/remote-build.sh install` puts it on the phone.
#
#   scripts/ci-apk.sh [COMMIT]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sha=$(git -C "$ROOT" rev-parse "${1:-HEAD}")

run=""
for _ in $(seq 1 30); do
  run=$(gh run list --workflow android.yml --commit "$sha" --limit 1 --json databaseId --jq '.[0].databaseId // empty')
  [ -n "$run" ] && break
  sleep 5
done
[ -n "$run" ] || { echo "ci-apk: no android.yml run for $sha (pushed?)" >&2; exit 1; }

gh run watch "$run" --exit-status --interval 15 >/dev/null || {
  echo "ci-apk: run $run failed: gh run view $run --log-failed" >&2
  exit 1
}
rm -rf "$ROOT/out/ci" && mkdir -p "$ROOT/out/ci"
gh run download "$run" --name "flowtype-debug-$sha" --dir "$ROOT/out/ci"
mv "$ROOT/out/ci/app-debug.apk" "$ROOT/out/flowtype-debug.apk"
rmdir "$ROOT/out/ci"
echo "ci-apk: run $run → out/flowtype-debug.apk"
(cd "$ROOT" && ls -l out/flowtype-debug.apk && sha256sum out/flowtype-debug.apk)
