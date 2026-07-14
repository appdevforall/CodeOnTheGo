#!/bin/sh
# ADFA-4128 demo recorder. Wraps on-device `screenrecord` (variable-framerate; caps at
# ~3 min per chunk here to stay well under the silent-truncation limit and keep pulls
# cheap). Usage:
#   record.sh start <base>     # begin recording chunks into /sdcard/<base>-NN.mp4
#   record.sh stop  <base> <outdir>   # stop, pull all chunks, concat into <outdir>/<base>.mp4
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/lib.sh"
CMD="$1"; BASE="$2"

case "$CMD" in
  start)
    $ADB shell "rm -f /sdcard/${BASE}-*.mp4" 2>/dev/null
    # Loop chunks on-device so a long take survives the 3-min/30-min limits; killed on stop.
    $ADB shell "for i in \$(seq -w 1 20); do screenrecord --bit-rate 8000000 --time-limit 180 /sdcard/${BASE}-\$i.mp4 || break; done" >/dev/null 2>&1 &
    echo $! > "/tmp/.rec-$BASE.pid"
    log "recording started (base=$BASE)"
    ;;
  stop)
    OUT="${3:-.}"; mkdir -p "$OUT"
    $ADB shell "killall -2 screenrecord" 2>/dev/null; sleep 2
    [ -f "/tmp/.rec-$BASE.pid" ] && kill "$(cat /tmp/.rec-$BASE.pid)" 2>/dev/null
    rm -f "/tmp/.rec-$BASE.pid"
    TMP="$(mktemp -d)"
    chunks=$($ADB shell "ls /sdcard/${BASE}-*.mp4 2>/dev/null" | tr -d '\r')
    [ -n "$chunks" ] || { log "no chunks recorded"; exit 1; }
    list="$TMP/list.txt"; : > "$list"
    for c in $chunks; do
        n=$(basename "$c")
        $ADB pull "$c" "$TMP/$n" >/dev/null 2>&1 && echo "file '$TMP/$n'" >> "$list"
    done
    FFMPEG="${FFMPEG:-/opt/homebrew/opt/ffmpeg-full/bin/ffmpeg}"
    [ -x "$FFMPEG" ] || FFMPEG=ffmpeg
    "$FFMPEG" -y -f concat -safe 0 -i "$list" -c copy "$OUT/${BASE}.mp4" >/dev/null 2>&1 \
      || "$FFMPEG" -y -f concat -safe 0 -i "$list" "$OUT/${BASE}.mp4" >/dev/null 2>&1
    $ADB shell "rm -f /sdcard/${BASE}-*.mp4" 2>/dev/null
    rm -rf "$TMP"
    log "recording saved -> $OUT/${BASE}.mp4"
    ;;
  *) echo "usage: record.sh {start|stop} <base> [outdir]"; exit 2 ;;
esac
