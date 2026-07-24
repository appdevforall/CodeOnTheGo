# Low-spec device farm runbook (ADFA-4128 P1a)

- **Status:** Prep-only tonight (2026-07-21) — no Tailscale access to the farm until Bryan
  sorts it with David. Everything below is push-button for that session: run these two
  scripts against each farm device, read the output, judge pass/fail per the bar below.
- **Scope:** the low-spec farm session only. The A56 keeps using its own already-staged
  `/sdcard/qb-daemon.sh` (see `corpus/harness/run_matrix_device.py`'s docstring) — it does
  not need `provision_device.py`, which exists for devices that have never run the
  quick-build harness before.

## Scripts

Both live in `quick-build/corpus/harness/`, run with plain `python3` (stdlib only, plus
`adb` on PATH).

### `provision_device.py` — one-time setup per farm device

```
python3 provision_device.py --serial <SERIAL>
```

What it checks/does, in order (fails loudly with the fix on the first thing missing):

1. `adb -s <SERIAL> get-state` == `device` (connected + authorized).
2. CoGo (`com.itsaky.androidide`) is installed.
3. CoGo's own JDK/SDK bootstrap is complete — the bundled JDK, `android.jar`, `aapt2`,
   `d8.jar` under the app's private storage. **This is CoGo's own first-launch unpack,
   not something this script does** — if missing, launch CoGo once on the device, wait
   for it to finish provisioning, then re-run.
4. The host has a built daemon: `quickbuild-daemon/build/daemon/` (from
   `flox activate -d flox/local -- ./gradlew :quickbuild-daemon:stageDaemon`). Fails with
   that exact command if missing.
5. Stages the daemon jars into the device's app-private
   `files/home/.cg/quickbuild/daemon/` (two-hop: `adb push` to `/data/local/tmp`, then
   `run-as` copies it into place — app-private storage isn't `adb push`-writable
   directly).
6. Writes `/sdcard/qb-daemon.sh` (the on-device launcher `run_matrix_device.py` and
   `bench_device.py` both spawn via `run-as ... sh /sdcard/qb-daemon.sh`).
7. Verifies the whole chain with a real `ping` round-trip through the daemon protocol.

Idempotent — safe to re-run against a device that's already partially or fully staged.

### `bench_device.py` — the benchmark protocol

```
python3 bench_device.py --serial <SERIAL> \
  --host-android-jar <sdk>/platforms/android-36/android.jar \
  --host-aapt2 <sdk>/build-tools/35.0.0/aapt2 \
  --host-javac <jdk>/bin/javac
```

Runs the 6-app host-comparable subset (`assets-app, fanout-kotlin, hello-java,
hello-kotlin, medium-kotlin, resources-heavy` — the same set `run_matrix_device.py`'s
device-vs-host table already has host medians for) through the real compile/relink
daemon ops, while sampling in the background:

- **Latency:** every edit's compile/relink ms (the same numbers `run_matrix_device.py`
  records), rolled up to p50/p95 per op.
- **Memory:** daemon process RSS (`/proc/<pid>/status` VmRSS, PID found via `ps -A`
  grepping for `DaemonMain`) plus whole-package `dumpsys meminfo` TOTAL PSS as a
  supplementary signal. PID discovery is best-effort and device-dependent — if a device's
  `ps` output doesn't expose it, per-process RSS reports "unavailable" rather than
  aborting the run; the package-level PSS number still lands.
- **Thermal:** a `dumpsys thermalservice` snapshot before and after the run (status code
  + label, Android's `THERMAL_STATUS_*` enum). A status that moved between snapshots is
  the signal that matters more than either single reading.

Writes `bench.json` (full data) + `bench.md` (copy-pasteable table) to
`quick-build/corpus/results/<timestamp>-bench/`.

## What "pass" means for the farm session

A device is a **pass** when:

- `provision_device.py` exits 0 (health check succeeded).
- `bench_device.py`'s matrix subset has zero `FAILED` edits (`SKIPPED` for a device that
  can't run some route is not automatically a fail — read the `gaps` list first).
- Compile p95 stays under a rough **3x** the A56's baseline for the same app (the A56 is
  itself a mid/low-tier device by design — see `docs/notes/` for its numbers — so 3x is
  "noticeably slower but still a live-reload loop," not "broken").
- No thermal status at or above `SEVERE` during the run.

A device is a **documented blocker**, not a silent fail, when `provision_device.py`'s
step 3 (CoGo's own bootstrap) can't complete — that's a device/CoGo-install issue, not a
Quick Build one, and belongs in the farm session notes as its own row.

## Memory-floor data (tonight, host + A56)

Per the overnight plan's P1a.2, a device-slot agent is running the corpus subset on the
A56 with the daemon launcher's `-Xmx` capped at 512m/384m/256m/192m tonight (see
`/sdcard/qb-daemon.sh`'s existing `-Xmx512m` — the launcher this repo already stages).
That table lands in the device-slot agent's own status file, not here — this runbook
only owns the farm-session protocol above. Cross-reference it before the farm session:
a heap cap that already fails on the A56 doesn't need re-discovering on weaker farm
hardware, it needs a raised floor recommendation.

## Pathological-case checklist (test STEPS ONLY — not run tonight)

No farm access tonight, so these are the steps for tomorrow's session, not results.

### Battery/power collapse under load

1. Provision the device (`provision_device.py`), confirm health check passes.
2. Unplug from charger; note battery percentage (`adb shell dumpsys battery`).
3. Start `bench_device.py` against the full corpus (not just the 6-app subset) so the run
   takes long enough to matter.
4. While it runs, watch `adb shell dumpsys battery` every ~30s for the percentage and
   `status` field. If the device hits Android's low-battery threshold mid-run, note:
   - Did the daemon process survive (still `ping`-able) or did the OS kill it?
   - Did in-flight compile/relink ops fail cleanly (`DaemonReply.Failed`) or hang past
     `--op-timeout`?
   - After the device is replugged, does a fresh `ping` succeed without re-provisioning?
5. Record: battery % at start/interruption/end, which edits were in flight, daemon
   survival, and whether `bench_device.py` itself exits cleanly or needs a kill.

### Thermal throttling

1. Provision the device, confirm a clean `thermal_snapshot` (status `NONE` or `LIGHT`).
2. Run `bench_device.py` back-to-back (2-3 consecutive invocations, no cooldown) against
   the full corpus to push the SoC into sustained load.
3. Watch the before/after thermal snapshots across runs for a status climbing toward
   `SEVERE`/`CRITICAL`.
4. If throttling is observed, compare that run's compile p50/p95 against an earlier
   unthrottled run on the SAME device — the delta IS the throttling cost, more useful
   than a cross-device comparison.
5. Record: which run first showed elevated status, the latency delta, and whether the
   daemon itself became unresponsive (vs. just slower).

### Storage-full

1. Provision the device normally.
2. Fill the device's `/sdcard` (and, if accessible, the app's private storage
   partition) to within a few MB of capacity — e.g. `dd` a large file, or install several
   large APKs, whatever's fastest on the specific device — leaving enough headroom to
   still run `adb shell` commands.
3. Attempt `provision_device.py` fresh against this device (or re-run `bench_device.py`
   if already provisioned) and observe:
   - Does the daemon jar push (`adb push` to `/data/local/tmp`, then `run-as cp`) fail
     with a clear "no space" error, or silently truncate?
   - Does `provision_device.py`'s health check correctly report a failure rather than a
     false pass?
   - Does an in-flight compile fail cleanly when its output classes/dex can't be
     written, or does it hang?
4. Free the space back up and confirm a subsequent clean run recovers (no leftover
   corrupt state from the full-disk attempt).
5. Record: the exact error surfaced at each step, and whether recovery after freeing
   space needed anything beyond a normal re-run.
