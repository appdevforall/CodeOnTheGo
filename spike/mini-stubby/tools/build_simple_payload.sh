#!/bin/sh
# Build a simple hand-compiled payload (no androidx) at 0x80 and deploy it.
# Usage: build_simple_payload.sh <payload-src-dir>   (dir has java/ + AndroidManifest.xml, optional res/)
set -e
. "$(dirname "$0")/env.sh"
P="$1"
[ -d "$P/java" ] || { echo "no java/ in $P" >&2; exit 1; }
NAME=$(basename "$P")
OUT="$BUILD_DIR/$NAME"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

RESARG=""
if [ -d "$P/res" ]; then
  aapt2 compile --dir "$P/res" -o "$OUT/res.zip"
  RESARG="$OUT/res.zip"
fi
aapt2 link --manifest "$P/AndroidManifest.xml" -I "$PLATFORM" \
  --package-id 0x80 --allow-reserved-package-id \
  --min-sdk-version 30 --target-sdk-version 34 \
  --java "$OUT/gen" -o "$OUT/payload-unsigned.apk" $RESARG

find "$P/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt" -Xlint:-options
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
d8 --lib "$PLATFORM" --min-api 30 --output "$OUT/dex" @"$OUT/classes.txt"
cd "$OUT/dex" && zip -q -j "$OUT/payload-unsigned.apk" classes*.dex && cd - >/dev/null

sh "$(dirname "$0")/deploy_payload.sh" "$OUT/payload-unsigned.apk"
echo "deployed $NAME"
