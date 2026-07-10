# ADFA-4128 Faster edit→build→run Loop Discussion Doc

The ticket description was very tech heavy, and light on product requirements.  Given how good/cheap LLMs are, I figured the cheapest way to ask the right questions was to dive in and do some prototyping.  Then go ask the right questions based on data gathered :)

So here's a doc with more questions.  Plus already a minimally working prototype flow (see video below) from the spike.

INFO BOX

Please feel free to edit this doc!  Or write your notes in the discussion section with a prefix with your username (if you please, or stay anonymous).

# A. Proposed Product Goals - Please Edit!!

This is in the ticket, but vague about magnitude and impact.  Can we edit this until it look good?

**Goal**: Today's edit→build→run loop takes tens of seconds or more, with manual prompts.  It builds an APK using a full Gradle incremental build, installs the APK, and fights Google Play Protect.

There's also a faster live compose loop for UX tweaks

For CoGo's audience (learners on low-end, often offline devices) this kills the feedback loop that makes learning to program work well.

## Not a Goal

- Initial build time for an app is not a goal -- here, we're focusing on the edit loopIn fact, we may even allow things that make initial build slightly slower to speed up the edit loop (but don't necessarily need to)

## How Fast?

Can we make the loop a lot faster.  From HCI research, these are good targets to aim for (from [Nielsen, 1993](https://www.nngroup.com/articles/response-times-3-important-limits/))

- < 1s means the user can mentally stay in the flow
- < 10s means user can wait, get a bit distracted, but likely stay on task

If you go much higher than that, the user goes off to do another task (or makes a coffee) - added by Bryan :)

**Discussion: **Does this seem like a good target?  Aim for < 1s on all important edits (below) on 

## What Types of Changes Are Important To Support?

What types of changes do we want to handle quickly?

Proposed Priority (please edit or discuss):

**MUST** happen quickly

- Resource changes
- Styling changes
- Change method code, no signature change

**SHOULD **happen quickly, if we can make it happen

- Change code that does affect class signature

**MAY **happen quickly (**low priority)**These operations happen less frequently during normal development (and for learners) and it's okay if they're slower:

- Dependency change (requires doing dependency resolution)
- Manifest changes

**Discussion: **Agree or disagree with priority? Any other types of operations we should support and test?

### Aside: Changing code that affects class signature and keeping state without restarting test app

Ideally, we also can handle class signature changes and preserve state when changes happen, so the user can continue iterating faster.  But from initial research (see below in tech decisions section), this will likely be very hard to do in general -- only shown to be reliably possible on [Flutter](https://flutter.dev/), where you're limited to working within their world.  Not arbitrary Android apps.  

Android Studio used to try to support hot reload, but found this introduced too many issues (TODO: insert brief list of top issues and link).  Now they only support hot reload via JVTMI on changes that don't affect the class signature.

## What Phone Spec?

TBD?

Do we have an analysis of what the spec distribution looks like for our current active users?Or what we want to target?

## What Types of Apps?

TBD?

Do we have a sense for what types of apps people want to build?

## Debugging?

Ideally, debugging MUST continue working!

**Discussion: **How important is this?  Is this necessary?

## Proposed Specific Criteria

The more specific you can make criteria that's testable, the easier it is to get an LLM to go optimize for it without much human effort.

Priorities, in descending order:

1. On > 80% of phones that active users use, all change types that MUST happen quickly work in under 10s
2. Same thing, but under 1s
3. TBD: Prioritize other edge cases to deal with

And if we get to #1 (or #2) quickly, that's a big enough improvement to release.

**Discussion:**

## Workflow Options?

What's the most convenient workflow to support?

1. From IDE
  1. Editing interface + live reload trigger
    1. Option A: Live reload after every keystroke, debounced to 1s
    2. Option B: Live reload when user saves a file
    3. Option C: Live reload when user triggers using a live reload button
    4. Option D: ??
  2. How does a user switch between IDE and app to see changes quickly?
    1. Option A: User does it manually using app switcher
    2. Option B: Have buttons in Test App and in IDE to switch between
    3. Option C: Split screen?
    4. Option D: ??
2. From Test App
  1. User can make edits from the test app and live reload
    1. Option 1: Can edit style or code snippets directly from the app being tested
    2. Option 2: Can ask an LLM to make edits in the background

TODO: Market research of what other app builders support

# B. Summary of Investigation Spike So Far to Prototype "Son of Stubby"

We tried to get a sub-1s edit→build→run loop going on a Samsung A56 (moderately high spec) for a variety of app types and change types.  To see what product questions came up.  And what technical blockers we might need to deal with.

This has taken about 1hr of hands-on time, a few hours of agent time over 2 wall-clock days (July 8-10, 2026).  Fable 5 orchestrator, and various model levels doing the tasks.

## Spike Results

**▶ Demo video (2 min):** *[attach **`2026-07-09_lemonade-live-reload.mp4`** on publish]* — starting from a blank app connected to the live-reload loop, four natural-language prompts (typed into an on-device "Ask Claude" dialog) build a playable Lemonade Stand game, restyle it mid-play, and add a dashboard and a leaderboard. Each change compiles and hot-reloads in ~1–3 s with **no build dialog, no install, no Play Protect prompt — and game state (day, cash) survives every reload.**

What the spike tested out:

- **The basic test architecture**
  - Shell app installed once (i.e. the "Son of Stubby" alternative in the ticket)
  - User app's DEX + resources + assets loaded dynamically
- **App types**Benchmarked three apps from 600 to 30,000 LoC
  - 600 LoC [Lemonade Stand app](https://en.wikipedia.org/wiki/Lemonade_Stand) that we built from scratch and hot reloaded along the way
  - TODO: insert more 
- **App feature usage**Validated across a few types of apps:
  - Multiple Views
  - Kotlin vs. Java apps
  - androidx
  - Material3
  - Compose, Fragments
  - Multi-Activity
  - Native libs
  - Runtime permissions

- **The build loop, fully on-device** — 
  - Warm (preloaded) kotlinc + d8 + aapt2 + package + deploy on the phone
  - Tiered dispatch (resource-only edits skip the compiler); 
- **Incremental compilation**
  - Integrated Kotlin's own incremental engine into the warm loop (skipping Gradle); 
  - Verified correctness against clean builds; measured under memory pressure.
- **Real dependencies**
  - Compiled real androidx code incrementally against CoGo's actual offline Maven repo (266 artifacts), on-device.
- **Alternatives stress-tested and deprioritized (too hard probably)**
  - The DEX-free JVM (original Stubby)
  - JVMTI method-body hot-swap
  - JRebel/Instant-Run-style instrumentation (evidence in §B1).
- **Workflow prototypes**
  - Save file →auto-reload; 
  - Floating dev controls in the test app for switching back to editor that survive the app owning its own UI
  - On-device Claude prompt-to-build dialog that builds and hot reloads in the Test App 

### Key measurements (Samsung A56, Android 16, warm preloaded compiler)

| Change type                                                  | Time                              |
| ------------------------------------------------------------ | --------------------------------- |
| Resource/styling edit (no compiler)                          | **~0.75 s**                       |
| Code edit, small app (~600 LoC), full recompile whole app    | **~2–3 s**                        |
| Code edit with **incremental compile** of one file (600 → 30,000 LoC) | **~0.4–0.7 s, flat**              |
| Code edit, 30k LoC full compile *without* incremental        | **~9.6 s**                        |
| Real-androidx app (267-dep classpath), incremental edit      | **~0.65 s** (~1.2 s total reload) |
| Shell reload (detect → rendered)                             | ~40 ms                            |
| One-time session warm-up                                     | ~16 s + ~11 s first compile       |

Raw data, code, and detailed write-ups: spike branch `spike/mini-stubby/` (`DESIGN.md`, `demo/INCREMENTAL-RESULTS.md`, `demo/ONDEVICE-BENCHMARK.md`).

**tl;dr **On an A56 (which is pretty fast), if we want to get below 10s, even full compiles on larger apps are OK.But if we want to get under 1s, then need to use more optimized, specialized fast paths (resource/styling edit handler, incremental compile and inject just one class, possibly JVTMI hot reload)

---

---

## B. Technical decisions and options (with evidence)

### B1. Proposed as settled by spike evidence — object if you see a flaw

| Decision                                                     | Evidence (measured/demonstrated on-device)                   |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| Keep DEX (not dex-free Stubby) — and dex *incrementally* (only changed classes, not the whole app) | dexing measures **~0.3 s** of a ~3 s loop — eliminating it buys almost nothing for an enormous surface (intercept all of `android.jar`, ship a JVM). Bonus: real DEX in ART ⇒ standard debugger attaches. **How to dex (on-device A56, warm in-process D8):** dex *only the changed classes* and merge — a 1-file edit dexes in **~36 ms**, **independent of app size** (5 classes = 44 ms in a 3k-LoC app vs 48 ms in a 30k-LoC app), while re-dexing the *whole* app grows with size (**266 → 917 ms** across 600 → 30k LoC). Dex tracks *change size*, not app size — flat like incremental compile. See `demo/DEX-RESULTS.md`. |
| **Tiered dispatch** (resource edits skip the compiler)       | Have different pathways that skip steps depending on type of change seems like a helpful pattern.  For exampleFast resource edit path (0.75s vs. 2-3s code path)Method body change only hot reload pathCode change without dependency change - incremental compile pathresource path **0.75 s** vs code path 2–3 s; R-id inlining defines the safe boundary (change a resource's *value* = fast tier; change what resources *exist* = code tier). |
| **No JVMTI / JRebel hot-swap; full reload instead**          | JVMTI ran but swaps **method bodies only** and **doesn't repaint** a drawn screen (same wall as Android Studio "Apply Code Changes"); JRebel-style = Google's abandoned Instant Run. At a 0.5–3 s reload, no payoff. |
| **State: persist + rebuild** (no component-tree diff)        | demo state survived 4 reloads via `SharedPreferences`; an imperative full-Android app has no tree to diff — accepted trade. |
| **Shell dev-controls in activity sub-windows**               | survive the payload calling `setContentView`; **zero special permissions** (vs `SYSTEM_ALERT_WINDOW` for overlays). Demonstrated. |
| **On-device warm compile service**                           | full ladder in §2; runs under ~460 MB free RAM.              |
| **Incremental compile via Kotlin Build Tools API**           | **flat ~0.4–0.7 s** from 600 → 30k LoC (vs 9.6 s full); output verified **byte-identical to clean builds** at every size; under a 160 MB heap squeeze full compile ~2×-balloons while incremental holds ~1 s. |
| **Dependencies: consume Gradle/Tooling-API's resolved classpath** (never reimplement resolution) | CoGo's `tooling-api-impl` already resolves per-module classpaths (LSP needs them); PoC compiled real androidx against CoGo's offline repo (266 artifacts) at **0.65 s/edit** on-device with zero CoGo changes. |

### B2. Genuinely open — options + evidence + lean

1. **Where the warm service lives in CoGo** — in-process vs separate service process. *Evidence:* the service ran fine as its own small JVM (≤1.5 GB heap, benchmarks unaffected at 512 MB); CoGo's process is already large. *Lean:* separate small restartable process.
2. **Payload delivery channel** — shared storage (ticket's suggestion) vs content-URI + broadcast vs app-private dir + handoff. *Evidence:* spike used a watched app-private dir (FileObserver fires reliably, ~40 ms detect→render); shared `/sdcard` adds scoped-storage friction and widens the write surface (see risk C7). *Lean:* app-private dir + content-URI handoff.
3. **Multi-Activity transparency** — explicit proxy-Activity contract vs Instrumentation Intent-rewriting (VirtualAPK/Shadow pattern). *Evidence:* proxy works today (demonstrated, multi-screen apps run); Instrumentation is fully transparent but invasive and OS-version-fragile. *Lean:* proxy for MVP.
4. **Shell provisioning at Create Project** — pre-built generic shell renamed/re-badged vs aapt2-built per project. *Evidence:* shell is small (~180-line core) and builds in seconds on-device with the same toolchain the spike staged; this is the **one remaining install prompt**, so its UX matters. *Lean:* needs a small design; either is feasible.
5. **KSP and Compose plugin wiring order** — *Evidence:* Compose compiler plugin is a single `-Xplugin` integration (small); KSP adds a codegen round-trip (moderate); kapt processors with host-native code (Room verifier) are a **hard on-device wall** — measured failure, glibc dependency. *Lean:* order per A2's priority; KSP-only policy regardless.
6. **APK-container elimination** (ticket steps 6–7) — *Evidence:* packaging measures **~0.25 s**; signing + zipalign already eliminated. *Lean:* keep the minimal unsigned container; revisit only if profiling demands.
7. **Gradle provisioning residency on low-RAM** — provision-then-exit vs resident daemon. *Evidence:* resident Gradle daemon ≈ 2.7 GB RSS (prior CoGo on-device measurements) — an eviction magnet under memory pressure; cold re-provision measured ~16 s (A56) and only triggers on dependency changes. *Lean:* non-resident; validate on the A1 reference device.
8. **Debugger UX integration** — *Evidence:* mechanism free (debuggable process, real DEX; attach demonstrated), IDE-side source-mapping UX unexercised. *Lean:* spike the CoGo debugger attach flow early in MVP.

---

# C. Known Risks to Address

Please keep in mind how limited this spike is -- we did what we could quickly to explore the space with what we had.  

These are some of the risks that came out of the spike:

- **Medium-High Spec Only**Prototyped on an Samsung A56 (we probably want to aim lower spec)
- **Simple to Moderately Complex Apps**
  - 600-30,000 LoC
  - 
- On simpler apps (maybe we want more compile)
  - Building [a Lemonade Stand game from scratch](https://en.wikipedia.org/wiki/Lemonade_Stand)
  - TODO: list other test apps and complexity
- With some variety of app functionality (e.g. multiple pages, resource usage, Java and Kotlin, etc.)But we probably missed a bunch of common use cases
- On some types of edits (resource only, change a method, change a class including method signatures, change dependencies)
- With a made up time target, to give models a more specific target to optimize against(< 1s for all types of edits)

1. **Low-spec CPU unvalidated** — all timing is A56 (mid-range); memory axis proxied (robust to a 160 MB heap), slow-SoC axis not. *Mitigation:* A1 reference device; rerun the ladder before locking D1.
2. **kapt host-native processors** (e.g. Room's verifier needs glibc) can never run on-device. *Mitigation:* KSP-only MVP policy + template defaults.
3. **Cold-session cost** (~16 s provision + ~11 s first compile on A56; worse on low-end). *Mitigation:* warm during project open; honest progress UI.
4. **Android 11 (API 30) floor** may exclude part of the audience fleet. *Mitigation:* measure actual audience devices; only then weigh the grayer pre-30 resource path.
5. **Manifest-bearing features don't hot-load** (services, receivers, new permissions ⇒ shell rebuild + one install). *Mitigation:* explicit UX framing (A3/D3) so it reads as designed, not broken.
6. **OS policy drift** on dynamic code loading (targetSdk W^X tightening). *Mitigation:* the read-only-codeCache pattern already used is API-34-safe; track releases.
7. **Security: the shell executes whatever lands in its payload directory.** *Mitigation:* app-private payload dir + a CoGo↔shell digest handshake; needs a short threat-model doc before MVP.
8. **Fast-loop vs Gradle-build divergence** ("works in dev, breaks in the real APK"). *Mitigation:* differential harness — build both ways, diff artifacts/behavior (Gradle as oracle); keep in CI.
9. **Incremental-compile staleness** (silently wrong output). *Mitigation:* Kotlin's own IC engine + our verify-vs-clean harness (already caught two silent-fallback bugs); keep as regression test.
10. **OEM variance** — one Samsung device tested. *Mitigation:* add one non-Samsung device after A1 lands.

---

## D. Decision register — where A × B become decisions

*Each decision combines a priority (A) with evidence/options (B) and risks (C). Approving a row = locking that decision; each row then spawns its child tickets. Strawman resolutions below are proposals.*

| #      | Decision                            | A inputs    | B/C inputs                                | Strawman resolution                                          | Status |
| ------ | ----------------------------------- | ----------- | ----------------------------------------- | ------------------------------------------------------------ | ------ |
| **D1** | **Hardware floor + speed bar**      | A1, A4      | §2 benchmarks; B1-incremental; C1, C3, C4 | Android 11+; code ≤3 s & resources ≤1 s on a named low-end reference device (🟢 on A56-class); **incremental compile is MVP-blocking**; validate on reference device before lock | Open   |
| **D2** | **MVP app-type scope**              | A2          | B2.5; C2                                  | Views + Kotlin + Java + androidx at MVP; Compose fast-follow (plugin is small); Room via **KSP only**; games & manifest-component apps out | Open   |
| **D3** | **Change-type handling + UX**       | A3          | B1-tiers; B2.7; C5                        | Resource + code edits on the fast path; dependency changes may take ~10–20 s (rare, honest progress UI); manifest changes get an explicit "needs re-install" flow | Open   |
| **D4** | **MVP workflow**                    | A5          | B1-sub-windows; B2.1, B2.2, B2.4; C7      | Save → auto-reload; shell launched from CoGo Run; floating back-to-editor control; "kept last good build" indicator; state-reset in dev chrome; Ask-Claude deferred | Open   |
| **D5** | **Build-loop architecture in CoGo** | A1 (memory) | B2.1, B2.2, B2.7; C6, C7                  | Separate small warm-service process; app-private payload dir + digest handshake; non-resident Gradle provisioning | Open   |
| **D6** | **Debug story at MVP**              | A5          | B1-keep-DEX; B2.8                         | Debugger attach supported at MVP (mechanism is free); IDE source-mapping spiked early to confirm | Open   |

**Proposed process:** async comments on A/B/C (one week?) → facilitator updates strawmen → D rows locked one by one → each locked D row spawns child tickets (the ticket's "steps become child tickets" intent) → this doc becomes the decision record.
