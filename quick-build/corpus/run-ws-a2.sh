#!/bin/bash
# WS-A2 convenience wrapper. Not part of the harness; local dev aid.
set -euo pipefail
cd "$(dirname "$0")/../.."
export ANDROID_HOME="$HOME/Android/Sdk"
FLOXBIN="$(pwd)/flox/local/.flox/run/aarch64-darwin.local.dev/bin"
DAEMON_DIR=quickbuild-daemon/build/daemon
python3 quick-build/corpus/harness/run_matrix.py \
  --android-jar "$ANDROID_HOME/platforms/android-36/android.jar" \
  --kotlin-stdlib "$DAEMON_DIR/kotlin-stdlib-2.3.0.jar" \
  --aapt2 "$ANDROID_HOME/build-tools/36.0.0/aapt2" \
  --d8-jar "$ANDROID_HOME/build-tools/36.0.0/lib/d8.jar" \
  --java-bin "$FLOXBIN/java" \
  --javac "$FLOXBIN/javac" \
  --daemon-jar "$DAEMON_DIR/quickbuild-daemon.jar" \
  "$@"
