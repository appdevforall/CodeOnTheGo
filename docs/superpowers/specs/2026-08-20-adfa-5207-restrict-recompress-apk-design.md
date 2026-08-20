# Restrict recompressApk to the stage and release pipelines

**Ticket:** ADFA-5207 (revises ADFA-1886; relates to ADFA-1167, ADFA-1462, ADFA-1460)
**Branch:** `ADFA-5207`
**Date:** 2026-08-20

## Goal

Stop running the `recompressApk` task on feature-branch CI builds. Keep it where the
artifact is broadly consumed: the stage pipeline and the release pipelines. Local builds
already skip it and stay unaffected.

## Why

`app/build.gradle.kts` gates the debug `finalizedBy("recompressApk")` on
`GITHUB_ACTIONS == "true"`, so every push to every branch pays for it. Measured on the
self-hosted runner the task takes **213s** - 43% of the 8m21s `:app:assembleV8Debug` step
and about 30% of the whole 11m41s workflow.

ADFA-1886 enabled recompression for debug CI in January 2026 to cut Firebase download time
and storage. The task cost roughly 20s then, the figure ADFA-1462 had measured. ADFA-1167
replaced the compression with `advzip` in July 2026; that commit records the new cost as
"+~2.3 min", and it measures 213s today. The cost grew about **10x after** the decision to
enable it and the trade has not been revisited. ADFA-1886's own comment already describes
this shape: "we'll do the recompress in the new stage pipeline and the new main pipeline".

## What it costs

Branch-build APKs get larger by the amount ADFA-1167 measured: 76.72 MB -> 79.68 MB, so
**2.97 MB (3.7%)**. Stage and release APKs are unchanged, so the artifacts testers consume
broadly keep their current size.

## Current behaviour (verified against `stage` @ 28e00f1e1)

`app/build.gradle.kts:666`:

```kotlin
val isCiCd = System.getenv("GITHUB_ACTIONS") == "true"
```

Inside `afterEvaluate`, the release blocks finalize unconditionally:

```kotlin
tasks.named("assembleV8Release").configure {
	finalizedBy("recompressApk")
	...
}
```

while the two debug blocks gate on CI:

```kotlin
tasks.named("assembleV8Debug").configure {
	if (isCiCd) {
		finalizedBy("recompressApk")
	}
	doLast {
		if (isCiCd) { /* sets abi/buildName/noCompressExtensions */ }
	}
	if (!isCiCd) {
		dependsOn("assetsDownloadDebug")
	}
}
```

Three workflows run `:app:assembleV8Debug` on the self-hosted runner and therefore all pay
the 213s today:

| Workflow | Trigger | Ref it runs on | APK consumed by |
|---|---|---|---|
| `debug.yml` | push to any branch except `main` | the pushed branch | Firebase App Distribution (testers) |
| `analyze.yml` | daily cron + dispatch | default branch (`stage`) | nothing - SonarQube analysis only |
| `instrumentation-test.yml` | daily cron + dispatch | default branch (`stage`) | Firebase Test Lab devices only |

`release.yml` runs `:app:assemble${variant}Release` and is out of scope.

## Design: explicit workflow opt-in

Gate the debug recompression on a **presence-only Gradle property**, `-PrecompressDebug`,
and pass it from `debug.yml` only when the branch is `stage`.

```kotlin
/*
 * Whether a debug assemble is finalized by recompressApk. Release assembles
 * always are; debug is opt-in via -PrecompressDebug because the task costs
 * ~3.5 min on the runner and only pays off for APKs testers download broadly.
 * debug.yml passes the flag on stage; feature branches and the daily
 * scheduled builds do not.
 */
val recompressDebugApk = project.hasProperty("recompressDebug")
```

```yaml
- name: Assemble Universal APK
  run: |
    # recompressApk costs ~3.5 min and only pays off for APKs testers
    # download broadly, so only stage opts in (ADFA-5207).
    BRANCH_NAME=${GITHUB_HEAD_REF:-${GITHUB_REF#refs/heads/}}
    recompress=""
    if [[ "${BRANCH_NAME,,}" == "stage" ]]; then
      recompress="-PrecompressDebug"
    fi
    echo "gradle_time_start=$(date +%s)" >> $GITHUB_ENV
    flox activate -d flox/base -- ./gradlew :app:assembleV8Debug $recompress --no-daemon
    echo "gradle_time_end=$(date +%s)" >> $GITHUB_ENV
```

### Why a property and not a branch check inside the build script

The ticket's Scope section suggests gating on the branch inside `app/build.gradle.kts`
(reading `GITHUB_REF_NAME`). That was rejected: scheduled runs check out the default
branch, so `analyze.yml` and `instrumentation-test.yml` would still report
`GITHUB_REF_NAME=stage` and keep paying 213s each, twice a day, for an APK neither of them
distributes. An explicit opt-in is precise - a workflow gets recompression only by asking
for it - and it puts the CI policy in the workflow YAML, which CLAUDE.md already treats as
the authoritative record of CI invocations.

This is a deliberate, small widening of the ticket's stated scope: the two daily
workflows stop recompressing as a side effect of the opt-in design. Both build an APK that
is never handed to a tester.

### What is deliberately left alone

- `assembleV7Release` and `assembleV8Release` keep their unconditional `finalizedBy`.
- `isCiCd` stays in the file; it still guards `dependsOn("assetsDownloadDebug")` and the
  `useAdvzip` selection inside `fun recompressApk`. Keeping `useAdvzip` keyed to CI means a
  stage CI debug build still uses advzip exactly as today, and a developer invoking
  `recompressApk` standalone against a local debug APK still gets the Deflater path.
- The `Install advancecomp` step in `debug.yml` stays unconditional. It is
  `command -v advzip || apt-get install`, a no-op on a warm self-hosted runner, and keeping
  it guarantees advzip exists for any manual `recompressApk` invocation.
- `analyze.yml`, `instrumentation-test.yml`, and `release.yml` are not edited. They simply
  never pass the flag.
- No change to compression settings or the `-Pzopfli` option.

### Docs that move with the code

`ARCHITECTURE.md:85` (Native lib compression) describes the trap as "the `recompressApk`
post-step (release always, debug in CI only)" and says local debug APKs never run it. Both
halves become wrong with this change and are updated in the same commit.

## Verification

`app/build.gradle.kts` build logic has no unit-test harness in this repo, so the
instrument is Gradle's `--dry-run`, which prints the finalizer in the task graph without
executing anything:

```
$ GITHUB_ACTIONS=true flox activate -d flox/local -- ./gradlew :app:assembleV8Debug --dry-run --no-daemon
:app:assembleV8Debug SKIPPED
:app:recompressApk SKIPPED
```

Expected task-graph matrix after the change (count of `:app:recompressApk` lines):

| Task | `GITHUB_ACTIONS=true` | `+ -PrecompressDebug` | local |
|---|---|---|---|
| `assembleV8Debug` | 0 | 1 | 0 |
| `assembleV7Debug` | 0 | 1 | 0 |
| `assembleV8Release` | 1 | 1 | 1 |
| `assembleV7Release` | 1 | 1 | 1 |

## Measurement plan

Numbers come from this branch's own runs on the self-hosted runner, not from historical
stage runs. The first commit on the branch is a behaviour-preserving rename of the gate,
so baseline runs measure today's behaviour on today's runner.

1. Three baseline runs on the mechanical commit.
2. Three runs on the behavioural commit (feature-branch path, recompression skipped).
3. One run with the flag temporarily forced on for this branch, to prove the stage path is
   unchanged; the forcing commit is removed with `--force-with-lease` afterwards.

Per run, record: `recompressApk completed in <N>ms` from the log, the `Assemble Universal
APK` step duration, the workflow total, and the APK size. `debug.yml` already writes
`build_time_secs` and `apk_size_mb` to the `ci_perf` table via
`scripts/insert-ci-perf-data.py`.

Runs must be sequential: `debug.yml` starts with `styfle/cancel-workflow-action`, so
overlapping runs cancel each other.

## Rollback

One `git revert` of the behavioural commit. It restores `val recompressDebugApk = isCiCd`
and the unconditional gradle invocation. No data migration and no artifact
incompatibility - the only difference in the produced APK is its compression level, and
both forms are valid signed APKs.

## Out of scope

`recompressApk` is registered as `tasks.register("recompressApk") { doLast { ... } }` with
no declared inputs or outputs, so even on stage and release it always re-runs and can never
be `UP-TO-DATE` or `FROM-CACHE`. It also references `project` at execution time, which
blocks Gradle's configuration cache. Both are worth a follow-up ticket.
