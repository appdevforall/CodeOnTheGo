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

Re-run at full breadth 2026-07-22 on the same device: **11 apps / 43 edits,
43/43 PASS, output-equivalence PASS on all 11, 0 skipped**, airplane mode ON
throughout (`results/20260722T060635Z-device/`). That is every app the device
runner can host today -- `compose-kotlin` and `sora-editor-lib` are the two it
cannot, because `run_matrix_device.py` has no `--compose-plugin-jar` /
`--compose-runtime-jar` / `--classpath-extra` equivalents (their jars would also
have to be staged on-device). Adding them is the one remaining gap between the
host and device matrices; until then those two apps are host-only, which is a
harness limitation, not an offline finding.

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
2. ~~**The daemon cannot compile a module with MUTUAL (bidirectional)
   Kotlin<->Java symbol references.**~~ **RESOLVED, and the original finding was
   wrong about the mechanism** (2026-07-22, WS-D). It was recorded on 2026-07-17,
   one day before `IncrementalCompiler` started passing `.java` sources into
   kotlinc's own source list for symbol resolution (the `mixed-lang` entry). With
   that in place the daemon does Gradle's two-pass shape -- kotlinc resolves
   against raw Java sources, then javac compiles Java against kotlinc's output --
   which handles cycles by construction, because neither language is compiled
   "first" in the sense the finding assumed.

   Re-tested against the **whole** `:editor` module at the same pinned commit
   (`sora-editor-full`, below): **287 upstream files -- 73 Kotlin, 214 Java, with
   references running both ways -- compile to 457 classes, output-equivalence
   PASS.** The specific error the finding cited, `Unresolved reference
   'CodeEditor'` in Kotlin, does not occur; a Kotlin harness activity calling
   `CodeEditor` directly is part of the entry precisely to keep that pinned.
   The genuinely blocking finding for that first attempt was (1), the Gradle 9
   setup-build defect, which still stands.

   What remains is a cost, not a wall: the incremental engine has no dependency
   tracking over `.java` sources, so an edit that moves a Java **ABI** recompiles
   every Kotlin source in the module. Body-only Java edits (the common case) stay
   on the fast path via `JavaSourceAbi` fingerprinting. See
   `IncrementalCompiler.kotlinFilesToCompile` for why the remaining conservatism
   is not narrowed further.
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
   its original LGPL-2.1 header. **This entry is kept as-is** now that finding 2
   is resolved: `sora-editor-full` covers the whole module, and this one stays
   useful as the small, fast tier over the same real code. (Its scaffolding
   being Java "so it can reference the vendored Java types" is no longer a
   constraint, just how it was written.)

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

**4 more edits, PR/commit-mined (2026-07-21, WS-A3):** the ask was "convert
each selected PR's diff into a `.patch` against the pinned commit." That
literal mechanic turns out to be **structurally impossible for any already-
merged historical PR**: `fetch_vendored.py` checks out the file at the SAME
pinned commit for every edit's base, and a pinned commit by definition
already contains the result of every PR merged before it -- confirmed
empirically for all 3 candidates below (their "after" diff lines are already
present verbatim in the pinned commit's file). So "apply PR #N's diff forward
against the pinned baseline" is a no-op/reject for anything that landed
before the pin, and there is no PR merged *after* the pin
(`43f7e8e2981...` = `2026-07-13T04:30:30Z`, itself the merge of PR #855 --
checked all 334 merged PRs in the repo's history via `gh pr list --state
merged --limit 500`, none merged later). **What these edits actually do**:
reproduce the exact real transformation as a fresh, original edit in the
same file/spirit, since the historical version is unusable as a literal
forward patch. Each `meta.json.description` names the real PR/commit,
explains why a literal replay was impossible, and states what the edit
does instead.

| Edit | Target | Real PR/commit modeled after | What it exercises |
|---|---|---|---|
| `04-charposition-copy-ctor` | `text/CharPosition.java` (new subgraph file) | PR [#267](https://github.com/Rosemoe/sora-editor/pull/267) "Added more keybindings" (2022-10-18) -- added CharPosition's ctor overloads, already baseline at the pin | grows the same overload set with a copy constructor |
| `05-intpair-swap` | `util/IntPair.java` | commit `89fe30473e88` "docs: update the doc of `IntPair`" (2025-12-13, direct maintainer commit, no associated PR -- confirmed via `gh api repos/.../commits/{sha}/pulls` returning empty) | grows IntPair's small doc'd-helper set with `swap()` |
| `06-textbidi-explicit-direction` | `text/bidi/TextBidi.java` | commit `192c935699` "feat: no longer use enforced LTR base direction" (2025-11-10) | continues the same "stop enforcing a fixed direction" idea with an explicit-flag overload |
| `07-stringlatin1-appendto-perf` | `text/string/StringLatin1.java` | commit `10370f833f4` "perf: avoid copying when create `String` in Latin1 implementation" (2026-03-03) | applies the same `ISO_8859_1`-direct-decode perf fix to the sibling method `appendTo()`, which the real commit didn't touch |

`CharPosition.java` is a new addition to `vendor.json`'s `files` (verified
acyclic before adding: only imports `androidx.annotation.NonNull`,
`java.util.Objects`, `io.github.rosemoe.sora.util.IntPair` -- all
already-resolvable within the subgraph or external).

**Result: 7/7 PASS** (3 original + 4 new), **output-equivalence PASS**, host
run 2026-07-21: `results/20260722T055622Z/` (`sora-editor-lib` alone) and
`results/20260722T055735Z/` (full 13-app / 52-edit host run, all PASS, no
regressions). Compile-op latency for the 4 new edits: 383ms, 415ms, 296ms,
431ms.

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

`apps/streetcomplete-lib/` -- real source from
[streetcomplete/StreetComplete](https://github.com/streetcomplete/StreetComplete),
GPL-3.0, pinned commit `26c673030a095e7a8b32f1074b7014f960e2b0a4` (tag
`v63.2`, 2026-07). Brought in 2026-07-21 (WS-A3), after checking Jira
`ADFA-2745` (StreetComplete-in-CoGo, done 2026-02) and its linked Confluence
page ("Building StreetComplete in Code On the Go").

**Neither of sora-editor's two blockers apply here** -- a materially
different, better outcome than assuming they would:

1. **Gradle 9+ setup-build wall: does not apply.** StreetComplete's
   `gradle/wrapper/gradle-wrapper.properties` pins **Gradle 8.14**
   (confirmed via `gh api repos/streetcomplete/StreetComplete/contents/...`),
   matching CoGo's own bundled 8.14.3. ADFA-2745's Confluence page confirms
   this empirically: `sh gradlew assembleDebug` on-device used the project's
   own 8.14 wrapper without incident.
2. **Bidirectional Kotlin<->Java: does not apply.** StreetComplete is
   **100% Kotlin** -- `gh api repos/.../languages` reports
   `{"Kotlin":5195580,"HTML":365566,"Swift":712}`, zero Java, confirmed
   independently by a full recursive tree listing at the pinned tag (1717
   `.kt` files, 0 `.java`). A pure-Kotlin module has no K<->J compile-order
   wall at all -- kotlinc resolves same-language cycles in one pass, unlike
   the two-compiler-pass problem that blocked sora-editor's `:editor` module.

**StreetComplete DOES have its own real wall, found in ADFA-2745 -- but it's
in the SETUP/plugin-resolution path, not the daemon's compile/relink
protocol this corpus exercises.** Per the ticket + Confluence page: the
CoGo GUI build failed with `Plugin [id: 'org.gradle.kotlin.kotlin-dsl',
version: '5.2.0'] was not found` resolving `buildSrc/build.gradle.kts`
against CoGo's offline `localMvnRepository` (an incomplete-offline-closure
gap, the same shape as other gaps catalogued in
`docs/process/learnings.md`), and separately, CoGo's local-maven-repo
injection into the settings' plugin-resolution repositories interfered with
StreetComplete's own plugin resolution (worked around at the time by an
ad hoc CoGo build with that injection disabled; a ticket was filed to fix
it properly). **This corpus tier never touches `buildSrc` or Gradle plugin
resolution at all** -- the daemon's `compile`/`relink` protocol operates
directly on source files against a fixed classpath, so this wall is
orthogonal to what's tested here. It remains real for the full
CoGo-GUI-build path and is unrelated to WS-F's Gradle-9 finding.

**Vendored subgraph: 10 real, self-contained files from `app/src/commonMain`**
(StreetComplete is Kotlin Multiplatform -- `commonMain` is the
platform-independent source set) -- the domain-model + geometry-math layer,
not the full app (which also has Compose UI, Room persistence, and a much
larger dependency graph excluded here):

- `data/osm/mapdata/Element.kt` -- `LatLon`/`Node`/`Way`/`Relation`/`Element`/`ElementType`
- `data/osm/mapdata/BoundingBox.kt`
- `util/ktx/{Double,Collections,LatLon}.kt`
- `util/math/{AngleMath,Vector3d,SphericalEarthMathVector3d,SphericalEarthMath,FlatEarthMath}.kt`

**One new corpus dependency:** these model classes carry
`@Serializable`/`@SerialName` (`kotlinx.serialization`) -- StreetComplete's
own `app/build.gradle.kts` applies `org.jetbrains.kotlin.plugin.serialization`
version `2.4.0` against `kotlinx-serialization-core`/`-json` `1.11.0`. None
of the vendored functions in this subgraph call the generated
`.serializer()`/`Json.encodeToString` machinery, so the **compiler plugin is
not needed** -- only the plain annotation classes need to resolve, via
`--classpath-extra <path>/kotlinx-serialization-core-jvm-1.11.0.jar`
(not in `~/.gradle/caches` by default on a fresh clone; fetch from Maven
Central: `https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.11.0/kotlinx-serialization-core-jvm-1.11.0.jar`).
Confirmed empirically: baseline compiles clean with just that jar on the
classpath, no `-Xplugin` needed.

**3 edits, PR-mined** -- same methodology note as sora-editor-lib applies
here too: all 3 real PRs below are already baked into the pinned commit (all
merged well before the 2026-07 pin), so each edit reproduces the real fix's
spirit as a fresh, original follow-on change rather than literally replaying
the historical diff:

| Edit | Target | Real PR modeled after | What it exercises |
|---|---|---|---|
| `01-collections-most-common` | `util/ktx/Collections.kt` | PR [#6318](https://github.com/streetcomplete/StreetComplete/pull/6318) "util: handle duplicate elements in Collection.containsExactlyInAnyOrder()" (2025-08-13) -- fixed to use a count-map | reuses the same count-map idiom for a new helper, `mostCommonOrNull()` |
| `02-vector3d-destructuring` | `util/math/Vector3d.kt` | PR [#6311](https://github.com/streetcomplete/StreetComplete/pull/6311) "fix: correct Vector3d division and subtraction operations" (2025-06-06) -- fixed copy-paste bugs in `div`/`minus` | continues the "make per-component operators complete" spirit with `component1/2/3()` destructuring |
| `03-anglemath-radians-float-overload` | `util/math/AngleMath.kt` | PR [#6307](https://github.com/streetcomplete/StreetComplete/pull/6307) "Fix angle normalization with non-zero startAt parameter" (2025-06-06) | fills the file's own real overload gap: `normalizeDegrees` has Double+Float overloads, `normalizeRadians` only had Double -- adds the Float one |

**Result: 3/3 PASS, output-equivalence PASS** (host run, 2026-07-22:
`results/20260722T060647Z/`), part of the full 14-app / 55-edit host run
(`results/20260722T060930Z/`, all PASS, no regressions on any existing app).
Baseline compile 5134ms for 22 classes (cold daemon; warm-daemon per-edit
compiles were 400ms/349ms/474ms).

Run command (serialization annotation classes needed via
`--classpath-extra`; everything else as in "Running it" above):
```bash
python3 quick-build/corpus/harness/run_matrix.py \
  --classpath-extra <path>/kotlinx-serialization-core-jvm-1.11.0.jar \
  --apps streetcomplete-lib \
  ... (other flags as usual)
```
