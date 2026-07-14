#!/bin/sh
# ADFA-4128 — reliably drive the shell's "Ask Claude" dialog: open it, focus the
# EditText, type the prompt, tap Send. The floating "Ask Claude" button lives in a
# WindowManager sub-window that UiAutomator does NOT capture, so it's opened by a
# hardcoded coordinate (override with ASK_BTN="x y"); everything inside the AlertDialog
# is a normal window and located reliably by UiAutomator dump.
# Usage: inject_ask.sh "<prompt text>"
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/lib.sh"
PROMPT="$1"
[ -n "$PROMPT" ] || { echo "usage: inject_ask.sh \"<prompt>\""; exit 2; }
ASK_BTN="${ASK_BTN:-893 2176}"   # floating Ask-Claude button (bottom-right sub-window)

# 1. shell to foreground
$ADB shell am start -n "$SHELL_PKG/.MainActivity" >/dev/null 2>&1
sleep 2

# 2. open the Ask-Claude dialog (hardcoded — sub-window not in a11y tree)
log "open Ask dialog @ $ASK_BTN"; $ADB shell input tap $ASK_BTN
sleep 2

# 3. focus the EditText
EC=$(find_center 'class="android.widget.EditText"')
[ -n "$EC" ] || { log "!! EditText not found (dialog didn't open?)"; exit 1; }
log "focus EditText @ $EC"; tap $EC
sleep 1

# 4. type the prompt, then hide the IME so it can't cover the Send button
log "typing (${#PROMPT} chars)…"
type_words "$PROMPT"
sleep 1
$ADB shell input keyevent 111   # KEYCODE_ESCAPE — dismiss IME, keep dialog
sleep 1

# 5. Send (re-dump so we get its post-typing position)
SC=$(find_center 'text="Send"')
[ -n "$SC" ] || { log "!! Send button not found"; exit 1; }
log "tap Send @ $SC"; tap $SC
log "sent."
