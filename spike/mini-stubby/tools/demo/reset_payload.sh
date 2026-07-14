#!/bin/sh
# ADFA-4128 — reset the demo to the blank "before" state so the Ask flow visibly builds
# the whole game from scratch. Overwrites the orchestrator's Mac-side payload source
# (payload-kotlin, which Claude edits) with demo/blank-start/Main.kt, pushes it into the
# on-device project, and triggers one on-device build so the shell reloads to blank.
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/lib.sh"
SPIKE="$(cd "$HERE/../.." && pwd)"   # tools/demo -> tools -> mini-stubby (spike root)
PROJ="${1:-/storage/emulated/0/CodeOnTheGoProjects/LiveReloadDemo}"

log "reset Mac payload-kotlin -> blank-start"
cp "$SPIKE/demo/blank-start/Main.kt" "$SPIKE/payload-kotlin/src/app/payload/Main.kt"

log "push blank source into on-device project"
$ADB push "$SPIKE/payload-kotlin/src/." "$PROJ/app/src/main/java" >/dev/null

log "trigger on-device build (whole app)"
curl -s -m 90 -X POST "$DAEMON/build?kind=code" \
     --data "$PROJ/app/src/main/java/app/payload/Main.kt"; echo
log "reset done — shell should show the blank stage"
