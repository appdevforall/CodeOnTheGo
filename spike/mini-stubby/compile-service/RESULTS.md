# Persistent Kotlin compile service — results (ADFA-4128)

Device: Samsung A56, Android 16 (post One UI 8.5 update). Date: 2026-07-08.

The piece that cashes the sub-1s loop for Kotlin. A long-lived process
(`compile-service/src/KotlinCompileService.java`) that keeps everything expensive warm
and pays only the incremental cost per save.

## What it holds warm (paid once at startup)

1. **A warm JVM with the Kotlin compiler loaded + JIT'd.** Cold Kotlin compile is 6.6 s
   on the A56 (bench/RESULTS.md); this is why per-save `kotlinc` or per-save Gradle is fatal.
2. **The resolved compile classpath** (android.jar [+ dependency jars]).
3. **The kotlin-stdlib DEX, dexed ONCE and reused as `classes2.dex`.** The warm loop only
   re-dexes the changed app class into `classes.dex` — the incremental-dex model. (The shell
   loads the resulting multidex payload; app `classes.dex` cross-references the cached stdlib
   `classes2.dex`.)
4. **The linked resources apk + generated R.class** (rebuilt only when `res/` changes).

## Per-save warm path

`kotlinc(changed .kt)` → app `.class` → warm in-process d8(app classes only) → `classes.dex`
→ package [cached resources.apk + classes.dex(app) + classes2.dex(cached stdlib)] → deploy.

Fresh `K2JVMCompiler` per request (not reentrant) but the JVM stays warm — the Kotlin-daemon
model minus Gradle overhead.

## Measured — Mac warm loop (deploy skipped)

`save→deployed total=219–365 ms` — kotlinc **138–275 ms**, incremental d8 **12–17 ms**,
repackage ~70 ms. (kotlinc here is Mac-fast; on the A56 the same compile is ~350–550 ms
per bench/RESULTS.md.)

## Measured — full loop driving the A56 (deploy on, one clock)

Warm-up (cold compiler, once): ~1.9 s. Then, live edits of `payload-kotlin/Main.kt`:

| save→deployed total | kotlinc | d8 | pack | deploy (adb push) | device reload |
|---|---|---|---|---|---|
| 565 ms | 231 | 15 | 75 | 242 | 52 |
| 533 ms | 194 | 15 | 64 | 258 | 51 |
| 585 ms | 183 | 17 | 68 | 315 | 53 |
| 533 ms | 184 | 14 | 64 | 269 | 56 |

**True save→rendered ≈ 550–650 ms warm, over adb.** Verified end-to-end: the shell POSTs
`/reloaded` back to the service (via `adb reverse tcp:8377 tcp:8378`), so the numbers are on
one clock. The Kotlin payload renders live on the A56 (title, stdlib-built list, tap counter),
and editing `HEADLINE` hot-reloads the on-screen version in ~0.6 s.

## The on-device projection

Of the ~550 ms, **~270 ms is `adb push`** — an artifact of the Mac being in the loop. When
CoGo hosts this service *on the device*, the deploy is a **local file write** (single-digit ms)
and the compile is the on-device kotlinc (~350–550 ms warm, bench). Net on-device projection:

    kotlinc ~400 ms + d8 ~15 ms + pack ~70 ms + local write ~5 ms + shell reload ~50 ms
    ≈ 0.5 s save→rendered

So the persistent compile service turns the projection into a running, measured loop, and the
on-device number lands around **half a second for a Kotlin edit** — within the <1 s target.

## What this is NOT (honest scope)

- It's a **spike**: it recompiles the whole (one-file) payload each save rather than doing
  true per-file incremental compilation with a dependency graph. For a multi-file real app,
  the production version needs incremental compilation (compile only changed files + their
  dependents) — the Kotlin compiler supports this; wiring it is real work. The warm-JVM +
  cached-stdlib-dex + incremental-app-dex architecture is the load-bearing part and it's proven.
- It runs on the Mac driving the device via adb. The on-device port is the natural next step:
  the bench already proved the Kotlin compiler runs on the A56 with CoGo's bundled JDK 21;
  this service is the watch+compile+dex+package+deploy loop around it. CoGo already hosts a
  long-lived JVM (the Gradle Tooling-API server), so a resident compile service fits the codebase.
- Compose uses the same architecture plus the compose-compiler plugin; latency is the same
  ballpark as Kotlin (bench/RESULTS.md), so this service generalizes to Compose with the plugin
  added to the held compiler config.

## Run it

```sh
compile-service/run_service.sh              # deploy on (needs the shell installed + a device)
DEPLOY=false compile-service/run_service.sh # dry-run, Mac-only timing
# then edit spike/mini-stubby/payload-kotlin/src/app/payload/Main.kt and save
```
