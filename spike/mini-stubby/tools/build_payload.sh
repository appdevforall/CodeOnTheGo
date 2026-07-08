#!/bin/sh
# Build the user-app payload (UNSIGNED, never installed) and hot-deploy it to
# the shell app. This simulates what CoGo would do on every file save.
set -e
. "$(dirname "$0")/env.sh"

PAYLOAD="$SPIKE_DIR/payload"
OUT="$BUILD_DIR/payload"
HOST_PKG="com.adfa.ministubby.host"
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

T0=$(python3 -c 'import time; print(int(time.time()*1000))')

echo "== aapt2 compile/link (payload, package-id 0x80) =="
aapt2 compile --dir "$PAYLOAD/res" -o "$OUT/res.zip"
aapt2 link \
  --manifest "$PAYLOAD/AndroidManifest.xml" \
  -I "$PLATFORM" \
  --package-id 0x80 \
  --min-sdk-version 30 --target-sdk-version 34 \
  --java "$OUT/gen" \
  -A "$PAYLOAD/assets" \
  -o "$OUT/payload-unsigned.apk" \
  "$OUT/res.zip"

echo "== javac + d8 =="
find "$PAYLOAD/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt" -Xlint:-options
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
d8 --lib "$PLATFORM" --min-api 30 --output "$OUT/dex" @"$OUT/classes.txt"
cd "$OUT/dex" && zip -q -j "$OUT/payload-unsigned.apk" classes.dex && cd - >/dev/null

T1=$(python3 -c 'import time; print(int(time.time()*1000))')

echo "== push (no install, no signing, no zipalign) =="
"$ADB" push "$OUT/payload-unsigned.apk" /data/local/tmp/ministubby-payload.apk >/dev/null
# Atomic-rename into the debuggable host's files dir so FileObserver fires
# exactly once, on a complete file. In real CoGo the IDE would hand the payload
# to the shell via a content URI + broadcast instead of run-as.
"$ADB" shell run-as "$HOST_PKG" sh -c \
  '"mkdir -p files/payload && cp /data/local/tmp/ministubby-payload.apk files/payload/.incoming.tmp && mv files/payload/.incoming.tmp files/payload/payload.apk"'

T2=$(python3 -c 'import time; print(int(time.time()*1000))')
echo "OK: build $((T1-T0)) ms, push $((T2-T1)) ms (Mac-side). Watch device: adb logcat -s MiniStubby"
