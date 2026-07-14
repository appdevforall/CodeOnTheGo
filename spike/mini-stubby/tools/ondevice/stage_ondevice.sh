#!/bin/sh
# ADFA-4128 — stage the on-device compile daemon into a (debuggable) CoGo install.
#
# Run AFTER a debug CoGo (com.itsaky.androidide) is installed AND its first-run setup
# has finished (JDK under files/usr, SDK under files/home). This:
#   1. compiles KotlinCompileService on the Mac,
#   2. pushes the daemon classes + Kotlin jars into CoGo's private files/mstc,
#   3. discovers CoGo's on-device JDK + aapt2 + android.jar + d8.jar,
#   4. writes files/mstc/run_daemon.sh (CoGo's LiveReloadManager launches this).
#
# Notes on the device quirks:
#   - app home is /data/user/0/PKG (not /data/data/PKG).
#   - `run-as PKG sh -c '...'` starts at cwd=/  → use DIRECT `run-as PKG <cmd>` (cwd=app
#     home) for relative paths, and ABSOLUTE paths for anything inside `sh -c`.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/../env.sh"                         # SDK, BT, PLATFORM, ADB, JAVA_HOME, SPIKE_DIR
SPIKE_DIR="$(cd "$HERE/../.." && pwd)"      # env.sh mis-derives it for tools/ondevice/
PKG="com.itsaky.androidide"

echo "== 1. compile daemon (Mac) =="
BTALIB="$SPIKE_DIR/compile-service/incremental/lib"
KCP=""; for j in "$BTALIB"/*.jar; do KCP="$KCP:$j"; done
STAGE="$SPIKE_DIR/build/ondevice-stage"
rm -rf "$STAGE"; mkdir -p "$STAGE/classes" "$STAGE/lib"
"$JAVA_HOME/bin/javac" -proc:none -classpath "$KCP" -d "$STAGE/classes" \
  "$SPIKE_DIR/compile-service/src/IncrementalCompiler.java" \
  "$SPIKE_DIR/compile-service/src/KotlinCompileService.java"
cp "$BTALIB"/*.jar "$STAGE/lib/"            # kotlin toolchain jars (pure JVM, portable)

echo "== 2. discover CoGo's on-device toolchain =="
APPHOME=$($ADB shell run-as $PKG pwd | tr -d '\r')                    # /data/user/0/PKG
JDK="$APPHOME/files/usr/lib/jvm/java-21-openjdk"
AAPT2_REL=$($ADB shell run-as $PKG find files/home/android-sdk/build-tools -name aapt2 -type f 2>/dev/null | head -1 | tr -d '\r')
ANDROIDJAR_REL=$($ADB shell run-as $PKG find files/home/android-sdk/platforms -name android.jar 2>/dev/null | head -1 | tr -d '\r')
D8_REL=$($ADB shell run-as $PKG find files/home/android-sdk/build-tools -name d8.jar 2>/dev/null | head -1 | tr -d '\r')
[ -n "$AAPT2_REL" ] || { echo "!! aapt2 not found (setup incomplete?)"; exit 1; }
[ -n "$ANDROIDJAR_REL" ] || { echo "!! android.jar not found"; exit 1; }
[ -n "$D8_REL" ] || { echo "!! d8.jar not found"; exit 1; }
AAPT2="$APPHOME/$AAPT2_REL"; ANDROIDJAR="$APPHOME/$ANDROIDJAR_REL"; D8JAR="$APPHOME/$D8_REL"
echo "   JDK        = $JDK"
echo "   aapt2      = $AAPT2"
echo "   android.jar= $ANDROIDJAR"
echo "   d8.jar     = $D8JAR"

echo "== 3. push stage + copy into files/mstc (run-as) =="
$ADB shell rm -rf /data/local/tmp/mstc-stage
$ADB push "$STAGE" /data/local/tmp/mstc-stage >/dev/null
$ADB shell run-as $PKG rm -rf files/mstc
$ADB shell run-as $PKG cp -r /data/local/tmp/mstc-stage files/mstc   # direct: cwd=app home
$ADB shell run-as $PKG mkdir -p files/mstc/work

echo "== 4. write files/mstc/run_daemon.sh =="
cat > "$STAGE/run_daemon.sh" <<EOF
#!/system/bin/sh
# Launched by CoGo (LiveReloadManager) as the CoGo uid. \$1 = open project dir.
D="\$(cd "\$(dirname "\$0")" && pwd)"
PROJ="\${1:-/storage/emulated/0/CodeOnTheGoProjects/LiveReloadDemo}"
exec "$JDK/bin/java" -Xmx512m \\
  --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED \\
  -cp "\$D/classes:\$D/lib/*:$D8JAR" \\
  -Daapt2="$AAPT2" -Dserve=true -Dwatch=false \\
  -Dsrc="\$PROJ/app/src/main/java" -Dres="\$PROJ/app/src/main/res" \\
  -Dmanifest="\$PROJ/app/src/main/AndroidManifest.xml" -Dwork="\$D/work" \\
  KotlinCompileService "\$D" "\$PROJ" "$ANDROIDJAR" "\$D/lib/kotlin-stdlib-2.0.21.jar" "$D8JAR"
EOF
$ADB push "$STAGE/run_daemon.sh" /data/local/tmp/run_daemon.sh >/dev/null
$ADB shell run-as $PKG cp /data/local/tmp/run_daemon.sh files/mstc/run_daemon.sh
$ADB shell run-as $PKG chmod 755 files/mstc/run_daemon.sh

echo "== staged. contents: =="
$ADB shell run-as $PKG ls -la files/mstc
echo "--- run_daemon.sh ---"
$ADB shell run-as $PKG cat files/mstc/run_daemon.sh
