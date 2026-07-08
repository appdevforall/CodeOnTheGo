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
