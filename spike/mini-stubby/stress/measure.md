# Stress-measurement runbook — reload latency vs payload size (ADFA-4128 phase 3, component F)

Audience: the MAIN AGENT (the only one allowed to touch the device / run adb).
Everything below is copy-paste-able from the spike root:

```sh
cd /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/adfa-4128-prototype/spike/mini-stubby
```

## What you're measuring

Save→deployed and save→rendered latency as the payload grows from S to XL.
XL exceeds the 64k-method dex limit, so the apk contains `classes.dex` +
`classes2.dex` — it additionally proves the shell loads multidex payloads.
The stress payload is a SCRATCH COPY at `build/stress-payload/` — the real
`payload/` is never touched.

## One-time setup

1. Shell installed (once): `sh tools/build_host.sh`
2. Orchestrator NOT required for this measurement. If it is not running,
   the shell's `/reloaded` POST (port 8377) fails silently — that only loses
   the DEVLOOP end-to-end line, not the on-device reload-ms. To get the
   end-to-end line without the orchestrator, run
   `adb reverse tcp:8377 tcp:8378` so the shell's `/reloaded` goes straight
   to the daemon. (With the orchestrator running, use the normal
   `adb reverse tcp:8377 tcp:8377` and it forwards to 8378 itself.)
3. Seed the scratch payload BEFORE starting the daemon (the daemon registers
   its watch roots at startup, so the tree must exist):

   ```sh
   python3 stress/gen_stress.py --size S --no-build
   ```

4. Start the daemon pointed at the scratch copy (own terminal; leave running):

   ```sh
   sh devloop/run_devloop.sh --payload build/stress-payload
   ```

   It does an initial full build + deploy of size S immediately.
5. Watch the device in another terminal:

   ```sh
   adb logcat -s MiniStubby MiniStubbyStress
   ```

## Per-size loop (S, M, L, XL)

For each size, in the spike root:

```sh
python3 stress/gen_stress.py --size M --no-build    # then L, then XL
```

The daemon sees the file changes, rebuilds warm, and deploys. Nothing else to do.

Caveat: generation writes hundreds of files; the daemon's 80 ms debounce can
fire a build mid-generation, which then fails (half-written sources) and is
followed by the real build. Ignore intermediate `DEVLOOP build failed` /
`javac error` noise and trust the LAST `DEVLOOP save→deployed` line per size.
If unsure, force a clean rebuild: `curl -X POST http://127.0.0.1:8378/rebuild`.

## Where the numbers appear

| Number | Where | Line looks like |
|---|---|---|
| Mac build stages + apk size + dex count | daemon terminal | `DEVLOOP save→deployed total=1234ms (javac=..ms d8=..ms aapt2=..ms pack=..ms deploy=..ms) apk=1234567B dex=2` |
| Device reload (detect→rendered) | `adb logcat -s MiniStubby` | the shell's reload log / status bar (`gen N … reload M ms`) |
| Save→rendered end-to-end (one clock, Mac-side) | daemon terminal | `DEVLOOP save→rendered end-to-end 1234ms (gen N, device reload M ms)` |
| Multidex classes actually LOAD on device | `adb logcat -s MiniStubbyStress` | `stress touch: 18/18 sampled classes loaded (of 700 generated) in N ms` |
| apkBytes/dexCount as the shell sees them | shell status bar + `/reloaded` body | per Component E (`"apkBytes":N,"dexCount":N`) |

Multidex verification (Mac side, per size — expect dex=1 for S/M/L, dex≥2 for
XL; measured on 2026-07-07 XL produced THREE dex files — d8's class-based
distribution splits before the hard 64k fill):

```sh
unzip -l build/devloop/payload-devloop.apk | grep -E 'classes[0-9]*\.dex'
```

On-device load proof for XL: the `MiniStubbyStress` touch line samples the
generated classes INCLUDING the last one (`C00699`), which lands in a secondary
dex — `18/18 … loaded` means classes from multiple dex files resolved through
the shell's DexClassLoader. `x/18` with x<18 means multidex load partially
failed — grab the `ClassNotFoundException` from logcat.

## Results table (fill in)

| Size | classes×methods (~defs) | strings | apk bytes | dex count | DEVLOOP total (ms) | javac (ms) | d8 (ms) | pack (ms) | deploy (ms) | device reload (ms) | e2e save→rendered (ms) | touch line OK? |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S  | 25×20 (~525)      | 100  | | | | | | | | | | |
| M  | 150×60 (~9,150)   | 1000 | | | | | | | | | | |
| L  | 400×120 (~48,400) | 3000 | | | | | | | | | | |
| XL | 700×120 (~84,700) | 6000 | | | | | | | | | | |

Reference Mac-side numbers measured 2026-07-07 (Mac Mini, flox jdk17,
build-tools 35.0.0), FieldSurvey base payload + overlay:

Standalone `gen_stress.py` build (cold javac/d8 subprocesses):

| Size | apk bytes | dex count (sizes) | total | aapt2 | javac | d8 |
|---|---|---|---|---|---|---|
| S  | 49,246    | 1 (61 KB)                       | 1,861 ms | 309 ms | 669 ms   | 876 ms   |
| M  | 243,285   | 1 (448 KB)                      | 2,330 ms | 277 ms | 858 ms   | 1,180 ms |
| L  | 805,050   | 1 (2.06 MB)                     | 4,937 ms | 130 ms | 1,349 ms | 3,408 ms |
| XL | 1,495,094 | 3 (1015 KB + 2.62 MB + 6.8 KB)  | 6,083 ms | 104 ms | 1,736 ms | 4,157 ms |

Devloop daemon (warm in-process javac/d8), same payloads, `--dry-run`:

| Build | total | javac | d8 | aapt2 | pack |
|---|---|---|---|---|---|
| XL cold (daemon startup) | 8,185 ms | 2,139 ms | 5,600 ms | 350 ms | 94 ms |
| S warm (regen while running) | 683 ms | 162 ms | 86 ms | 332 ms | 9 ms |
| XL warm (regen while running) | 3,610 ms | 1,336 ms | 1,703 ms | 374 ms | 93 ms |

Both paths produced byte-identical apks per size (S 49,246 B dex=1;
XL 1,495,094 B dex=3), and the S rebuild after XL correctly dropped back to a
single dex (no stale classes2.dex — the apk is repacked from the resource apk
every build).

## Repeat-reload variance (optional, per size)

To get >1 reload sample at a size without changing size, touch one generated
source and let the daemon redeploy:

```sh
touch build/stress-payload/java/app/payload/stress/C00000.java
```

## Cleanup

- Ctrl-C the daemon; it deployed the STRESS payload last, so restore the real
  one for anyone using the device afterwards: `sh tools/build_payload.sh`
  (or restart the daemon without `--payload`).
- Scratch + outputs live only under `build/` (gitignored): `build/stress-payload/`,
  `build/stress-out/`, `build/devloop/`.
