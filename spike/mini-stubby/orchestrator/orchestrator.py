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
PAYLOAD_DIR = SPIKE_ROOT / "payload"

# System-ish preamble for the in-app "what should I build next" agent. It must
# keep Claude inside the payload contract — edit-only, framework widgets,
# stable entry point — because the devloop daemon rebuilds whatever lands on
# disk and the shell renders it blindly.
PREAMBLE = """\
You are live-editing an Android "user app" payload that is hot-reloaded onto a
phone the user is holding right now. Rules — follow ALL of them:

- You are in the payload source directory. Look before you edit: code lives
  under java/app/payload/ (packages app.payload, app.payload.ui,
  app.payload.data); res/values/values.xml holds strings, colors, dimens and
  styles; res/values-night/ holds the dark palette; res/layout/ holds screens;
  res/drawable/ holds shapes and selectors; assets/ holds raw files.
- The entry point is `app.payload.Main`, whose `render(Activity)`,
  `render(Activity, Bundle)` and `saveState()` methods the shell invokes by
  reflection. Keep that class name and those signatures EXACTLY as they are.
- A custom View subclass CAN be referenced by class name in layout XML. But do
  NOT add custom XML attributes (declare-styleable) — the payload's attr
  namespace is invisible to the host theme. Style custom views from code.
- Anything placed in the saveState() Bundle must be a framework type (String,
  int, int[], long, …) — never a payload-defined class.
- Use Android FRAMEWORK widgets and APIs only (android.widget.*, android.view.*
  etc.). No androidx, no external libraries, no new dependencies. No Fragments.
- Reference resources via the payload's own R class / @string / @color / @layout.
  The resource package id is assigned at build time — NEVER hardcode resource
  ids or the 0x80 package id.
- ONLY edit files. Do NOT run any build, gradle, adb, or shell commands — a
  file watcher rebuilds and hot-reloads automatically the moment you save.
- Keep the change minimal and self-contained; the app must stay compilable
  plain Java 17 against the Android SDK.
- When you are done, reply with exactly ONE short sentence describing what you
  changed — it is shown on the phone's screen.

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
                ok, message = run_claude(prompt)
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
