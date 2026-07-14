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

## Why the real Main.kt takes ~1.7–2.2 s (not ~1.1 s) — probe attribution

`compile-service/incremental/MainProbe.java` — 3-way controlled full compile on the A56, warm:

| Condition (~617–692 LoC, warm) | median | min |
|---|---:|---:|
| **A** synthetic 692 LoC, stdlib-only (= benchmark) | 1282 ms | 943 ms |
| **B** same synthetic file **+ android.jar on classpath** | 885 ms | 798 ms |
| **C** the **real Main.kt** (Android framework + org.json) + android.jar | 1742 ms | 1439 ms |

- **A ≈ B → the big android.jar (27.7 MB) on the classpath is essentially free.** K2 resolves lazily;
  a file that references no Android types pays ~nothing for it. So it's *not* "the classpath is huge."
- **B → C = +857 ms → it's the CODE, not the size.** Real Android UI code (Activity/View/LinearLayout/
  Button member resolution, overload resolution on framework methods, `SharedPreferences`, `org.json`,
  string templates, lambdas, `when`) type-checks ~2× harder than an equal-length file of synthetic
  arithmetic. Same LoC, double the frontend work.
- **Plus a ~0.5–0.9 s fixed K2 init floor** baked into every *full* `K2JVMCompiler.exec` invocation.

So the 617-line Main.kt sits at ~1.4–1.7 s **warm full compile**; a cold first-compile or thermal spike
pushes it to the 2.2 s we saw this session. Note the daemon's real fast loop uses the **incremental** BTA
path (persistent compiler, amortizes the init floor) — the same Main.kt landed at **~0.66 s** warm-incremental
in the daemon log. Levers to cut it: keep the daemon warm on the incremental path, and **split Main.kt into
smaller files** so a per-file incremental recompile only touches the edited one.

## Can settings speed it up (file contents held fixed)? — no, ~5–10% at most

Swept heap, GC, kotlinc flags, and compiler version against the **same 617-line Main.kt**, warm,
on the A56 (`FixedBench.java` + `tools/ondevice/run_configbench.sh`; raw: `bench/configbench-ondevice.txt`).
All numbers are median (min) of 5, in ms. Run-to-run noise is ~±300 ms — **bigger than almost every effect below.**

**Heap / GC** (baseline flags):

| Config | median | min |
|---|---:|---:|
| `-Xmx512m` (current daemon) | 2108 | 1783 |
| `-Xmx1g` | 1981 | 1608 |
| `-Xmx2g` | 2090 | 1520 |
| `-Xmx2g -Xms2g` | **1890** | 1606 |
| `-Xmx2g +UseParallelGC` | 1934 | 1576 |
| `-Xmx2g +UseSerialGC` | 2183 | 1883 |

→ **Heap isn't the constraint** (512 m isn't starved). Pre-sizing `-Xms2g` + ParallelGC shaves maybe ~10%, within noise.

**kotlinc flags** (`-Xmx2g`):

| Flag | median | min |
|---|---:|---:|
| baseline | 2090 | 1479 |
| `-Xno-{param,call,receiver}-assertions` | 1973 | 1511 |
| `-Xbackend-threads=4` | 2429 | 1640 | ← *worse* (thread setup, no parallelism in 1 file) |
| `-language-version 1.9` | 2648 | 2188 | ← *worse* (~25%) |
| `-Xno-optimized-callable-references` | 2035 | 1723 |

→ Only assertion-elision helps, ~5%. No magic flag; `-Xbackend-threads` and older language levels *hurt*.

**Compiler version** (fixed file, `-Xmx2g`):

| Version | median | min |
|---|---:|---:|
| 2.0.21 (current) | ~2090 | ~1520 |
| 2.2.0 | 2026–2091 | 1491–1768 |
| 2.4.20-Beta1 | 2092–2311 | 1766–1884 |

→ **No improvement.** Newer K2 optimized *incremental / multi-module*, not single-file full-compile frontend throughput.

**Conclusion:** with the edit and file contents held constant, settings buy ~5–10% at best (≈2.1 s → ≈1.9 s),
**all inside the noise band.** The ~2 s is intrinsic: K2 frontend type-checking 617 lines of Android-framework
Kotlin + the ~0.5–0.9 s per-invocation compiler-init floor. The real levers are structural, not knobs:
1. **Shrink the changed file** (split `Main.kt`) — less frontend work, and enables `-Xbackend-threads` to *help*.
2. **Hit Tier 1** (body-only edit → ART redefine ~0.66 s, skips dex+package) instead of Tier 2.
3. **Amortize the init floor** — the BTA `compileJvm` re-inits the compiler env per call; a persistent compiler
   session (Kotlin daemon / more reuse across calls) is the one *compiler-side* lever, but it's an architecture
   change, not a flag. (Note: `FixedBench` uses `K2JVMCompiler.exec`, which has a *higher* floor than the daemon's
   BTA incremental path — so the daemon's real compile slice is a bit below these numbers; the *relative* "settings
   don't matter" conclusion transfers.)

## Which "compile" number is which (reconciling the figures)

There are **four different compile numbers** in this spike; they are not interchangeable:

| Path | What it measures | On-device (A56) |
|---|---|---|
| **Full compile, Java** (this doc) | javac a whole file from scratch | ~0.12–0.19 s, flat |
| **Full compile, Kotlin — synthetic** (this doc) | K2JVMCompiler, arithmetic, stdlib-only | ~0.6–1.3 s, grows w/ size |
| **Full compile, Kotlin — real Main.kt** (probe) | K2JVMCompiler, Android framework code | ~1.4–1.7 s warm (2.2 s cold) |
| **Incremental, Kotlin** (`INCREMENTAL-RESULTS.md`) | BTA `CompilationService`, 1-file edit, caches warm | ~0.4–0.7 s, **~flat** 600→30k LoC |

The **0.53 s** figure Bryan flagged as "not representative" was the *incremental* edit on a **30k-LoC** app
(the flat-incremental curve's endpoint) — a best case of a different mechanism, not full compile. The daemon's
real fast loop uses the **incremental** path; the curves here are **full compile**, which shows how a
from-scratch per-file build scales and how the two languages compare head-to-head.
