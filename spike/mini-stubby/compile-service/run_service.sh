#!/bin/sh
# Compile + run the persistent Kotlin compile service. Long-lived; Ctrl-C to stop.
#
# Kotlin toolchain: the version-matched 2.0.21 set (Build Tools API + compiler-embeddable)
# under compile-service/incremental/lib. Incremental compilation (the default) drives kotlinc
# through CompilationService; -Dinc=false falls back to the whole-app K2JVMCompiler path.
set -e
. "$(dirname "$0")/../tools/env.sh"

BTALIB="$SPIKE_DIR/compile-service/incremental/lib"
D8JAR="$BT/lib/d8.jar"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
STDLIB="$BTALIB/kotlin-stdlib-2.0.21.jar"
AAPT2="$BT/aapt2"

# Build the Kotlin classpath from the 2.0.21 jar set + d8.
KCP="$D8JAR"
for j in "$BTALIB"/*.jar; do KCP="$KCP:$j"; done

OUT="$SPIKE_DIR/build/compile-service"
mkdir -p "$OUT"
echo "== compiling service (+ IncrementalCompiler) =="
javac -proc:none -classpath "$KCP" -d "$OUT" \
  "$SPIKE_DIR/compile-service/src/IncrementalCompiler.java" \
  "$SPIKE_DIR/compile-service/src/KotlinCompileService.java"

echo "== running service (Ctrl-C to stop) =="
exec java -Xmx1500m --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED \
  -classpath "$OUT:$KCP" \
  -Daapt2="$AAPT2" ${DEPLOY:+-Ddeploy=$DEPLOY} ${TIER1:+-Dtier1=$TIER1} ${INC:+-Dinc=$INC} \
  KotlinCompileService \
  "$SPIKE_DIR" "$SPIKE_DIR/payload-kotlin" "$ANDROID_JAR" "$STDLIB" "$D8JAR"
