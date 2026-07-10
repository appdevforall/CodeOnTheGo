# On-device compile-loop benchmark — Son-of-Stubby on the A56 (2026-07-10)

The whole edit→reload compile loop (**kotlinc + d8 + aapt2 + deploy**) now runs **on the
Samsung A56 itself**. The only things left on the Mac are the **orchestrator + headless
Claude** (the "what to build" brain) — exactly the target split. Sources are pushed to the
device; everything else — compile, dex, resource-link, package, and the deploy into the
shell's watched dir — happens on the phone. Deploy is a local `run-as cp` (no `adb`).

## Device
- **Samsung Galaxy A56** — Exynos 1580 (`s5e8855`), 8 GB RAM (~460 MB free during the run,
  i.e. under real memory pressure), Android 16.
- Toolchain staged under `/data/local/tmp/mstc`: CoGo's bundled **JDK 21** (aarch64),
  `kotlin-compiler.jar` + `d8.jar` + `android.jar`, arm64 **aapt2**, and the Termux
  `zip` + runtime `.so`s. Same `KotlinCompileService` bytecode as the Mac, unmodified.

## Results (payload = the 617-line lemonade game, realistic size)

| Phase | On-device (A56) | Mac (for reference) |
|---|---|---|
| **One-time setup** (aapt2 + R + stdlib-dex) | 15.9 s | ~3.3 s |
| **Warm-up** (first, cold kotlinc) | 11.2 s | ~5.3 s |
| **TIER 2 warm** (full compile+dex+reload) | **~3.0 s** (2.9–4.2, settling to ~3.0) | ~0.8–3.0 s |
| ├ kotlinc | ~2.3 s (dominant) | ~0.4 s |
| ├ d8 (dexing) | ~0.2–0.4 s | ~0.03 s |
| ├ package (zip) | ~0.25 s | ~0.08 s |
| └ deploy (local cp) | ~0.18 s | ~0.25 s (adb) |
| **TIER 0 warm** (resource-only, no compiler) | **~0.75 s** (0.73–1.37) | ~0.6 s |
| **Shell reload** (detect→rendered, on-device) | **~40 ms** | ~50–130 ms |

## Takeaways

1. **It works, on-device, under memory pressure.** A phone with ~460 MB free ran the warm
   compiler + d8 + aapt2 loop repeatedly without OOM. Reloads landed on screen (logcat
   `RELOADED gen 13/14 · ~40 ms`).
2. **Warm TIER 2 ≈ 3 s; warm TIER 0 ≈ 0.75 s.** ~3–4× the Mac, and the gap is almost
   entirely **kotlinc** (2.3 s vs 0.4 s) — ARM cores compiling Kotlin. d8/dexing is cheap
   (~0.3 s); it is NOT the bottleneck. So "keeping DEX" (the Son-of-Stubby choice) costs
   very little; the JVM-based dex-free Stubby would save ~0.3 s while adding enormous
   complexity.
3. **This clears the "is JRebel worth it?" question for now.** JRebel-style method-body
   hot-swap only helps *structural* edits apply sub-second, and warm TIER 2 is already ~3 s
   with correct repaint + persisted state. Not worth the Instant-Run-class complexity at
   these numbers. (Revisit only if a much larger app pushes kotlinc past ~10 s/edit.)
4. **Biggest lever if we want it faster: kotlinc.** Kotlin incremental compilation (only
   recompile changed files) or the Kotlin compile daemon would cut the 2.3 s. d8 and aapt2
   are already negligible warm.
5. **First-run cost is real** (~16 s setup + ~11 s cold compile) but it's paid **once per
   session** — every subsequent edit is warm. In CoGo this warms while the user reads/edits.

## Production notes
- On the A56 this used `/data/local/tmp` + `run-as` (works because the shell is debuggable).
  In CoGo proper the service runs **inside the CoGo process** with CoGo's own JDK/SDK
  already on disk — no staging, no `run-as`, no `adb`. The measured compile/dex/link/deploy
  costs are the same; only the plumbing changes.
- The Mac orchestrator still drove nothing here — these numbers are the phone standing alone
  for the whole toolchain. Claude/orchestrator only chooses *what* to edit.
