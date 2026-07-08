#!/bin/sh
# Shared toolchain env for the mini-stubby spike. Sourced by the build scripts.
SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
ADB="$SDK/platform-tools/adb"
# Canonical team JDK (flox jdk17) — see docs/process/learnings.md in the wrapper repo.
export JAVA_HOME="/Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/CodeOnTheGo/flox/local/.flox/run/aarch64-darwin.local.dev"
export PATH="$JAVA_HOME/bin:$BT:$SDK/platform-tools:$PATH"

SPIKE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$SPIKE_DIR/build"
mkdir -p "$BUILD_DIR"
