# Mini-Stubby overnight build — component contracts (ADFA-4128 phase 2)

Three components built in parallel. These contracts are the source of truth;
do not change them unilaterally — code to them exactly.

## Directory layout

```
spike/mini-stubby/
├── host/                 # shell app (component B evolves host/java/.../MainActivity.java)
├── payload/              # the "user app" Claude will edit (component C refreshes it)
├── devloop/              # component A: warm compile daemon (Java 17, Mac-side)
│   ├── src/DevLoopDaemon.java
│   └── run_devloop.sh
├── orchestrator/         # component C: Mac-side HTTP server + claude -p bridge
│   └── orchestrator.py
└── tools/                # existing build scripts (env.sh, build_host.sh, build_payload.sh, deploy_payload.sh)
```

## Ports & transport

- Mac orchestrator listens on **127.0.0.1:8377** (plain HTTP). The phone reaches
  it via `adb reverse tcp:8377 tcp:8377` (orchestrator sets this up at startup).
- Shell → orchestrator:
  - `POST /ask` body `{"prompt": "<user request text>"}` → orchestrator runs
    Claude on the payload sources, replies (may take 30-120 s; shell must use a
    generous read timeout) `{"status":"ok","message":"<short summary of what Claude did>"}`
    or `{"status":"error","message":"..."}`.
  - `POST /reloaded` body `{"gen": N, "reloadMs": M}` fire-and-forget, sent by the
    shell right after a payload reload renders. Lets the Mac side compute
    save→rendered end-to-end on ONE clock. Reply: 200, body ignored.
- Never put secrets in these messages.

## Payload contract (UNCHANGED from phase 1)

- Payload apk = aapt2-linked resources (`--package-id 0x80`) + `classes.dex` + assets.
- Entry point: `app.payload.Main` with `public static View render(Activity host)`.
- Deployed to the shell by `tools/deploy_payload.sh <apk-path>` (adb push +
  run-as atomic rename into `files/payload/payload.apk`).

## Component A — devloop daemon (`devloop/`)

Single-file Java 17 program, run on the Mac with the flox JDK. Long-running.
- Watches `payload/java/`, `payload/res/`, `payload/assets/` recursively
  (java.nio WatchService; re-register new dirs; debounce 80 ms).
- On change, rebuilds the payload INCREMENTALLY and WARM (no new JVMs):
  - javac: `javax.tools.ToolProvider.getSystemJavaCompiler()` in-process,
    classpath = android.jar + generated R.java dir. Compile ALL payload java
    files (tiny project — simpler than per-file deps) but reuse the warm JVM.
  - d8: in-process via `lib/d8.jar` on the daemon's own classpath
    (`com.android.tools.r8.D8.main(String[])` or the D8 command API).
  - aapt2 compile+link (subprocess): ONLY when res/ or assets/ changed; cache the
    last linked resource apk + generated R.java otherwise.
  - package: copy cached resource apk, `zip -j` replace classes.dex.
- Then invokes `tools/deploy_payload.sh <apk>` and prints a stage-timing line:
  `DEVLOOP save→deployed total=NNNms (javac=.. d8=.. aapt2=..|cached pack=.. deploy=..)`
- Listens on **127.0.0.1:8378** for `POST /reloaded` forwarded by the orchestrator
  (or directly from the shell if orchestrator is down is NOT required) and prints
  `DEVLOOP save→rendered end-to-end NNNms (gen N, device reload M ms)` matching the
  most recent deploy.
- Also accepts `POST /rebuild` on 8378 to force a full rebuild.
- Paths: resolve everything relative to the spike root (arg 1), no hardcoded
  absolute paths except via `tools/env.sh` values documented there.
- `run_devloop.sh`: sources `tools/env.sh`, compiles the daemon if stale, runs it
  with d8.jar on the classpath.

## Component B — shell v2 (`host/`)

Evolve `host/java/com/adfa/ministubby/host/MainActivity.java` (keep the phase-1
reload mechanics + status bar exactly as they are). Add, pure framework APIs only:
- A floating "Ask Claude" button (FrameLayout overlay, bottom-right of the
  payload container; not part of payload UI).
- Tap → dialog with an EditText, a Mic button, and Send/Cancel. Mic launches
  `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (free-form, `startActivityForResult`)
  and puts the top result into the EditText. Guard with resolveActivity — if no
  recognizer, toast and keep typing path.
- Send → background thread → `POST http://127.0.0.1:8377/ask` with JSON
  `{"prompt": ...}` (HttpURLConnection, connect timeout 3 s, read timeout 180 s).
  While in flight: disable button, show "Claude is building…" in the status bar.
  On response: show the returned message (status bar + toast). On failure: show
  the error, re-enable.
- After every successful payload reload render, fire-and-forget
  `POST http://127.0.0.1:8377/reloaded` `{"gen":N,"reloadMs":M}` from a
  background thread. Never block or crash the UI on network failure.
- Manifest: add `<uses-permission android:name="android.permission.INTERNET"/>`,
  `android:usesCleartextTraffic="true"` on <application> (loopback HTTP).
- JSON: hand-rolled minimal escaping is fine (org.json is in the platform — use
  `org.json.JSONObject`, it's in android.jar).
- MUST compile: run `SKIP_INSTALL=1 sh tools/build_host.sh` (build-only; does NOT
  touch the device — device access is reserved for the main agent).

## Component C — orchestrator (`orchestrator/orchestrator.py`) + demo payload

Python 3 stdlib only (http.server / subprocess / json / threading).
- Startup: run `adb reverse tcp:8377 tcp:8377`; print instructions.
- `POST /ask`: extract prompt; run headless Claude in the PAYLOAD directory:
  `claude -p "<system-ish preamble + user prompt>" --permission-mode acceptEdits`
  with cwd = spike payload dir. Use the plain binary path
  `/Users/bryanchan/.local/bin/claude` (the shell alias adds channels we don't
  want in a subprocess). Preamble must tell Claude: it is editing a tiny Android
  "user app" payload; entry point `app.payload.Main.render(Activity)`; resources
  in `res/` (values.xml, layout/payload_main.xml), package-id-agnostic (never
  hardcode 0x80); framework widgets only, no androidx; keep `render` signature;
  the file watcher will rebuild automatically — just edit files, don't run
  builds; keep changes minimal and self-contained; reply with ONE short sentence
  describing what you changed (that goes on the phone's screen).
  Capture stdout; on exit 0 reply `{"status":"ok","message":"<last line of stdout, trimmed>"}`,
  else `{"status":"error","message":"claude exited N"}`. Timeout 240 s.
  Serialize /ask handling (one at a time; 429 if busy).
- `POST /reloaded`: log it AND forward the same body to `127.0.0.1:8378/reloaded`
  (the devloop daemon) best-effort; reply 200.
- Also refresh the DEMO PAYLOAD to a clean, slightly-nicer starter: keep
  `payload/java/app/payload/Main.java` + res minimal but make it look like a
  plausible "My Notes"-style starter screen (title, a couple of framework
  widgets), STILL following the payload contract exactly (render(Activity),
  own R resources, no androidx). This is the canvas Claude edits live.
- No pushes to any remote, no installs, no adb calls other than `adb reverse`.
