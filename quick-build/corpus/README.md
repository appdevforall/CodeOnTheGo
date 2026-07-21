# Quick Build corpus + matrix runner

Drives the `quickbuild-daemon` jar against every app in `apps/`, applying each
app's edits in order and checking the result against what the edit declares it
should do. This is the edit-type x changeset test matrix made runnable, not
just a table.

## Layout

```
corpus/
  apps/<name>/          app.json, AndroidManifest.xml, src/main/{java,res,assets}
    edits/<NN>-<class>/ meta.json + replacement files, applied cumulatively
    vendor.json         (vendored apps only) pinned upstream {repo, commit, files}
  harness/run_matrix.py the runner (this doc)
  harness/fetch_vendored.py  materializes vendored apps into .cache/ (below)
  results/<timestamp>/  matrix.json + matrix.md + daemon-stderr.log, per run
  .cache/               gitignored: fetched upstream repos + materialized apps
```

Third-party source is **never checked in**. An app with a `vendor.json` (a
"vendored app", e.g. `sora-editor-lib`) keeps only scaffolding, edits, and the
manifest in the repo; its upstream files are pinned by `{repo, commit}` and
fetched into `.cache/` by `harness/fetch_vendored.py` before a run. Edits that
touch fetched files are checked in as `.patch` files against the pinned commit
and applied at materialization. `run_matrix.py` SKIPs a vendored app (an
environment gap, never a failure) until it has been materialized.

Apps compile against **only** `android.jar` + `kotlin-stdlib` (plain
`android.app.Activity` / `android.widget` views, no androidx) so the daemon's
compile op has a minimal, controlled classpath. The **large-real-app tier**
(`sora-editor-lib` -- see "Large-real-app tier" below) is the one
exception: it needs `--classpath-extra` for androidx jars, since it's real
upstream code from an actual open-source app rather than a synthetic
minimal-classpath fixture.

One deliberate exception: an app whose `app.json` has `"compose": true`
(currently `compose-kotlin`) additionally gets the `androidx.compose.runtime`
classes.jar on its classpath and the Compose compiler plugin passed to the
daemon's `configure.compilerPlugins` -- that is exactly the shape of the real
on-device Compose session, where the setup build reports `composeEnabled` and
CoGo passes the staged plugin jar. Both come from the `--compose-plugin-jar` /
`--compose-runtime-jar` flags; omitting either SKIPs the app's rows (an
environment gap, never a failure).

## Trying your own ideas

The corpus is meant to be extended by anyone with a hypothesis ("how do
quick builds handle X?"), and it is deliberately agent-friendly - every
input is a plain file, every result is a markdown/json artifact. The loop:

1. **Add an app** (new scenario): copy the closest `apps/<name>/`, adjust
   `app.json` + sources. `app.json` fields: `applicationId`, `entryActivity`,
   `minSdk` (30 across the corpus), `language` (`"kotlin"` / `"java"`), and
   optional `"compose": true`. Keep the minimal classpath (android.jar +
   kotlin-stdlib) unless the scenario genuinely needs more
   (`--classpath-extra`, below). To test against a real open-source app,
   don't copy its code in -- write a `vendor.json` pinning
   `{repo, commit, files}` (see `sora-editor-lib`) and let
   `fetch_vendored.py` materialize it.
2. **Add an edit** (new change-type): new `edits/<NN>-<class>/` dir with
   `meta.json` (see schema below) + the replacement files. Edits apply
   cumulatively in order.
3. **Run** the matrix (below; or on-device, next section) and read
   `results/<timestamp>/matrix.md`.
4. **Commit** the app/edit and the results dir alongside it - results are
   the evidence, they belong in the repo.

With Claude (any coding agent works the same): point it at this README and
ask, e.g. *"add a corpus app with a 500-string strings.xml and an edit that
changes one string; run the host matrix and compare relink time against
plain-kotlin"* - steps 1-4 are exactly what it will do. The runner's
oracles (below) tell it whether the result is correct, not just fast.

## Running it

```bash
python3 quick-build/corpus/harness/run_matrix.py \
  --android-jar "$(find "$ANDROID_HOME/platforms" -maxdepth 1 -name 'android-*' | sort -V | tail -1)/android.jar" \
  --kotlin-stdlib "$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib -name 'kotlin-stdlib-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort -V | tail -1)" \
  --aapt2 "$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -name '*.*.*' | sort -V | tail -1)/aapt2" \
  --d8-jar "$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -name '*.*.*' | sort -V | tail -1)/lib/d8.jar" \
  --javac "$(dirname "$(which javac)")/javac" \
  --daemon-jar quickbuild-daemon/build/daemon/quickbuild-daemon.jar \
  --compose-plugin-jar "$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable -name '*.jar' | sort -V | tail -1)" \
  --compose-runtime-jar /path/to/compose-runtime-classes.jar
```

For `--compose-runtime-jar`, extract `classes.jar` from an
`androidx.compose.runtime:runtime-android` AAR (the
`:quickbuild-daemon:stageComposeTestRuntime` task stages one at
`quickbuild-daemon/build/compose-test-runtime/compose-runtime.jar`). The plugin
jar version must match the daemon's bundled Kotlin compiler (the
`kotlin-composeCompilerPluginEmbeddable` catalog entry).

`$ANDROID_HOME` is your Android SDK root (`~/Library/Android/sdk` via Android
Studio on macOS, `~/Android/Sdk` via this project's flox env -- check both if
`$ANDROID_HOME`/`$ANDROID_SDK_ROOT` aren't set). Run needs a working `java` on
PATH (this project's daemon needs JDK 17: `flox activate -d flox/local`, per
the repo's CLAUDE.md, gets you one). On a fresh clone the `--kotlin-stdlib`
discovery command finds nothing (`~/.gradle/caches` is empty until a Gradle
build has run) -- after `stageDaemon` (next paragraph, needed anyway) a stdlib
jar is sitting right in `quickbuild-daemon/build/daemon/`, or point the flag at
any kotlin-stdlib jar.

**Build the runnable daemon layout with `./gradlew :quickbuild-daemon:stageDaemon`**
(what the run command's `--daemon-jar` path points at). The jar's manifest
`Class-Path` lists its runtime deps (kotlin-stdlib, kotlin-build-tools-api/impl,
gson, asm, and their transitives) by **file name only**, resolved relative to the
jar's own directory -- `stageDaemon` syncs the jar and those deps side by side
into `quickbuild-daemon/build/daemon/`. A bare `daemonJar` archive run from
anywhere else fails fast with `NoClassDefFoundError:
kotlin/NoWhenBranchMatchedException` (or similar) with no indication it's a
staging problem.

**Vendored apps need one extra step before the run**: `python3
quick-build/corpus/harness/fetch_vendored.py` (network required once per pinned
commit; the fetch is cached in `.cache/`). Skipping it just SKIPs those apps'
rows.

All flags except `--android-jar`/`--kotlin-stdlib` are optional:

- No `--daemon-jar` found on disk, or it fails a `ping` health check (e.g. no
  `java`, or the jar has no runnable manifest) -> the whole run degrades
  gracefully: every app/edit is marked `SKIPPED`, `matrix.json`/`matrix.md`
  still render (showing the intended matrix shape), exit code 0.
- No `--aapt2`, or no `javac` (via `--javac`, `PATH`, or alongside `--java-bin`)
  -> the harness can't generate an `R` class (see Limitations) -> baseline
  compile for every app is `SKIPPED`, not `FAILED` (an environment gap, not a
  code defect). `--javac` itself is optional -- omit it to auto-derive from
  `PATH` or from the directory `--java-bin` resolves to.
- No `--aapt2` also means every `resources`/`mixed` edit's relink is `SKIPPED`.
- No `--d8-jar` -> dex is never exercised (off by default anyway; see below).
- No `--compose-plugin-jar` or no `--compose-runtime-jar` -> every app with
  `"compose": true` in its `app.json` is `SKIPPED` (baseline + all edit rows).
- `--classpath-extra <jar>` (repeatable) adds jars to every app's compile
  classpath -- only the large-real-app tier needs it (androidx; see below).
- `--apps hello-kotlin,hello-java` restricts the run to named apps.
- `--exercise-dex` additionally calls `dex` once after each app's baseline
  compile, informational only (not gating).

Exit code: **0 unless some assertion actually `FAILED`** (`SKIPPED` never
fails the run -- it means a prerequisite was missing, not that behavior was
wrong).

The latest full-corpus host run -- all 11 apps / 40 edits, compose and
vendored tiers included -- is `results/20260719T181349Z/` (40/40 PASS, 0
skipped). Older results dirs predate some apps; each run's Config section
records exactly what it covered.

## Running it on-device

`harness/run_matrix_device.py` drives the SAME daemon protocol against a
physical Android test phone instead of a host JVM (real ARM compile/dex/relink
timings -- the surface the release benchmark gates measure). The daemon
launches via `adb shell run-as <pkg> sh <launcher-script>` (stdin/stdout flow
through adb transparently -- `DaemonClient` needs no changes). R class
generation still runs on the HOST (its output bytecode, JVM target 17, is
portable to the device's JDK 21) and gets pushed over; everything else -- the
corpus tree, each edit's changed files, the daemon's `classesDir` for CRC
snapshotting -- is pushed/pulled per-app under `/sdcard/qb-corpus` and
`/sdcard/qb-work` (plain `adb`, no `run-as` needed -- confirmed readable by
the app's own process too, since the launcher script itself lives under
`/sdcard`). Requires a prior one-time device setup, not automated by this
script: push the `stageDaemon` output (daemon jar + deps) to the device, and
push a launcher script to `/sdcard/qb-daemon.sh` that runs the jar with the
app's bundled JDK -- the exact on-device layout the script expects (tool
paths, app-private vs shared storage) is documented in
`run_matrix_device.py`'s module docstring.

```bash
python3 quick-build/corpus/harness/run_matrix_device.py \
  --host-android-jar "$(find "$ANDROID_HOME/platforms" -maxdepth 1 -name 'android-*' | sort -V | tail -1)/android.jar" \
  --host-aapt2 "$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -name '*.*.*' | sort -V | tail -1)/aapt2" \
  --host-javac "$(dirname "$(which javac)")/javac" \
  --serial <device-serial>
```

Device SDK tool paths (android.jar/aapt2/d8.jar/kotlin-stdlib) default to the
confirmed staged locations under the app's private storage
(`/data/user/0/<pkg>/files/home/...`) and can be overridden with
`--device-android-jar`/`--device-aapt2`/`--device-d8-jar`/`--device-kotlin-stdlib`
if a different device/build changes them. Results land in a sibling
`results/<timestamp>-device/` dir; `matrix.md` appends a device-vs-host median
compile-time comparison table (`--host-results <matrix.json>` to compute the
host side from a specific run instead of the hardcoded default).

**Scope note:** this measures `compile`/`relink` op latency (plus the repeated
`configure` reported as `reconfigureMs` -- there is no separate "reconfigure"
op in the protocol) -- it does not exercise `dex` or a real deploy/reload, so
it's a partial signal toward the edit-to-reload release gate, not a full
certification of it.

### Offline runs

Quick Build is meant to work with zero network (the whole hot loop is bundled
toolchain + local classpaths + binder IPC -- see the wrapper repo's
`offline-test-plan.md`). Add `--offline` to `run_matrix_device.py` to prove the
on-device matrix runs with the phone's radios off:

```bash
python3 quick-build/corpus/harness/run_matrix_device.py \
  --serial RZGYC24640P --offline \
  --apps assets-app,fanout-kotlin,hello-java,hello-kotlin,medium-kotlin,resources-heavy \
  ... (host tool flags as above)
```

What `--offline` does, in order:

1. Records the device's prior airplane-mode state
   (`adb shell settings get global airplane_mode_on`).
2. Enables airplane mode (`adb shell cmd connectivity airplane-mode enable`).
3. **Verifies connectivity is actually down** before running anything --
   `adb shell ping -c1 -W2 8.8.8.8` must fail (polled a few seconds while the
   radios drop). If ping still succeeds it aborts rather than run a matrix that
   would falsely claim to be offline.
4. Runs the matrix normally. `adb` rides USB debugging, not the cellular/Wi-Fi
   radios, so it's unaffected by airplane mode -- the device compiles/dexes/
   relinks with no network the whole time.
5. **Always restores the prior airplane-mode state in a `finally`** -- even on
   crash or Ctrl-C -- so an `--offline` run never leaves the phone stranded.
   (Note: a hard `kill -9`/SIGTERM of the runner skips Python's `finally`; run
   it detached rather than foreground-with-a-timeout, and if a run is killed,
   `adb shell cmd connectivity airplane-mode disable` restores it by hand.)

The run records `"offline": true` (and the restored prior state) in the results
`config` block, and `matrix.md` gets an **OFFLINE RUN** banner in its header.

Verified 2026-07-20 on the A56 (`RZGYC24640P`): the 6-app device subset passed
**23/23, output-equivalence PASS on all six, no skips**, in airplane mode --
parity with the online device run -- confirming the daemon's compile/dex/relink
pipeline is network-free on real hardware. Results:
`results/20260721T023009Z-device/`.

## meta.json schema

```json
{
  "editClass": "method-body",
  "description": "human-readable summary of what this edit does",
  "files": {
    "src/main/java/.../Greeter.kt": "Greeter.kt"
  },
  "expected": {
    "route": "code",
    "recompiledClasses": { "min": 1, "max": 2 },
    "behavioralMarker": "The quick build reloaded this method."
  }
}
```

- `files`: app-relative path -> replacement filename in this edit's own
  directory. Edits apply cumulatively in numeric order (`01-`, `02-`, ...),
  like a user editing over a session.
- `expected.route` drives which daemon op(s) the runner sends:

  | route | op(s) sent | what's asserted |
  |---|---|---|
  | `code` / `noop` | `compile` | ok:true, recompiled-class-count bounds, behavioral marker |
  | `resources` | `relink` (or `SKIPPED` if no `--aapt2`) | ok:true |
  | `mixed` | `compile` then `relink` | same as `code` + `resources` |
  | `assets` | none | recorded pass -- an asset-only save needs no daemon call |
  | `fallback` | none | recorded pass -- this changeset should trigger a full Gradle build, not a daemon op |

- `recompiledClasses: null` / `behavioralMarker: null` -> that assertion is
  skipped for the edit (used for `resources`/`assets`/`fallback` rows, which
  never touch the compiled class set).
- `editClass` is free-form EXCEPT for one convention: a `resources`/`mixed`
  edit that ADDS a resource must contain `add` in its `editClass` (e.g.
  `string-add`) -- that substring is what triggers per-edit `R` regeneration
  (see Limitations). Name it `new-string` and the `R` class silently goes
  stale, which then looks like a daemon bug.
- `expected.deploy` (optional, component-proxying apps -- see
  `quick-build/docs/component-proxying-design.md`): the restart-vs-recreate
  oracle -- `"restart"` when the edit's recompiled set intersects the restart
  closure (service/provider/custom-Application classes plus their user-side
  supertypes and nested classes), `"recreate"` for everything else (activity
  recreate / fresh-per-delivery receivers / resource overlay). Declarative
  only in this harness: `run_matrix.py` ignores it (the decision under test
  is CoGo-side, in `DeployPolicy` -- its JVM suite and the device walk
  consume these declarations; `service-app` and `receiver-provider-app` are
  the fixtures, both keyed on `expected.deploy`).

## The two oracles

1. **Per-edit**: `.class` file CRCs under the daemon's `classesDir` are
   snapshotted before and after each `compile`. The diff is the
   `recompiledClasses` set, checked against `expected.recompiledClasses`
   bounds. `expected.behavioralMarker` (a string literal the edit introduces)
   is searched for in the *recompiled* class files' raw bytes only -- so a
   marker sitting in a file the compiler decided NOT to recompile correctly
   fails the check.
2. **Output equivalence** (once per app, after its last edit): a from-scratch
   full compile of the final tree, in a fresh `outDir` (fresh IC caches), with
   a freshly regenerated `R` class on the classpath. Compared class-set +
   per-class CRC against the incremental run's final state. A real mismatch
   here means the incremental path produced different bytecode than a clean
   build would -- the strongest correctness signal this harness has.

## Limitations

- **Host-JVM only.** The daemon runs as a JVM child process on the machine
  running this script, not on-device. It exercises the same BTA/d8/aapt2
  pipeline the real quick-build feature uses, but timing numbers here are not
  the release benchmark gates (per-edit hot-reload latency and the vs-standard-
  Run ratio, measured on real phones -- see `results/phase1-gates-a56/`); those
  need devices.
- **The `R` class is generated by this harness, not the daemon.** The daemon's
  `relink` op only extracts `resources.arsc` (see
  `quickbuild-daemon/.../res/Aapt2Link.kt`) -- it never emits `R.java`. Every
  corpus app references `R.*` (even just via
  `android:label="@string/app_name"` in the manifest), so `compile` cannot
  succeed without one. Four things to know:
  - **How**: the harness shells out to `aapt2 compile` + `aapt2 link --java`
    directly (bypassing the daemon protocol) to produce `R.java`, then
    `javac`-precompiles it into a classes dir added to `configure`'s
    `classpath` -- **not** passed as a compile source. This is a
    **harness-only convenience**, not part of the protocol under test -- if it
    fails (aapt2/javac missing, or a genuine resource error in the app), the
    app's baseline is `SKIPPED` with a clear reason, not `FAILED`, and its
    edits are skipped too.
  - **Why classpath, not source**: `IncrementalCompiler` does give kotlinc
    visibility into raw `.java` sources for symbol resolution (added for the
    mixed-language corpus entry, `mixed-lang/`), but R.java is regenerated
    per-edit rather than being a stable project source, so precompiling it
    once onto the classpath is simpler than re-passing a fresh `.java` source
    every time.
  - **When it regenerates**: only for an edit whose `editClass` names a
    resource being ADDED (e.g. `string-add`) -- a value-only edit
    (`string-value`, `color-value`, `layout-edit`) leaves every existing
    `R.*` field name intact, so there's nothing to regenerate. The
    reconfigure's wall time is reported separately as `reconfigureMs`, not
    folded into `compileMs`.
  - **R-id churn caveat**: regenerating `R` from scratch does NOT guarantee
    stable resource IDs across edits (aapt2 isn't asked to preserve prior
    IDs) -- a real `R` field changing value is itself a compile-time constant
    change to every referencing class, same fan-out shape as a Kotlin
    `const val`. Treat an unexpected extra class in `recompiledClasses` for a
    resource-add edit as a possible R-id-churn artifact before assuming it's
    a daemon bug.
- **`dex` is not part of the per-edit assertions.** The protocol has a `dex`
  op, but recompiled-class-set correctness is checked directly against
  `compile`'s `classesDir`, not the dexed output. `--exercise-dex` smoke-tests
  `dex` once per app (informational, non-gating) when `--d8-jar` is given.
  Assets-route deploy-payload sizing (what dex would produce) isn't checked
  here at all.
- **`assets` and `fallback` routes are recorded, not independently verified.**
  The runner dispatches ops based on each edit's own declared
  `expected.route` -- it does not re-run CoGo's `ChangeClassifier` from
  Python to confirm that route is what the real classifier would choose for
  that changeset (that's `ChangeClassifierTest.kt`'s job, a separate JVM unit
  test in `:quick-build`). For `assets`/`fallback` there's no daemon op to
  assert against, so these rows just confirm the harness took the "no call"
  path the meta.json expects.
- **One daemon process, reused across the whole run.** Each app calls
  `configure` again (with a fresh `outDir`), which the daemon documents as
  replacing its session state -- cheaper than restarting the JVM per app, and
  matches the "warm daemon" spirit of the real feature.
- **Device benchmarks are a separate concern.** The release gates (p50/p95
  hot-reload latency vs a standard Gradle Run, memory/thermal, measured on
  real phones -- `results/phase1-gates-a56/`) are out of scope for this
  harness; it answers "is the result correct", not "is it fast enough on a
  phone".
- **The runner's own logic has a second layer of coverage**: a
  protocol-compatible fake daemon (same wire format, fake compilation) was
  used during development to verify dispatch, CRC diffing, assertion bounds,
  marker search, and the output-equivalence comparison independently of the
  real daemon. The checked-in `results/` dirs are real-daemon runs.

## Large-real-app tier

Two apps were picked (Bryan, 2026-07-16) to stress-test the daemon against
real, unmodified-in-spirit open-source Android code instead of hand-written
minimal fixtures: **sora-editor** (Akash's pick) and **StreetComplete**. This
section reports what actually happened trying to bring each one up.

### sora-editor-lib

`apps/sora-editor-lib/` -- real source from
[Rosemoe/sora-editor](https://github.com/Rosemoe/sora-editor)'s `:editor`
module, LGPL-2.1, pinned commit `43f7e8e298133248ce4128812092114f397a94f6`
(2026-07-13). **The upstream source is not checked in** (team decision,
2026-07-17: real-app code is referenced by pinned hash, never vendored into
this repo -- regardless of size). `vendor.json` pins the commit and maps the
13 upstream files to their app destinations; `harness/fetch_vendored.py`
shallow-fetches that exact sha (GitHub serves arbitrary-sha `--depth 1`
fetches) and materializes the complete app under `corpus/.cache/`. Edits 01
and 03 touch upstream files, so they are checked in as `.patch` files against
the pinned commit and applied at materialization; edit 02 targets our own
scaffolding and stays a plain replacement file.

**Two real blockers found, in order, each forcing a narrower scope than
originally planned:**

1. **The quick-build SETUP BUILD (QuickBuildPlugin applied via CoGo's real
   init-script mechanism) does not work on Gradle 9.x at all -- confirmed,
   not sora-editor-specific.** sora-editor's own `gradle-wrapper.properties`
   pins Gradle 9.5.1 (AGP 9.2.1). Reproducing CoGo's actual production
   plugin-injection wiring (`AndroidIDEGradlePlugin`/`AndroidIDEInitScriptPlugin`,
   the same `initscript { classpath ... } / pluginManager.apply("com.itsaky.androidide")`
   pattern `AndroidIDEInitScriptPluginTest` exercises) against sora-editor's
   `:app` module throws `UnknownPluginException` for `:app` itself -- the
   plugin ID never resolves, so `QuickBuildPlugin` never even gets a chance
   to check `AGP` compatibility. **Isolated the variable**: ran the *exact
   same* init-script mechanism against `gradle-plugin`'s own tested fixture
   (`gradle-plugin/src/test/resources/sample-project`, AGP 8.11.0 -- the
   plugin's own `AGP_VERSION_LATEST`) with Gradle 9.5.1 substituted for its
   normally-tested 8.x -- **same `UnknownPluginException` for `:app`**. This
   confirms the wall is Gradle-9-vs-the-init-script-classpath-propagation
   mechanism, not sora-editor's AGP version, its NDK use, or its project
   layout. `gradle-plugin`'s own JVM tests (`AndroidIDEInitScriptPluginTest`)
   only exercise Gradle 8.x/8.6 today, so this gap has no existing coverage.
   **This blocks quick-build's setup build for ANY project pinned to Gradle
   9+** -- a materially bigger blast radius than "can't use sora-editor,"
   since Gradle 9 is what new/actively-maintained projects increasingly pin.
   Not something this corpus entry can work around (it's a plugin-injection
   defect, not a project-content problem) -- filed here as the actionable
   finding; a fix belongs in `gradle-plugin`, not the corpus.
2. **The daemon cannot compile a module with MUTUAL (bidirectional)
   Kotlin<->Java symbol references -- a real, generalizable limitation
   beyond the already-documented R-class gap.** The existing "R class is
   harness-only, precompiled + put on classpath" workaround
   (see "Limitations" above) works because that dependency is one-directional
   (Kotlin/Java -> R, never the reverse). sora-editor's real `:editor` module
   is NOT one-directional: of its 73 Kotlin files, 50 are referenced by at
   least one Java file, and Java files are referenced back from Kotlin files
   too (e.g. `CodeEditor.java` from Kotlin event/style files) --  a genuine
   dependency cycle across the language boundary. Confirmed empirically: the
   full module fails baseline compile with `Unresolved reference 'CodeEditor'`
   in Kotlin sources (Kotlin compiles first per the daemon's own
   `IncrementalCompiler` architecture, so it never sees Java sources -- not
   even on the classpath route, since they haven't been compiled yet either).
   Precompiling the Java side first hits the mirror error (`cannot find
   symbol` for Kotlin-defined types). **Neither compile order works for a
   module with real cycles; this is not a workaround-able ordering problem.**
   This is a real gap for a large share of mature Android codebases, which
   very commonly mix Kotlin and Java with references running both ways.
3. **Corpus content, given (2):** rather than force a same-language-only
   slice through artificial restructuring, this entry pins a genuinely
   real, but small, **acyclic** subgraph of `:editor` --
   `text/{ContentLine,LineSeparator}.java` (the actual line-storage core) +
   their real leaf dependencies (`text/bidi/{TextBidi,Directions,IDirections,
   BidiRequirementChecker}.java`, `text/string/{StringLatin1,StringUTF16}.java`,
   `util/{Floats,IntPair,TemporaryCharBuffer}.java`, `util/ShareableData.kt`)
   -- 16 real classes, zero synthetic ones. `corpusharness/` (Java, so it can
   reference the vendored Java types directly -- see finding 2) is authored
   scaffolding: an `EditorHostActivity` giving the app an entry point, and a
   `SampleText` object for the sample-app-UI edit. Every fetched file keeps
   its original LGPL-2.1 header.

**3 scripted edits, all real sora-editor source except edit 02:**

| Edit | Target | What it exercises |
|---|---|---|
| `01-editor-core-value` | `util/Floats.java` | editor-core value edit -- method body + a new marker method |
| `02-sample-app-ui` | `corpusharness/SampleText.kt` (scaffolding) | sample-app UI edit -- a displayed constant, `const val` inlining forces the caller to recompile too |
| `03-cross-module` | `text/LineSeparator.java` + `text/ContentLine.java` | cross-module edit -- new editor-core API (`LineSeparator.describe()`) consumed by a new caller method in the same edit |

**Result: 3/3 PASS, output-equivalence PASS** (host run, 2026-07-17:
`results/20260717T061943Z/`, part of the full 7-app / 26-edit host run:
26/26 PASS, no regression on the existing 6 synthetic apps). Compile-op
latency across the 3 edits: 61ms, 103ms, 52ms -- **p50 = 61ms, p95 = 103ms**
(n=3; not a statistically meaningful percentile, but the raw numbers are in
the same ballpark as the synthetic corpus's small apps). Baseline compile
240ms for 16 classes.

Run command (androidx needed via `--classpath-extra`; everything else as in
"Running it" above):
```bash
python3 quick-build/corpus/harness/run_matrix.py \
  --classpath-extra <path>/androidx.annotation/annotation-1.3.0.jar \
  --apps sora-editor-lib \
  ... (other flags as usual)
```

**Gotcha hit and fixed while building this entry**: an XML *comment*
containing a literal `--` (used as a prose dash) makes the manifest
not-well-formed per the XML spec (`--` is illegal anywhere inside a
comment, not just adjacent to `-->`) -- `aapt2 link` fails with `not
well-formed (invalid token)` pointing at the comment line, which reads like
a resource-content problem rather than a comment-punctuation one. Cost real
debugging time; worth remembering for any hand-written manifest doc
comment.

### StreetComplete

**Not attempted yet.** Whoever picks it up: read the prior blocker analysis
first (Jira `ADFA-2745`, StreetComplete-in-CoGo, done 2026-02), then check
whether the two sora-editor findings above already predict the outcome -- a
Gradle 9+ wrapper pin hits the setup-build wall, and bidirectional
Kotlin<->Java references hit the compile wall -- once its Gradle/AGP version
and language mix are known. Bring it in the same way as sora-editor:
`vendor.json` + `fetch_vendored.py`, never vendored source.
