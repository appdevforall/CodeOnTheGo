# Mini-Stubby — findings + fixes from the live-reload demo build (2026-07-09)

Working toward a "build a game by prompting Claude, live" demo surfaced three issues.
Two were real design/robustness bugs; one was self-inflicted. All now addressed.

## 1. Did the demo weaken the arbitrary-app use case? (Bryan's question) — FIXED

**Concern:** the demo payload contract had drifted to `object Main { render(Activity): View }`
*plus* a rule forbidding `host.setContentView()`. That made the payload behave less like a
normal Android app.

**Root of the drift:** the shell's **"Ask Claude" button lived inside the Activity content
view**, as a sibling of the payload's container. A payload that called `setContentView` wiped
the button — so I had constrained the payload to never do so. That constraint traded
arbitrary-app generality for button-overlay convenience.

**The mechanism was never the limitation** — the `DexClassLoader` + `ResourcesLoader` core
loads arbitrary compiled app code + resources (androidx, Material3, Compose, Fragments,
multi-Activity via `ProxyActivity`, native libs, permissions — all validated earlier). Only
the *demo harness* had narrowed.

**Fix (shell rearchitecture, `MainActivity`):**
- The **payload now OWNS the Activity content** — `loadPayload` calls `setContentView(view)`
  directly. The payload may call `setContentView`, launch its own Activities, etc. — full
  normal-app behavior.
- The shell chrome (**Ask Claude button + reload-status strip**) moved into **two
  `WindowManager` SUB-WINDOWS** (`TYPE_APPLICATION_PANEL`, attached to the Activity's window
  token). They float above whatever the payload draws and are **never wiped by the payload's
  `setContentView`**. Sub-windows use the Activity token, so they need **no permission** (unlike
  `DevJumpService`'s system overlay).
- The status strip **auto-hides ~3.5 s** after each reload so it doesn't cover the app's top.
- Removed the no-`setContentView` rule from the orchestrator preamble.

Net: the Ask-Claude live-reload surface no longer imposes a render-method contract beyond
"give me a root View to show first"; the payload is free to be a normal app.

## 2. Shell "dying" during the ~100s build wait — NOT a shell bug (self-inflicted)

**Symptom:** during recording attempts the shell process would vanish mid-wait and the device
fell back to CoGo (the previous task), so `/ask` results never rendered.

**Diagnosis (logcat, captured live):** the shell **reloaded successfully every time**, then was
**`am force-stop`ped by an external adb command** (`Killing … due to from pid <adbd>`). A clean
control test — trigger a reload, then leave the device **untouched for 100 s** — showed the
shell **stable** (same pid throughout). The kills were coming from **my own
`adb shell am force-stop` commands** in the relaunch/reset sequences I was interleaving between
drive steps, not from any shell defect.

**Fixes / takeaways:**
- **Don't `am force-stop` the shell mid-flow.** Drive with taps + poll the Mac-side orchestrator
  log for build completion; reserve force-stop for deliberate clean restarts only.
- Confirmed the shell's existing **rollback path is solid**: if a payload's `render()` throws,
  the shell catches it, restores the previous classloader/resources/theme, and keeps the old
  view — no crash. So a bad payload can't take the shell down.
- Share the A56 politely — a co-tenant agent's device walk can also foreground-pop; coordinate
  via the hive before reinstalling.

## 3. Generated payload referenced undefined `R.color.*` → build failed silently — FIXED

One Sonnet build used `host.getColor(R.color.accent)` etc. without adding those colors to
`values.xml` → `unresolved reference` compile error → nothing deployed (shell kept last-good).

**Fix (preamble):** prefer **direct `Color.parseColor("#…")` ints** over `R.color` refs, and if
any resource IS referenced it MUST be defined in the same change. Also reminded the agent that
the build fails hard on any unresolved reference (so define every helper it calls).

## Status
- Shell rearchitecture: coded, **compiles** (`tools/build_host.sh`). Needs on-device validation
  (install + confirm the Ask button survives a payload `setContentView`, and a full game build).
- Preamble fixes: in `orchestrator/orchestrator.py`.
- Then: re-rehearse briefly and record the continuous take **without** force-stopping the shell.
