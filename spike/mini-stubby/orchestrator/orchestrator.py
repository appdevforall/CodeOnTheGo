#!/usr/bin/env python3
"""Mini-Stubby orchestrator (component C, ADFA-4128 phase 2).

Mac-side HTTP bridge between the on-device shell app and headless Claude:

  - listens on 127.0.0.1:8377 (the phone reaches it via `adb reverse`)
  - POST /ask      {"prompt": "..."}  -> runs `claude -p` over the payload
                   sources, replies {"status":"ok"|"error","message":"..."}
  - POST /reloaded {"gen":N,"reloadMs":M} -> logged + forwarded best-effort to
                   the devloop daemon on 127.0.0.1:8378, replies 200

Python 3 stdlib only. Flags:
  --mock-claude   don't invoke the real claude binary; sleep 2 s, make a canned
                  edit to payload res/values/values.xml, return a canned message
                  (for verifying the HTTP plumbing without spending tokens)
  --no-adb        skip the `adb reverse` setup call (device off-limits in tests)
"""

import argparse
import json
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

LISTEN_HOST = "127.0.0.1"
LISTEN_PORT = 8377
DEVLOOP_URL = "http://127.0.0.1:8378/reloaded"
CLAUDE_BIN = "/Users/bryanchan/.local/bin/claude"
CLAUDE_TIMEOUT_S = 240

SPIKE_ROOT = Path(__file__).resolve().parent.parent
PAYLOAD_DIR = SPIKE_ROOT / "payload-kotlin"
# The warm compile service appends its build results here. We tail it after each
# Claude edit to detect a COMPILATION_ERROR and auto-feed it back for a fix.
COMPILE_LOG = SPIKE_ROOT / "build" / "logs" / "compile-service.log"
BUILD_FIX_RETRIES = 2

# System-ish preamble for the in-app "build/tweak this app" agent. It keeps
# Claude inside the KOTLIN payload contract that the warm compile service +
# shell render blindly.
PREAMBLE = """\
You are live-editing a KOTLIN Android app that hot-reloads onto a phone the user
is holding right now. Rules — follow ALL of them:

- Source layout (look before you edit):
  * src/app/payload/*.kt   — Kotlin code (package app.payload)
  * res/values/values.xml  — strings and colors
  * res/layout/*.xml       — layouts (optional; you may also build views in code)
- ENTRY POINT: `object Main` with `@JvmStatic fun render(host: Activity): View`.
  The shell calls Main.render(activity) by reflection and sets the returned View as
  the Activity content. Keep that object name and method signature EXACTLY. render()
  must return the root View to display. (You MAY call host.setContentView / start
  Activities if you want — the shell's controls float in their own windows and are
  never affected — but the simplest pattern is: build a root ViewGroup once and
  update the UI by rebuilding its children in place, e.g.
  `fun rebuild(root: LinearLayout, host: Activity) { root.removeAllViews(); … }`,
  which render() also calls.)
- LEAVE ROOM AT THE TOP: the shell briefly shows a thin status strip across the very
  top on each reload. Give your root a little top padding (~48px) so your title/first
  row isn't covered while it's visible.
- FRAMEWORK WIDGETS ONLY: android.widget.*, android.view.*, android.graphics.*,
  android.app.*, kotlin stdlib. NO androidx, NO Compose, NO Fragments, NO
  external libraries, NO new dependencies, NO Gradle.
- PERSIST ALL APP STATE across reloads. On every state change, write to
  host.getSharedPreferences("game", 0). At the START of render(), READ that state
  back and rebuild the UI from it. This is critical: a hot-reload rebuilds the UI,
  and the user must NOT lose game progress when you tweak the app. Use only
  primitive/String prefs (putInt/putString/putBoolean; encode lists as a joined
  String or JSON via org.json which IS available).
- THE USER IS MID-GAME RIGHT NOW. When a request adds or changes a feature, you
  MUST preserve the in-progress game: only ADD new keys to the saved state — never
  reset, rename, clear, or change the meaning of an existing key, and never wipe
  SharedPreferences. New keys must default gracefully (e.g. missing history = empty)
  so an existing save keeps its exact day number and cash on hand after the reload.
  A purely visual restyle must not touch game logic or state at all.
- Build the UI programmatically in Kotlin (LinearLayout, TextView, Button,
  EditText, ScrollView) with setOnClickListener handlers.
- *** COLORS: NEVER use R.color.* — it will NOT compile. *** The resource table is
  FIXED; res/values/values.xml already defines a `bg`/`fg`/`app` set and nothing
  else, and you cannot rely on adding more. ALWAYS use direct ints via
  android.graphics.Color, e.g. `Color.parseColor("#FFF5C518")` or hard-coded hex
  constants in Kotlin. Do NOT write `R.color.anything`, `host.getColor(R.color.…)`,
  or a getColor-with-fallback helper — even inside a try/catch, the `R.color.NAME`
  symbol itself fails to RESOLVE AT COMPILE TIME and the whole build breaks, so
  nothing reloads. Same for `@color/…` in any XML you touch.
- Other resources: `R.string.app` exists and is fine. Do not invent new
  R.string/R.layout/R.id/R.drawable references unless you ALSO define them in the
  matching res/ file in the same change. When in doubt, inline the value in Kotlin.
- Keep it in as few .kt files as practical; ensure it COMPILES as plain Kotlin
  against the Android SDK (no unresolved refs, no androidx imports). Every helper
  or symbol you reference must be defined — the build fails HARD on any unresolved
  reference and then the app does not update at all.
- ONLY edit files. Do NOT run build, gradle, adb, or shell commands — a watcher
  rebuilds and hot-reloads automatically the moment you save.
- When done, reply with exactly ONE short sentence describing what you changed —
  it is shown on the phone's screen.

The user's request:
"""

log_lock = threading.Lock()


def log(msg: str) -> None:
    with log_lock:
        print(f"[orchestrator {time.strftime('%H:%M:%S')}] {msg}", flush=True)


def run_claude(prompt: str) -> tuple[bool, str]:
    """Run headless Claude over the payload dir. Returns (ok, message)."""
    cmd = [
        CLAUDE_BIN,
        "-p",
        PREAMBLE + prompt,
        "--permission-mode",
        "acceptEdits",
        # Sonnet at medium effort: the payload is small, framework-only Kotlin, so
        # a lighter/faster model keeps the edit→reload loop snappy (the headless
        # agent's generation time dominates the loop, not the ~2 s compile).
        "--model",
        "sonnet",
        "--effort",
        "medium",
    ]
    log(f"claude -p starting (cwd={PAYLOAD_DIR})")
    t0 = time.monotonic()
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(PAYLOAD_DIR),
            capture_output=True,
            text=True,
            timeout=CLAUDE_TIMEOUT_S,
        )
    except subprocess.TimeoutExpired:
        log(f"claude timed out after {CLAUDE_TIMEOUT_S}s")
        return False, f"claude timed out after {CLAUDE_TIMEOUT_S}s"
    except OSError as e:
        log(f"claude failed to launch: {e}")
        return False, f"claude failed to launch: {e}"
    elapsed = time.monotonic() - t0
    if proc.returncode != 0:
        log(f"claude exited {proc.returncode} after {elapsed:.1f}s; "
            f"stderr tail: {proc.stderr.strip()[-300:]}")
        return False, f"claude exited {proc.returncode}"
    lines = [ln.strip() for ln in proc.stdout.splitlines() if ln.strip()]
    message = lines[-1] if lines else "done (no output)"
    log(f"claude ok in {elapsed:.1f}s: {message}")
    return True, message


def _compile_log_size() -> int:
    try:
        return COMPILE_LOG.stat().st_size
    except OSError:
        return 0


def check_build(start_size: int, settle_s: float = 2.5, timeout_s: float = 40.0):
    """
    After Claude has edited the payload, the warm compile service rebuilds
    asynchronously. Wait for its log to settle, then classify the LAST build result
    that appeared after `start_size`. Returns (built_ok, diagnostic).

    A build that COMPILATION_ERRORs logs `kotlinc FAILED (COMPILATION_ERROR):` +
    the kotlinc diagnostic; a good build logs `TIER2 code (full compile+reload)`.
    Transient mid-write failures are fine — only the LAST result matters.
    """
    deadline = time.monotonic() + timeout_s
    last_size = -1
    stable_since = None
    saw_result = False
    while time.monotonic() < deadline:
        size = _compile_log_size()
        # peek at whether a build result has appeared yet
        if size > start_size and not saw_result:
            try:
                with open(COMPILE_LOG, "rb") as f:
                    f.seek(start_size)
                    chunk = f.read().decode("utf-8", "replace")
                if "TIER2 code (full compile+reload)" in chunk or "kotlinc FAILED" in chunk:
                    saw_result = True
            except OSError:
                pass
        if size != last_size:
            last_size = size
            stable_since = time.monotonic()
        elif saw_result and stable_since and (time.monotonic() - stable_since) >= settle_s:
            break
        time.sleep(0.5)

    try:
        with open(COMPILE_LOG, "rb") as f:
            f.seek(start_size)
            new = f.read().decode("utf-8", "replace")
    except OSError:
        return True, ""   # can't read — don't block the loop

    ok_idx = new.rfind("TIER2 code (full compile+reload)")
    fail_idx = new.rfind("kotlinc FAILED")
    if fail_idx > ok_idx:
        # Grab the diagnostic: lines from the FAILED marker up to the next KCS line.
        tail = new[fail_idx:]
        diag_lines = []
        for ln in tail.splitlines()[1:]:      # skip the "KCS kotlinc FAILED…" line
            if ln.startswith("KCS "):
                break
            if ln.strip():
                diag_lines.append(ln.rstrip())
        diag = "\n".join(diag_lines[:40])     # cap size
        return False, diag
    return True, ""


def run_claude_with_build_retry(prompt: str) -> tuple[bool, str]:
    """
    Run Claude, then verify the payload actually COMPILED. If the warm compiler
    reports a COMPILATION_ERROR, feed the exact kotlinc diagnostic back to Claude to
    fix it — up to BUILD_FIX_RETRIES times. This is what makes the live-reload loop
    robust to the occasional generation slip (wrong helper arity, a typo): the user
    just sees a slightly longer build, then the working app.
    """
    start = _compile_log_size()
    ok, message = run_claude(prompt)
    if not ok:
        return ok, message

    built, diag = check_build(start)
    attempt = 0
    while not built and attempt < BUILD_FIX_RETRIES:
        attempt += 1
        log(f"build failed (attempt {attempt}/{BUILD_FIX_RETRIES}); feeding kotlinc "
            f"error back to Claude:\n{diag}")
        fix_prompt = (
            "The change you just made does NOT compile. Fix the Kotlin compile "
            "error(s) below so the app builds. Keep all existing behavior and state; "
            "change only what's needed to compile. Do not introduce R.color.* — use "
            "android.graphics.Color directly.\n\n"
            "kotlinc error output:\n" + diag
        )
        start = _compile_log_size()
        ok, message = run_claude(fix_prompt)
        if not ok:
            return ok, message
        built, diag = check_build(start)

    if not built:
        log(f"build STILL failing after {BUILD_FIX_RETRIES} fix attempts")
        return False, "build failed (compile error persisted after auto-fix)"
    if attempt:
        log(f"build recovered after {attempt} auto-fix attempt(s)")
        message = message + f" (auto-fixed {attempt} compile error(s))"
    return True, message


def run_mock_claude(prompt: str) -> tuple[bool, str]:
    """--mock-claude: 2 s sleep + canned edit of the payload title string."""
    log(f"mock claude: prompt={prompt!r}")
    time.sleep(2)
    values = PAYLOAD_DIR / "res" / "values" / "values.xml"
    try:
        text = values.read_text(encoding="utf-8")
        stamp = time.strftime("%H:%M:%S")
        new_text, n = re.subn(
            r'(<string name="payload_title">)[^<]*(</string>)',
            rf"\g<1>My Notes (mock {stamp})\g<2>",
            text,
            count=1,
        )
        if n == 0:
            return False, "mock edit failed: payload_title string not found"
        values.write_text(new_text, encoding="utf-8")
    except OSError as e:
        return False, f"mock edit failed: {e}"
    return True, f"Mock edit: set the title to 'My Notes (mock {stamp})'."


class Handler(BaseHTTPRequestHandler):
    server_version = "MiniStubbyOrchestrator/1.0"
    # Set by main():
    mock_claude = False
    ask_lock = threading.Lock()

    def log_message(self, fmt, *args):  # route http.server logs through log()
        log(f"{self.address_string()} {fmt % args}")

    def _read_json_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            return json.loads(raw.decode("utf-8")) if raw else {}
        except (ValueError, UnicodeDecodeError):
            return None

    def _reply_json(self, code: int, obj: dict) -> None:
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path == "/ask":
            self._handle_ask()
        elif self.path == "/reloaded":
            self._handle_reloaded()
        else:
            self._reply_json(404, {"status": "error", "message": "not found"})

    def do_GET(self):
        self._reply_json(404, {"status": "error", "message": "POST only"})

    def _handle_ask(self):
        body = self._read_json_body()
        if body is None or not isinstance(body.get("prompt"), str) \
                or not body["prompt"].strip():
            self._reply_json(400, {"status": "error",
                                   "message": "body must be JSON with a non-empty 'prompt'"})
            return
        prompt = body["prompt"].strip()
        # Serialize: one Claude run at a time. Busy -> 429 immediately.
        if not Handler.ask_lock.acquire(blocking=False):
            log("/ask rejected: busy")
            self._reply_json(429, {"status": "error",
                                   "message": "busy: a previous request is still building"})
            return
        try:
            log(f"/ask: {prompt!r}")
            if Handler.mock_claude:
                ok, message = run_mock_claude(prompt)
            else:
                ok, message = run_claude_with_build_retry(prompt)
            self._reply_json(200 if ok else 500,
                             {"status": "ok" if ok else "error", "message": message})
        finally:
            Handler.ask_lock.release()

    def _handle_reloaded(self):
        body = self._read_json_body() or {}
        gen = body.get("gen")
        reload_ms = body.get("reloadMs")
        log(f"/reloaded: gen={gen} reloadMs={reload_ms}")
        # Reply to the shell first-class; forward to devloop best-effort.
        self._reply_json(200, {"status": "ok"})
        threading.Thread(target=forward_reloaded, args=(body,), daemon=True).start()


def forward_reloaded(body: dict) -> None:
    """Best-effort forward of /reloaded to the devloop daemon on 8378."""
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        DEVLOOP_URL, data=data,
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=2) as resp:
            log(f"forwarded /reloaded to devloop ({resp.status})")
    except (urllib.error.URLError, OSError) as e:
        log(f"devloop forward skipped ({e.reason if hasattr(e, 'reason') else e})")


def setup_adb_reverse() -> None:
    try:
        proc = subprocess.run(
            ["adb", "reverse", f"tcp:{LISTEN_PORT}", f"tcp:{LISTEN_PORT}"],
            capture_output=True, text=True, timeout=15)
        if proc.returncode == 0:
            log(f"adb reverse tcp:{LISTEN_PORT} -> tcp:{LISTEN_PORT} OK")
        else:
            log(f"WARNING: adb reverse failed ({proc.stderr.strip() or proc.stdout.strip()})"
                " — the phone will not reach this orchestrator until it succeeds")
    except (OSError, subprocess.TimeoutExpired) as e:
        log(f"WARNING: could not run adb reverse ({e})")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mock-claude", action="store_true",
                        help="canned 2s edit instead of invoking the claude binary")
    parser.add_argument("--no-adb", action="store_true",
                        help="skip adb reverse (verification without a device)")
    args = parser.parse_args()

    if not PAYLOAD_DIR.is_dir():
        log(f"FATAL: payload dir not found: {PAYLOAD_DIR}")
        sys.exit(1)

    Handler.mock_claude = args.mock_claude
    if args.no_adb:
        log("--no-adb: skipping adb reverse")
    else:
        setup_adb_reverse()

    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler)
    log(f"listening on http://{LISTEN_HOST}:{LISTEN_PORT}"
        f" (mock_claude={args.mock_claude})")
    log("shell endpoints: POST /ask {\"prompt\":...}, POST /reloaded {\"gen\":N,\"reloadMs\":M}")
    log(f"payload dir: {PAYLOAD_DIR}")
    log("Reminder: keep the devloop daemon (run_devloop.sh) running so edits rebuild;")
    log("re-run `adb reverse tcp:8377 tcp:8377` if the device reconnects.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log("shutting down")
        server.shutdown()


if __name__ == "__main__":
    main()
