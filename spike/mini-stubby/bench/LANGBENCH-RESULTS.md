# Java vs Kotlin — full-compile time by file size (on-device A56, 2026-07-14)

Answers "benchmark compile by file size, Java vs Kotlin, 50–1000 LoC, plot the curves."
Harness: `compile-service/incremental/LangBench.java` · runner: `tools/ondevice/run_langbench.sh`
· plot: `bench/plot_langbench.py` → `bench/langbench-ondevice.png` · data: `bench/langbench-ondevice.csv`.

## Setup (fair, warm, in-process)

- **Device:** Samsung A56 (`SM-A566B`, Exynos 1580 / `s5e8855`), **CoGo's bundled JDK 21**, run as
  CoGo's uid — the exact JVM the live-reload daemon uses.
- **Both compilers in-process:** Kotlin via `K2JVMCompiler.exec` (kotlin-compiler-embeddable 2.0.21),
  Java via `javax.tools` system `javac`. Same warm JVM.
- **Matched code:** one self-contained source file of *real* code (arithmetic, string building, loops,
  branches) — identical method count per language, so LoC matches at each point.
- **Method:** global warmup per language, then per size **1 warmup + 5 timed**, median + min. The output
  dir is wiped *outside* the timed region — only compile time is measured. **Full compile from scratch.**

## Results

| File size (LoC) | Kotlin median (ms) | Java median (ms) | Kotlin / Java |
|---:|---:|---:|---:|
| 57   | 612  | 150 | 4.1× |
| 122  | 717  | 187 | 3.8× |
| 232  | 711  | 152 | 4.7× |
| 351  | 743  | 129 | 5.8× |
| 461  | 906  | 157 | 5.8× |
| 692  | 1119 | 121 | 9.2× |
| 921  | 1084 | 144 | 7.5× |
| 1152 | 1263 | 157 | 8.0× |

![curves](langbench-ondevice.png)

## Takeaways

1. **javac is ~flat and cheap:** ~120–190 ms for *any* file 50→1150 LoC on-device. javac's cost is almost
   all fixed overhead; the source-size term is negligible in this range.
2. **kotlinc grows with size and has a much higher floor:** ~0.6 s at 57 LoC → ~1.3 s at 1150 LoC, on a
   ~500 ms fixed floor (K2 frontend + backend init per invocation). Full Kotlin compile is **4–8× slower
   than Java**, and the gap widens with file size.
3. **Design implication for the live-reload loop:** per-*file* full recompile is the daemon's granularity,
   so a big Kotlin file (our 617-line demo `Main.kt`) sits in the ~0.9–1.3 s band — matching this session's
   observed 0.7–2.2 s kotlinc times. Keeping edited files small, or splitting hot files, directly cuts the
   loop time. Java payloads would reload markedly faster, but the plugin/app ecosystem is Kotlin.

## Which "compile" number is which (reconciling the three figures)

There are **three different compile numbers** in this spike; they are not interchangeable:

| Path | What it measures | On-device (A56) |
|---|---|---|
| **Full compile, Java** (this doc) | javac a whole file from scratch | ~0.12–0.19 s, flat |
| **Full compile, Kotlin** (this doc) | K2JVMCompiler a whole file from scratch | ~0.6–1.3 s, grows w/ size |
| **Incremental, Kotlin** (`INCREMENTAL-RESULTS.md`) | BTA `CompilationService`, 1-file edit, caches warm | ~0.4–0.7 s, **~flat** 600→30k LoC |

The **0.53 s** figure Bryan flagged as "not representative" was the *incremental* edit on a **30k-LoC** app
(the flat-incremental curve's endpoint) — a best case of a different mechanism, not full compile. The daemon's
real fast loop uses the **incremental** path; the curves here are **full compile**, which shows how a
from-scratch per-file build scales and how the two languages compare head-to-head.
