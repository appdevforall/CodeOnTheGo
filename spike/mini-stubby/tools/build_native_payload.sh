#!/bin/sh
# Build a payload apk that bundles a native .so under lib/arm64-v8a/, deploy it.
# Proves native-library loading (ADFA-4128 ticket step 4).
set -e
. "$(dirname "$0")/env.sh"
P="$SPIKE_DIR/payload-native"
OUT="$BUILD_DIR/payload-native"
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

aapt2 compile --dir "$P/res" -o "$OUT/res.zip" 2>/dev/null || : # res may be empty
aapt2 link --manifest "$P/AndroidManifest.xml" -I "$PLATFORM" \
  --package-id 0x80 --allow-reserved-package-id \
  --min-sdk-version 30 --target-sdk-version 34 \
  --java "$OUT/gen" -o "$OUT/payload-unsigned.apk" \
  $([ -f "$OUT/res.zip" ] && echo "$OUT/res.zip")

find "$P/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt" -Xlint:-options
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
d8 --lib "$PLATFORM" --min-api 30 --output "$OUT/dex" @"$OUT/classes.txt"
cd "$OUT/dex" && zip -q -j "$OUT/payload-unsigned.apk" classes*.dex && cd - >/dev/null

# Add the native lib under lib/arm64-v8a/ inside the apk zip.
( cd "$P/apk" && zip -q -r "$OUT/payload-unsigned.apk" lib )

echo "== payload contents =="
unzip -l "$OUT/payload-unsigned.apk" | grep -E 'classes|lib/'
sh "$(dirname "$0")/deploy_payload.sh" "$OUT/payload-unsigned.apk"
echo "deployed native payload"
