#!/bin/sh
# ADFA-4128 demo driver — shared helpers. Reliable UiAutomator-based driving so a
# recorded demo doesn't depend on hand-typed adb coordinates. Source this.
#
#   SERIAL   default A56; override with env.
#   DAEMON   Mac->on-device daemon (adb forward tcp:18378 tcp:8378).
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
SERIAL="${SERIAL:-RZGYC24640P}"
ADB="adb -s $SERIAL"
DAEMON="${DAEMON:-http://127.0.0.1:18378}"
SHELL_PKG="com.adfa.ministubby.host"
COGO_PKG="com.itsaky.androidide"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

tap()   { $ADB shell input tap "$1" "$2"; }
swipe() { $ADB shell input swipe "$1" "$2" "$3" "$4" "${5:-300}"; }
key()   { $ADB shell input keyevent "$1"; }

# Type an ASCII prompt reliably: word-by-word, KEYCODE_SPACE between words. Avoids the
# `input text` %s-encoding pitfalls; punctuation attached to words (commas/periods) is
# fine. Assumes the target EditText is already focused.
type_words() {
    _first=1
    for w in $1; do
        [ $_first -eq 1 ] || key 62          # 62 = KEYCODE_SPACE
        _first=0
        $ADB shell input text "$w"
    done
}

# Center (x y) of the first UiAutomator node whose dump line matches $1 (text/resource
# substring). Empty if not found. Reliable vs guessing pixel coordinates.
find_center() {
    $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    $ADB shell cat /sdcard/ui.xml 2>/dev/null | tr '>' '\n' | grep -i "$1" \
      | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 \
      | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
      | awk '{ if (NF==4) print int(($1+$3)/2), int(($2+$4)/2) }'
}

tap_text() {  # tap the center of the first node matching $1; returns 1 if not found
    c=$(find_center "$1")
    [ -n "$c" ] || { log "tap_text: '$1' not found"; return 1; }
    log "tap '$1' @ $c"; tap $c
}

# Current served generation from the daemon (X-Gen header on /payload).
cur_gen() {
    curl -s -m 3 -D - -o /dev/null "$DAEMON/payload?have=0" 2>/dev/null \
      | awk 'tolower($1)=="x-gen:"{print $2}' | tr -d '\r'
}

# Block until the shell reports a hot-reload past $1 (via logcat "RELOADED"), or $2 s.
# Prints the RELOADED banner line if seen. Returns 1 on timeout.
wait_reload_after() {
    _base_ts=$($ADB shell "logcat -d 2>/dev/null | grep -c RELOADED" | tr -d '\r')
    _deadline=$(( $(date +%s) + ${2:-240} ))
    while [ "$(date +%s)" -lt "$_deadline" ]; do
        _now=$($ADB shell "logcat -d 2>/dev/null | grep -c RELOADED" | tr -d '\r')
        if [ "${_now:-0}" -gt "${_base_ts:-0}" ]; then
            $ADB shell "logcat -d 2>/dev/null | grep RELOADED | tail -1" | tr -d '\r'
            return 0
        fi
        sleep 2
    done
    log "wait_reload_after: TIMEOUT after ${2:-240}s"
    return 1
}
