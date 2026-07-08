# On-device compile feasibility (A56, Android 16, CoGo's bundled toolchain)

Run 2026-07-08 via `run-as com.itsaky.androidide` using CoGo's OWN bundled
JDK 21 (`files/usr/lib/jvm/java-21-openjdk`), CoGo's on-device android.jar
(`files/home/android-sdk/platforms/android-36/android.jar`), and d8.jar from
build-tools 35 — i.e. exactly what a devloop daemon hosted inside CoGo would use.

Workload: the phase-1 payload (Main.java + R.java → dex), ONE warm JVM,
javac via javax.tools in-process, D8 via its Java API in-process
(`bench/WarmCompileBench.java`).

| iter | javac | d8 | total |
|---|---|---|---|
| 1 (cold JIT) | 807 ms | 2239 ms | 3046 ms |
| 2 | 315 ms | 158 ms | 474 ms |
| 3 | 162 ms | 139 ms | 301 ms |
| 4 | 225 ms | 132 ms | 357 ms |
| 5 | 103 ms | 188 ms | 291 ms |

aapt2 (subprocess, cold, compile+link vs android-36, --package-id 0x80): **1239 ms**
— paid only when res/assets change; aapt2 daemon mode (what AGP uses) would cut it.

## Conclusion

**~300 ms warm compile on the phone itself.** On-device the deploy is a local
file write (no adb) + the shell's ~30 ms reload, so a CoGo-hosted warm daemon
projects to **~350 ms save→rendered for code edits** — comfortably inside the
<1 s target — and ~1.5 s for resource edits (improvable). The <1 s loop does NOT
need the Mac; it needs a persistent compile process inside CoGo (CoGo already
hosts long-lived JVMs — the Gradle Tooling API server — so this is an
established pattern in the codebase).

## On-device KOTLIN warm compile (A56, CoGo's bundled JDK 21) — 2026-07-08

Ran the Kotlin compiler (`K2JVMCompiler`, Android Studio's kotlinc jars) IN-PROCESS
on the A56 via CoGo's bundled JDK 21 — fresh compiler per request, warm JVM (the
Kotlin-daemon model). Workload: a small single-file Kotlin payload (`bench/KotlinWarmBench.java`).

| iter | kotlinc |
|---|---|
| 1 (cold JVM + compiler classload + JIT) | 6596 ms |
| 2 | 739 ms |
| 3 | 742 ms |
| 4 | 533 ms |
| 5 | 496 ms |
| 6 | 348 ms |
| 7 | 530 ms |
| 8 | 342 ms |

**Warm steady-state ≈ 350–550 ms** for a single-file Kotlin compile on the phone.
Cold start is ~6.6 s, paid ONCE when the compile service spins up.

### This revises the earlier pessimistic correction

The Mac "0.6–0.9 s" Kotlin figure was inflated by Gradle task-graph overhead. The bare
warm compiler on-device is ~400 ms — *faster* than the Mac-through-Gradle number.
Combined with warm d8 (~30–200 ms, earlier in this file) + on-device deploy (local file
write + ~40 ms shell reload), a CoGo-hosted warm Kotlin service projects to roughly
**0.5–0.8 s save→rendered for single-file Kotlin edits — i.e. within/near the <1 s
target**, not the 1–1.5 s I estimated from the Mac. The requirement is a *persistent*
in-process Kotlin compile service (keep one warm JVM with the compiler loaded; recompile
only changed files); a per-save `kotlinc` cold start (6.6 s) or per-save Gradle would blow
the budget.

Residual: **Compose** adds the compose-compiler plugin on top of kotlinc (not measured
on-device here — needs the plugin wired into a headless invocation). Expect Compose
single-file compile to be somewhat higher than plain Kotlin, but the same warm-service
architecture applies.
