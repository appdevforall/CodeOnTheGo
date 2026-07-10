# Incremental Kotlin compile — results + recipe (2026-07-10)

Implemented incremental compilation via the **Kotlin Build Tools API** (`CompilationService`)
and benchmarked it against full compile across app sizes. **The result changes the design
conclusion for large apps.** Harness: `compile-service/incremental/IncBench.java`.

## Headline: incremental time is ~FLAT with app size

Editing **one file**, warm compiler (Mac, Kotlin 2.0.21 BTA):

| App size | Full warm compile | Incremental (1-file edit) | Speedup | Correct? |
|---|---|---|---|---|
| 600 LoC | 289 ms | 129 ms | 2.2× | ✅ == clean build |
| 3,000 LoC | 500 ms | 91 ms | 5.5× | ✅ |
| 15,000 LoC | 1,047 ms | 82 ms | 12.8× | ✅ |
| 30,000 LoC | 2,231 ms | **71 ms** | **31.4×** | ✅ |

- **Full compile grows ~linearly with total LoC; incremental stays ~70–130 ms** (it recompiles
  ~the one changed file + ABI-affected downstream). So the win grows with app size — exactly
  where we need it.
- **Correctness verified at every size**: the incremental output classes are byte-identical
  (CRC) to a clean build of the edited sources. (This is the staleness check flagged as the
  hard-to-verify part of tar pit #2 — it passes.)
- Cold first compile still scales with size (~1.8 s at 600 LoC → ~5.4 s at 30k) but is paid
  **once per session** (warmup), then every edit is the flat incremental cost.

## Confirmed ON-DEVICE (Samsung A56, warm, 1-file edit)

Ran the same harness on the A56 (Exynos 1580, JDK 21, in-process BTA). **The flat-incremental
curve holds on real hardware:**

| App size | Full warm compile | Incremental | Speedup | Correct? |
|---|---|---|---|---|
| 600 LoC | 2,055 ms | 719 ms | 2.9× | ✅ |
| 3,000 LoC | 4,164 ms | 478 ms | 8.7× | ✅ |
| 15,000 LoC | 7,117 ms | 402 ms | 17.7× | ✅ |
| 30,000 LoC | 9,561 ms | **529 ms** | 18.1× | ✅ |

- **Incremental stays ~400–720 ms at every size** on the A56; full compile grows to ~9.6 s at 30k.
- A 30k-LoC edit: **full ≈ 9.6 s 🟠 → incremental ≈ 0.53 s.** Add the measured on-device
  d8+package+deploy (~0.5 s) → **total reload ≈ ~1 s even for a 30k-LoC app 🟢**, vs ~10 s+ 🔴
  without incremental.
- Cold first compile on-device: ~7 s (600 LoC) → ~24 s (30k) — paid **once per session** (warmup).

So incremental compile is the difference between "Son-of-Stubby only works for tiny apps" and
"it stays fast at real app sizes on the actual phone." **It's the lever that keeps the loop in the
🟢/🟡 band regardless of app size.**

## Memory-pressure proxy (A56, constrained JVM heap)

No Tailscale/low-spec device available, and a local emulator on an arm64 Mac runs Android at
near-native CPU speed (so it can't produce low-spec *compile times*). Better proxy: constrain the
JVM heap on the **A56 itself** — real slower-than-a-laptop arm silicon under memory pressure. This
isolates the memory axis (a genuinely low-spec CPU would add a further ~2× on top).

| App / heap | Full compile | Incremental | Notes |
|---|---|---|---|
| 15k @ 1500m | 7,117 ms | 402 ms | baseline |
| 15k @ 512m | 7,979 ms | 633 ms | |
| 15k @ 256m | 8,765 ms | 795 ms | |
| 15k @ 192m | 9,819 ms | 704 ms | |
| 30k @ 1500m | 9,561 ms | 529 ms | baseline |
| 30k @ 256m | 13,488 ms | 685 ms | |
| **30k @ 160m** | **18,573 ms** | **1,020 ms** | full ~2× slower under GC; incremental barely moves |

**Findings:**
- **kotlinc is memory-lean** — it did NOT OOM even at 160 MB for a 30k-LoC app; it just gets
  slower (GC-bound). So the failure mode under pressure is *slowdown*, not crash.
- **Full-compile time balloons under memory pressure** (30k: 9.6 s → 18.6 s from 1500→160 MB —
  it holds the whole app's compilation state and GCs heavily). On a genuinely tiny-RAM + slow-CPU
  device this trends toward the 🔴 "grab a coffee" zone.
- **Incremental barely degrades** (~0.5 s → ~1 s across the same squeeze) — its working set is ~one
  file, so there's little to GC. **Under memory pressure, incremental is not just faster but far
  more robust**, which is exactly the low-spec condition we care about.
- Caveat: this is the *memory* axis on mid-range silicon. A real low-spec CPU would scale all
  numbers up (~2–3×), making full-compile clearly unusable at scale and incremental the only viable
  path — but that specific data point still needs genuinely low-spec hardware.

## The recipe (and the gotcha that cost the most time)

Driving the Build Tools API for *genuine* incremental (not silent fallback to full):

1. **Jar set** (version-matched; ours = 2.0.21, from the Gradle cache):
   `kotlin-build-tools-api` + `kotlin-build-tools-impl` (has the `CompilationService`
   ServiceLoader impl) + `kotlin-compiler-embeddable` + `kotlin-stdlib` + `kotlin-reflect` +
   `kotlin-daemon-embeddable` + `kotlin-script-runtime` + **`kotlinx-coroutines-core-jvm`** +
   **`trove4j`** + `annotations`. (The last two are non-obvious runtime deps of the IC runner —
   without them it throws `ClassNotFoundException` mid-compile.) See `required-jars.txt`.
2. `svc = CompilationService.loadImplementation(classLoader)` → `useInProcessStrategy()`.
3. Snapshot the **fixed** classpath **once** (`calculateClasspathSnapshot(jar, CLASS_MEMBER_LEVEL)`
   → `saveSnapshot`). For us the classpath (android.jar + stdlib) never changes, so this is a
   one-time cost (~0.7 s).
4. Per compile: `makeJvmCompilationConfiguration()` → `makeClasspathSnapshotBasedIncremental…()`;
   set `rootProjectDir` + `buildDir`; then
   `useIncrementalCompilation(icWorkDir, changes, params, icCfg)` and `compileJvm(...)`.
5. **Tell it what changed:** `SourcesChanges.Known([editedFile], [])` — explicit changed-files
   (which an editor always knows) works reliably; `ToBeCalculated` fell back to
   `UNKNOWN_CHANGES_IN_GRADLE_INPUTS` (full compile) in our setup.
6. **⚠️ THE GOTCHA:** the `shrunkClasspathSnapshot` file you pass in
   `ClasspathSnapshotBasedIncrementalCompilationApproachParameters(cpSnaps, shrunk)` **must be the
   exact path the engine writes to** — `<rootProjectDir>/shrunk-classpath-snapshot.bin`. If you
   pass any other name, the engine writes the shrunk snapshot to its own path, your param reads
   empty, and **every build silently falls back to non-incremental** with reason
   `CLASSPATH_SNAPSHOT_NOT_FOUND` — compiles succeed and are *correct*, just never incremental.
   This is the "naive integration silently loses incremental" trap; only the per-build reason log
   (`isDebugEnabled=true` + capture the logger) reveals it.

## Hybrid PoC: incremental against a REAL resolved androidx classpath

Proved the D10 hybrid mechanism end-to-end with **no CoGo changes**: took **CoGo's actual
offline Maven repo** (`localMvnRepository.zip`, 266 artifacts), extracted `classes.jar` from
all 90 AARs (~20 lines of code — the hybrid's AAR-handling step), and compiled a payload that
genuinely uses androidx (`AppCompatActivity`, `ViewModel`/`LiveData`, core-ktx extensions,
`androidx.collection.LruCache`) through the **production `IncrementalCompiler`** against the
resulting 267-entry classpath — i.e., exactly what the warm loop would consume from CoGo's
Tooling-API sync.

| Phase | Mac | A56 |
|---|---|---|
| Classpath snapshot of 267 entries (one-time provisioning) | 5.8 s | 16.0 s |
| Cold full compile (3 androidx files) | 2.9 s | 11.4 s |
| **Incremental 1-file edit (steady)** | **~125 ms** | **~610–695 ms** |

**Findings:**
- **Real androidx resolves + compiles incrementally** through the exact production class. The
  hybrid's "consume Gradle's classpath" model works.
- **A second BTA gotcha found + fixed:** with a big classpath, per-build re-verification of all
  267 classpath snapshots dominated incremental cost (~2.3 s on the A56). Since our classpath is
  FIXED between provisions, `assureNoClasspathSnapshotsChanges(true)` (once the shrunk snapshot
  exists) skips it → **2.3 s → 0.6–0.7 s on-device (3.5×)**. Now in `IncrementalCompiler`; the
  service re-seeds on resource/classpath changes, which keeps the assumption honest.
- **On-device totals with a real classpath:** incremental ~0.65 s + d8/package/deploy ~0.5 s →
  **~1.2 s reload for an androidx app** 🟢/🟡 — vs ~12 s cold-recompile without incremental.
- Provisioning (16 s on-device) is the once-per-classpath-change cost — exactly the
  amortization the design predicts (D10).

## Integration into the on-device service (next)

Replace `KotlinCompileService.kotlinCompile()`'s raw `K2JVMCompiler.exec()` with the
`CompilationService` path above:
- Stage the 2.0.21 jar set alongside the service (device already has kotlin-stdlib/compiler for
  the old path; add build-tools-api/impl + coroutines + trove4j).
- Feed `SourcesChanges.Known` from the existing FileObserver/watch loop (it already knows which
  file changed).
- Keep the classpath snapshot + IC workdir warm across the session (like the current warmup).
- Re-run the A56 benchmark ladder (design §7) — expect the flat-incremental curve to hold, ~3–5×
  the Mac numbers.
