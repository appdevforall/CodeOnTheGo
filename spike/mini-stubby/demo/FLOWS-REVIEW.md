# ADFA-4128 — E2E demo flows for review

Two flows to record, both proving the **on-device live-reload loop** (incremental
Kotlin compile + dex + aapt2 run on the A56; the shell hot-reloads with **no install**).
The only off-device piece is headless Claude in Flow 1. Please review the flow shapes,
the "what's on camera" claims, and the open questions before I run + record.

Status: honest status banner already landed + verified (`built X s + reload Y ms`).
Driver scripts written + the flaky part (Ask-dialog driving) validated. **Nothing recorded yet.**

---

## Flow 1 — "Ask Claude" from the shell builds Lemonade Stand (4 prompts, live)

**Narrative:** start from a blank app, build the whole game by chatting, on-device.

```
shell "Ask Claude" dialog
  → POST /ask (orchestrator on :8377, via adb reverse)
     → headless Claude on the Mac edits payload-kotlin/  (real `claude -p`, sonnet)
     → adb push changed src into the on-device project
     → POST /build to the on-device daemon (:8378, via adb forward 18378)
        → daemon compiles + dexes on the PHONE (CoGo's bundled JDK 21 + d8 + aapt2)
        → shell long-polls /payload, verifies SHA-256, hot-reloads — no install
```

**The 4 on-camera prompts (from DEMO-PLAN.md, unchanged):**
1. Build a classic Lemonade Stand game… (weather, glasses, price, ad signs, sell, profit, cash)
2. Restyle mint-green + cream, bigger title — *do not reset my game*
3. Add a daily dashboard (cash, day, yesterday's profit, trend) — *keep my game going*
4. Add a leaderboard button (best cash, top 10, back) — *keep my game going*

**On camera:** blank stage → amber "Claude is writing your code…" during each generation
→ green "Live-reloaded!" flash with the honest `built N s + reload M ms` metric → the
new feature, **with Day + cash preserved across prompts 2–4** (the state-preservation claim).

**Timing (Sonnet/medium, from rehearsal):** P1 ≈ 105 s of Claude writing, tweaks 30–100 s;
on-device compile+deploy ~1–4 s throughout. The wait is Claude, not the build. I'll
compress the generation waits in post (auto-editor) so the cut stays watchable.

**Reset:** I overwrite the Mac-side `payload-kotlin` with a trivial blank `Main.kt`
(`demo/blank-start/Main.kt`) and build once, so the demo genuinely starts from empty.

---

## Flow 2 — CoGo editor → Run → live reload

**Narrative:** edit source *inside CoGo* and hit Run; the running app updates without a reinstall.

```
open LemonadeStand in CoGo   (valid Gradle project + .livereload marker)
  → CoGo LiveReloadManager spawns the daemon on that project
edit a source file in CoGo's editor  (e.g. change a title / color / number)
tap Run
  → LiveReloadManager: flush editor → POST /build (changed .kt) → launch shell
     → daemon compiles on-device → shell hot-reloads
```

**Full-vs-fast Run gate (already implemented):** first Run (or any manifest / *.gradle
change) does a real full Gradle build; subsequent code/res edits take the fast loop.

**On camera:** CoGo editor with a visible edit → tap Run → shell comes forward already
showing the change, with the honest reload banner.

**Known risk (flagged):** opening a project in CoGo via UI automation bounced in an
earlier session. The CoGo build installed on the A56 *does* contain my LiveReloadManager
(verified in the APK dex), so the code path is real — the open risk is UI-driving only.
If it won't drive reliably, I'll fall back to a hand-driven open, or narrow Flow 2 to the
edit→Run portion once the project is open.

---

## Reliability approach (the "write scripts, don't hand-drive" ask)

All driving is scripted under `tools/demo/` so a take is deterministic:
- `lib.sh` — UiAutomator coordinate discovery (`find_center`), reload detection.
- `inject_ask.sh "<prompt>"` — opens the Ask dialog (the floating button isn't in the
  a11y tree, so its coord is hardcoded/overridable; everything inside the AlertDialog is
  located by UiAutomator), types word-by-word, dismisses the IME, taps Send. **Validated.**
- `record.sh start|stop` — chunked on-device `screenrecord`, pulled + concatenated.
- `reset_payload.sh` — reset to blank + build.
- `run_ask_demo.sh` — the whole Flow 1, prompt-by-prompt, waits for each reload.
- Flow 2 CoGo driver — to be written after we align (project-open is the risky bit).

---

## Open questions for you

1. **Flow 1 — live Claude, or replay known-good?** Live is the honest thing but each
   prompt is a real 30–105 s generation and *could* emit a non-compiling intermediate
   (shell holds last-good; on-device path has no auto-retry). I lean **live** for P1 at
   minimum. OK to run all 4 live, or prefer I fall back to the captured known-good outputs
   if a prompt's final build fails?
2. **One combined recording or two?** You mentioned "the full recording showing both."
   I can stitch Flow 1 + Flow 2 into one cut, or deliver two clips. Preference?
3. **Flow 2 project:** LemonadeStand (valid Gradle project already on-device) is my target.
   Good, or do you want a fresh New-Project-wizard project to prove that path too?
4. **Blank-start reset for Flow 1** wipes the current lemonade source in `payload-kotlin`
   (regenerable; `demo/known-good/` has backups). OK to reset?
