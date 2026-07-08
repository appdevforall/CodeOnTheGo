#!/bin/sh
# Compile + run the persistent Kotlin compile service. Long-lived; Ctrl-C to stop.
set -e
. "$(dirname "$0")/../tools/env.sh"

KLIB="/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc/lib"
D8JAR="$BT/lib/d8.jar"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
STDLIB="$KLIB/kotlin-stdlib.jar"
AAPT2="$BT/aapt2"

# Kotlin compiler + its runtime deps on the service classpath.
KCP="$KLIB/kotlin-compiler.jar:$KLIB/kotlin-stdlib.jar:$KLIB/kotlin-reflect.jar:$KLIB/kotlin-script-runtime.jar:$KLIB/annotations-13.0.jar:$KLIB/kotlinx-coroutines-core-jvm.jar:$D8JAR"

OUT="$SPIKE_DIR/build/compile-service"
mkdir -p "$OUT"
echo "== compiling service =="
javac -classpath "$KCP" -d "$OUT" "$SPIKE_DIR/compile-service/src/KotlinCompileService.java"

echo "== running service (Ctrl-C to stop) =="
exec java -Xmx1g -classpath "$OUT:$KCP" \
  -Daapt2="$AAPT2" ${DEPLOY:+-Ddeploy=$DEPLOY} ${TIER1:+-Dtier1=$TIER1} \
  KotlinCompileService \
  "$SPIKE_DIR" "$SPIKE_DIR/payload-kotlin" "$ANDROID_JAR" "$STDLIB" "$D8JAR"
