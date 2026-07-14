#!/bin/sh
# ADFA-4128 — sweep JVM (heap/GC) + kotlinc flags for a FIXED 617-line Main.kt, on-device.
# Answers "can we speed the compile up with settings, holding file contents constant?"
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"; SPIKE="$(cd "$HERE/../.." && pwd)"
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
SERIAL="${SERIAL:-RZGYC24640P}"; ADB="adb -s $SERIAL"; PKG=com.itsaky.androidide

APPHOME=$($ADB shell run-as $PKG pwd | tr -d '\r')
JDK="$APPHOME/files/usr/lib/jvm/java-21-openjdk"
AJ="$APPHOME/files/home/android-sdk/platforms/android-36/android.jar"
STDLIB="$APPHOME/files/mstc/lib/kotlin-stdlib-2.0.21.jar"
SRC="$APPHOME/files/mstc/RealMain.kt"   # the 617-line game Main.kt (pushed earlier)
WORK="$APPHOME/files/mstc/cfg-work"
CP="files/mstc/classes:files/mstc/lib/*"

$ADB push "$SPIKE/compile-service/incremental/FixedBench.class" /data/local/tmp/FixedBench.class >/dev/null
$ADB shell run-as $PKG cp /data/local/tmp/FixedBench.class files/mstc/classes/FixedBench.class
$ADB shell run-as $PKG mkdir -p files/mstc/cfg-work
# confirm the fixed src is the 617-line file
echo "fixed src LoC: $($ADB shell run-as $PKG wc -l "$SRC" | tr -d '\r')"

run() { # $1=label  $2=jvmflags  $3..=kotlincflags
  label="$1"; jvm="$2"; shift 2
  line=$($ADB shell run-as $PKG "$JDK/bin/java" $jvm \
      --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED \
      -cp "$CP" FixedBench "$WORK" "$STDLIB" "$AJ" "$SRC" "$@" 2>&1 | grep '^RESULT' | tr -d '\r')
  printf "%-26s %s\n" "$label" "$line"
}

echo "===== JVM heap / GC sweep (baseline kotlinc flags) ====="
run "xmx512m (current)"   "-Xmx512m"
run "xmx1g"               "-Xmx1g"
run "xmx2g"               "-Xmx2g"
run "xmx2g +Parallel"     "-Xmx2g -XX:+UseParallelGC"
run "xmx2g +Serial"       "-Xmx2g -XX:+UseSerialGC"
run "xmx2g Xms2g"         "-Xmx2g -Xms2g"
run "xmx512m +Parallel"   "-Xmx512m -XX:+UseParallelGC"

echo "===== kotlinc flag sweep (xmx2g) ====="
run "baseline"            "-Xmx2g"
run "no-assertions"       "-Xmx2g"   -Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions
run "backend-threads=4"   "-Xmx2g"   -Xbackend-threads=4
run "lang-version 1.9"    "-Xmx2g"   -language-version 1.9
run "no-optim-callref"    "-Xmx2g"   -Xno-optimized-callable-references
run "all-fast combo"      "-Xmx2g -XX:+UseParallelGC"  -Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions
