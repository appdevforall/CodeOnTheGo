# ADFA-4128 Mini-Stubby — Live-Reload Demo / Test Plan

**Goal:** demonstrate the whole Mini-Stubby loop *using Claude prompts only* — start
from a blank app, and build + tweak a Lemonade Stand game live, showing that code edits
appear in the running app in ~1 s and **game state survives every reload**.

**Device:** Samsung Galaxy A56 (`RZGYC24640P`), production-adjacent Android 16.
**What's driving the loop:** the on-device **"Ask Claude"** button → `adb reverse` →
Mac orchestrator → headless `claude -p` (now **Sonnet / medium effort**) editing
`payload-kotlin/` → warm compile service rebuilds (~2 s) → shell hot-reloads.

> **Why each prompt takes ~30–120 s:** it is **not** the build. Compile+deploy is ~2 s
> (`TIER2 total≈1.9 s`). The wait is the headless coding agent *writing the Kotlin*.
> Switching Opus→Sonnet(medium) is the lever that shortens it.

---

## Pre-test setup (clean state)

1. Compile service running (`TIER1=true`), orchestrator running (Sonnet), `adb reverse tcp:8377`.
2. **Wipe game state:** `adb shell run-as com.adfa.ministubby.host rm -f shared_prefs/game.xml`
   (so the recording starts at Day 1 / $20.00, not mid-game).
3. **Restore the blank payload:** `payload-kotlin/src/app/payload/Main.kt` = the blank-app
   stub (renders "Blank app"). Service redeploys it → shell shows the blank app.
4. `show_touches` / `pointer_location` ON so taps are visible in the recording.
5. Overlay quick-jump pill: **off** during recording (revoke `SYSTEM_ALERT_WINDOW`) so it
   doesn't cover the title — it was already demonstrated separately.

---

## Flows

### Flow 0 — Blank app + project structure
- **Intent:** show the starting point: a blank app wired to live-reload, and its tiny
  source tree (what a CoGo project looks like here).
- **Steps:** launch shell → screen reads **"Blank app / Connected to live reload."**
  Show `payload-kotlin/` tree: `AndroidManifest.xml`, `res/values/values.xml`,
  `res/layout/main.xml`, `src/app/payload/Main.kt`.
- **Pass:** blank app visible; status bar shows `gen N · reload <Xms>`.

### Flow 1 — Prompt 1: build the game, then play
- **Prompt:** "Build a Lemonade Stand game based on the Wikipedia description … (mechanics: daily weather → decide glasses/price/ads → Sell! → profit → next day; persist all state)."
- **Steps:** send prompt via Ask-Claude path → wait for reload → game renders (Day 1,
  $20.00, weather, decision fields, **Sell!**). Tap **Sell!** → result phase
  (sold/revenue/costs/profit/cash). Tap **Next day** → Day 2 forecast.
- **Pass:** a real, playable game appears with no manual build/install; the generated
  `Main.kt` is shown as the "code built in CoGo."

### Flow 2 — Prompt 2 (+ maybe 2b): tweak the interface *while playing*
- **Precondition:** mid-game (e.g. Day 2, cash ≠ $20.00) — a state visibly different
  from a fresh start.
- **Prompt (cosmetic/UI only):** e.g. "Add a big running-total 'Total profit so far'
  banner at the top, and colour the weather card by weather." (No state-shape change.)
- **Steps:** note current Day + cash → send prompt → wait for reload → observe the tweak
  applied **and the same Day + cash still shown**.
- **Pass (the key claim):** UI changed; **Day number and cash did NOT reset**. This is
  the SharedPreferences-survives-reload guarantee (and, for pure body edits, Tier-1
  in-place hot-swap with zero reload).

### Flow 3 — Prompt 3: daily dashboard module
- **Prompt:** "Before each day's decisions, show a dashboard: current cash, day number,
  yesterday's result, and a 3-day profit trend."
- **Steps:** send → reload → a dashboard/summary appears in the daily flow.
- **Expectation:** this MAY require a state-shape change (new keys / history list). If it
  resets the run, that's acceptable and called out; the point is it *works* and rebuilds
  cleanly. Prefer a prompt that reads existing state so it does **not** reset — verify
  which happens and report honestly.
- **Pass:** dashboard renders with real numbers; note whether state was preserved or reset.

### Flow 4 — Prompt 4: 10-person leaderboard
- **Prompt:** "Add a leaderboard that records my best score (peak cash) over time — keep
  the top 10 with a date, persisted, viewable from a button."
- **Steps:** send → reload → a leaderboard screen reachable from a button; play enough to
  post a score; confirm it persists across a reload.
- **Pass:** leaderboard shows entries; a new high score is recorded and survives a reload.

---

## Edge cases to note (not gating)
- A prompt that adds a **new .kt file / new class** → correctly forces **Tier-2 full
  reload** (Tier-1 gate rejects class-set changes). Expected, not a bug.
- A **compile error** from a prompt → service logs `kotlinc` diagnostics; the shell keeps
  showing the last-good app (no crash). If it happens on camera, show the recovery.
- Rapid re-prompt while a build is in flight → orchestrator returns **429 busy** (serialized).

## Definition of done (gates the recording)
- [ ] Flows 0–4 each pass on-device at least once (rehearsed before the take).
- [ ] State-preservation claim in Flow 2 visibly demonstrated (same Day+cash across a tweak).
- [ ] Generated code shown for Flow 1 (and diffs, if convenient, for later prompts).
- [ ] One captioned + auto-shortened recording via `tools/recording/` in
      `demo/recordings/` (chunked if it approaches the 30-min screenrecord cap).

## Rehearsal findings (2026-07-09, Sonnet/medium)

- **Trigger on camera = the on-device "Ask Claude" dialog** (multiline EditText + Mic +
  Send), text injected via `adb shell input text` (spaces→`%s`). Validated: dialog opens,
  text lands, Send kicks off the build. This is the "chat with Claude in the app" moment.
- **Two shell/preamble bugs found + fixed during rehearsal:**
  1. **Tier 1 (JVMTI in-place redefine) doesn't repaint** an already-drawn screen and a
     restart reverts it → **disabled Tier 1 for the demo; Tier 2 (full reload) repaints
     reliably** and still preserves state via SharedPreferences (~0.7–3 s compile).
  2. Generated games re-rendered via `host.setContentView(...)`, which **destroyed the
     shell's floating "Ask Claude" button** after any in-game navigation. **Fixed via
     preamble:** forbid `setContentView`; render() builds a root once and `rebuild()`s its
     children in place. Verified the Ask Claude button now survives Sell / Next-day / etc.
- **Strengthened the preamble** with an explicit "user is mid-game — only ADD state keys,
  never reset/rename" rule, so *short, natural* prompts still preserve progress.
- **Timing (Sonnet/medium):** P1 build ≈105 s (no WebFetch, single file), tweaks ≈30–100 s.
  Compile+deploy is ~1–3 s throughout — the wait is the agent writing Kotlin, not the build.
- Transient mid-write compile errors (Sonnet references a helper before defining it) are
  harmless: the shell holds the last-good app until the final good build deploys.

### Finalized on-camera prompts (ASCII, injectable)

1. `Build a classic Lemonade Stand game like the one on Wikipedia. Each day show the weather. Let me choose how many glasses to make, the price per glass, and how many ad signs to buy. Then sell and show the days profit and my cash. Make it look nice.`
2. `While I keep playing, restyle the app with a fresh mint green and cream look and a bigger title. Do not reset my game.`
3. `Add a daily dashboard at the top showing my cash, the day number, yesterdays profit, and a short profit trend. Keep my game going.`
4. `Add a leaderboard button that shows my best cash ever reached, top 10, with a back button. Keep my game going.`

## Recording mechanics
- Harness: `tools/recording/start.sh <base>` → `caption.sh` before each action →
  `stop.sh <base> "<title>"`. Captions mark intent; auto-editor (`--silent-speed 6`)
  compresses the Claude-generation waits so the demo stays watchable.
- Caption the waits explicitly ("Claude is writing the game…") so the dead time reads as
  progress, not a hang.
