#!/bin/sh
# ADFA-4128 — run LangBench (Java vs Kotlin full-compile vs file size) on the A56, using
# CoGo's bundled JDK 21 + the already-staged Kotlin toolchain jars under files/mstc/lib.
# Emits CSV rows (RESULT,...) captured to bench/langbench-ondevice.csv.
#
# Prereq: tools/ondevice/stage_ondevice.sh has run (files/mstc/{classes,lib} exist) and
# LangBench.class is compiled on the Mac.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SPIKE="$(cd "$HERE/../.." && pwd)"
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
SERIAL="${SERIAL:-RZGYC24640P}"
ADB="adb -s $SERIAL"
PKG="com.itsaky.androidide"

APPHOME=$($ADB shell run-as $PKG pwd | tr -d '\r')
JDK="$APPHOME/files/usr/lib/jvm/java-21-openjdk"
MSTC="$APPHOME/files/mstc"
STDLIB="$MSTC/lib/kotlin-stdlib-2.0.21.jar"

echo "== push LangBench.class into files/mstc/classes =="
$ADB push "$SPIKE/compile-service/incremental/LangBench.class" /data/local/tmp/LangBench.class >/dev/null
$ADB shell run-as $PKG cp /data/local/tmp/LangBench.class files/mstc/classes/LangBench.class
$ADB shell run-as $PKG mkdir -p files/mstc/langbench-work

echo "== run on device (bundled JDK 21, in-process, warm) =="
OUT="$SPIKE/bench/langbench-ondevice.csv"
mkdir -p "$SPIKE/bench"
$ADB shell run-as $PKG "$JDK/bin/java" -Xmx512m \
  --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED \
  -cp "files/mstc/classes:files/mstc/lib/*" \
  LangBench "$MSTC/langbench-work" "$STDLIB" 2>&1 | tee "$SPIKE/bench/langbench-ondevice.raw.txt"

echo "== extract CSV -> $OUT =="
grep '^RESULT,' "$SPIKE/bench/langbench-ondevice.raw.txt" | sed '1!{/^RESULT,lang,/d}' > "$OUT" || true
echo "device: $($ADB shell getprop ro.product.model | tr -d '\r')  soc=$($ADB shell getprop ro.hardware | tr -d '\r')"
echo "wrote $OUT:"; cat "$OUT"
