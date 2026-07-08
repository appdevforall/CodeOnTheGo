#!/bin/sh
# Run the mini-stubby devloop daemon (component A, ADFA-4128 phase 2).
# Usage: run_devloop.sh [--dry-run]
#   --dry-run  build on change but skip tools/deploy_payload.sh (no device access)
set -e

DEVLOOP_DIR="$(cd "$(dirname "$0")" && pwd)"
SPIKE_DIR_ARG="$(cd "$DEVLOOP_DIR/.." && pwd)"

# Shared toolchain (JAVA_HOME=flox jdk17, aapt2/adb on PATH, BT/PLATFORM vars).
# env.sh derives SPIKE_DIR from $0's parent dir — devloop/ is one level under the
# spike root just like tools/, so the derivation stays correct when sourced here.
. "$SPIKE_DIR_ARG/tools/env.sh"

D8_JAR="$BT/lib/d8.jar"
[ -f "$D8_JAR" ] || { echo "run_devloop: d8.jar not found at $D8_JAR" >&2; exit 1; }

OUT="$BUILD_DIR/devloop/daemon-classes"
SRC="$DEVLOOP_DIR/src/DevLoopDaemon.java"
CLS="$OUT/DevLoopDaemon.class"

# Stale-check compile: rebuild the daemon only when the source is newer.
if [ ! -f "$CLS" ] || [ "$SRC" -nt "$CLS" ]; then
  echo "run_devloop: compiling daemon..."
  mkdir -p "$OUT"
  javac -encoding UTF-8 -d "$OUT" "$SRC"
fi

exec java \
  -cp "$OUT:$D8_JAR" \
  -Dplatform.jar="$PLATFORM" \
  -Daapt2.bin="$BT/aapt2" \
  DevLoopDaemon "$SPIKE_DIR_ARG" "$@"
