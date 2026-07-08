#!/bin/sh
# Build + install the Mini-Stubby shell app (the ONE-TIME install).
set -e
. "$(dirname "$0")/env.sh"

HOST="$SPIKE_DIR/host"
OUT="$BUILD_DIR/host"
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/res"

echo "== aapt2 compile/link (host) =="
aapt2 compile --dir "$HOST/res" -o "$OUT/res.zip"
aapt2 link \
  --manifest "$HOST/AndroidManifest.xml" \
  -I "$PLATFORM" \
  --min-sdk-version 30 --target-sdk-version 34 \
  --version-code 1 --version-name spike \
  --java "$OUT/gen" \
  -o "$OUT/host-unsigned.apk" \
  "$OUT/res.zip"

echo "== javac =="
find "$HOST/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt" -Xlint:-options

echo "== d8 =="
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
d8 --lib "$PLATFORM" --min-api 30 --output "$OUT/dex" @"$OUT/classes.txt"

echo "== package + sign =="
cd "$OUT/dex" && zip -q -j "$OUT/host-unsigned.apk" classes.dex && cd - >/dev/null
zipalign -f 4 "$OUT/host-unsigned.apk" "$OUT/host-aligned.apk"
apksigner sign \
  --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --out "$BUILD_DIR/ministubby-host.apk" "$OUT/host-aligned.apk"

if [ -n "$SKIP_INSTALL" ]; then
  echo "OK: built $BUILD_DIR/ministubby-host.apk (SKIP_INSTALL set — not touching device)"
else
  echo "== install =="
  "$ADB" install -r "$BUILD_DIR/ministubby-host.apk"
  echo "OK: shell installed (this is the only install the user ever sees)"
fi
