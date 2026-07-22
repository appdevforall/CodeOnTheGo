#!/bin/bash
# WS-A1 convenience wrapper: run the host corpus matrix with all tool paths resolved.
# Usage: bash run_ws_a.sh [--apps a,b,c] [other run_matrix.py flags...]
set -euo pipefail
cd "$(dirname "$0")/../.."

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JAVAC="$(dirname "$(which javac)")/javac"

python3 quick-build/corpus/harness/run_matrix.py \
  --android-jar "$(find "$ANDROID_HOME/platforms" -maxdepth 1 -name 'android-*' | sort -V | tail -1)/android.jar" \
  --kotlin-stdlib "$(find quickbuild-daemon/build/daemon -maxdepth 1 -name 'kotlin-stdlib-*.jar' | sort -V | tail -1)" \
  --aapt2 "$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -name '*.*.*' | sort -V | tail -1)/aapt2" \
  --d8-jar "$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -name '*.*.*' | sort -V | tail -1)/lib/d8.jar" \
  --javac "$JAVAC" \
  --daemon-jar quickbuild-daemon/build/daemon/quickbuild-daemon.jar \
  "$@"
