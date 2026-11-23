#!/usr/bin/env bash
# Installs flowtype-clean on this Linux laptop (PLAN §7 Phase 5) from
# out/flowtype-clean.jar, which `scripts/remote-build.sh desktop` builds on the
# box. Copying a jar is the only thing done here: nothing is built.
#
#   ~/.local/share/flowtype/flowtype-clean.jar   the jar
#   ~/.local/bin/flowtype-clean                  runs it (stdin → stdout)
#
# Voxtype runs it after each dictation; in ~/.config/voxtype/config.toml:
#   [output.post_process]
#   command = "/home/<you>/.local/bin/flowtype-clean"   (absolute: the service has no ~/.local/bin on PATH)
#   timeout_ms = 4500
#
# The key goes in KDE Wallet, typed by you (it reads from the terminal):
#   secret-tool store --label='Flowtype (Anthropic)' service flowtype provider anthropic
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/out/flowtype-clean.jar"
[ -f "$JAR" ] || { echo "no $JAR: run scripts/remote-build.sh desktop first" >&2; exit 1; }
mkdir -p ~/.local/share/flowtype ~/.local/bin
install -m 644 "$JAR" ~/.local/share/flowtype/flowtype-clean.jar
rm -f ~/.cache/flowtype/cds.jsa   # built from the old jar; the first run makes a new one
cat > ~/.local/bin/flowtype-clean <<'SH'
#!/bin/sh
# Flowtype's cleanup for voxtype (installed by FlowType/scripts/install-desktop.sh).
# Small heap and quick start: it runs once per dictation and exits. The class
# archive in ~/.cache/flowtype (rebuilt by Java when the jar changes) takes
# start-up from about 0.55 s to 0.3 s.
mkdir -p "$HOME/.cache/flowtype"
exec java -Xlog:cds=off -Xlog:cds+dynamic=off -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile="$HOME/.cache/flowtype/cds.jsa" \
  -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xmx96m \
  -jar "$HOME/.local/share/flowtype/flowtype-clean.jar" "$@"
SH
chmod 755 ~/.local/bin/flowtype-clean
echo "installed: ~/.local/bin/flowtype-clean"
