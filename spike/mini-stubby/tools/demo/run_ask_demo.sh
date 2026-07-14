#!/bin/sh
# ADFA-4128 — FLOW 1 end-to-end: "Ask Claude" from the shell builds the Lemonade Stand
# game on-device, live, in 4 natural prompts. Fully scripted so a recorded take is
# deterministic. Pipeline per prompt:
#   shell Ask dialog -> POST /ask (orchestrator on :8377) -> headless Claude edits
#   payload-kotlin on the Mac -> adb push into the on-device project -> POST /build to
#   the on-device daemon -> daemon compiles+dexes on the phone -> shell hot-reloads.
#
# Prereqs (checked below): daemon running in serve mode on LiveReloadDemo, orchestrator
# running with --ondevice-project, adb forward 18378->8378 + reverse 8377, shell installed.
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/lib.sh"
SPIKE="$(cd "$HERE/.." && pwd)"
OUT="$SPIKE/demo/recordings"
BASE="${BASE:-$(date +%Y-%m-%d)_ask-flow-lemonade}"
PROJ="/storage/emulated/0/CodeOnTheGoProjects/LiveReloadDemo"

P1="Build a classic Lemonade Stand game like the one on Wikipedia. Each day show the weather. Let me choose how many glasses to make, the price per glass, and how many ad signs to buy. Then sell and show the days profit and my cash. Make it look nice."
P2="While I keep playing, restyle the app with a fresh mint green and cream look and a bigger title. Do not reset my game."
P3="Add a daily dashboard at the top showing my cash, the day number, yesterdays profit, and a short profit trend. Keep my game going."
P4="Add a leaderboard button that shows my best cash ever reached, top 10, with a back button. Keep my game going."

log "== preflight =="
G=$(cur_gen); log "daemon serveGen=${G:-<none>}"
[ -n "$G" ] || { log "!! daemon not reachable on $DAEMON"; exit 1; }
pgrep -f "orchestrator.py --ondevice-project" >/dev/null || { log "!! orchestrator not running"; exit 1; }
$ADB shell pm path "$SHELL_PKG" >/dev/null 2>&1 || { log "!! shell not installed"; exit 1; }

log "== reset to blank =="
"$HERE/reset_payload.sh" "$PROJ"
sleep 3

log "== start recording =="
"$HERE/record.sh" start "$BASE"
$ADB shell am start -n "$SHELL_PKG/.MainActivity" >/dev/null 2>&1
sleep 4

i=0
for P in "$P1" "$P2" "$P3" "$P4"; do
    i=$((i+1))
    log "======== PROMPT $i ========"
    log "$P"
    "$HERE/inject_ask.sh" "$P" || log "inject failed (continuing)"
    if wait_reload_after 0 260; then
        log "prompt $i reloaded OK"
    else
        log "prompt $i did not reload in time — daemon tail:"
        $ADB shell "logcat -d 2>/dev/null | grep -iE 'TIER|error|kotlinc' | tail -6" | tr -d '\r'
    fi
    sleep 6   # let the new UI + banner sit on camera
done

log "== stop recording =="
"$HERE/record.sh" stop "$BASE" "$OUT"
log "DONE. recording -> $OUT/$BASE.mp4"
