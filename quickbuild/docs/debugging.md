# Debugging a live Quick Build session

You saved a file and the running app did not change. This doc is the answer paths for that,
in the order they are worth trying, plus the reference material each one needs: on-device
paths, the session event log, the adb entry point, and every timeout in the pipeline.

Assumed already read, and not repeated here:

- [README, "Running it on a device"](../core/README.md#running-it-on-a-device) - the flag files
  and the CoGo build you need before any of this works.
- [README, "There is no single logcat tag"](../core/README.md#there-is-no-single-logcat-tag) -
  the six truncated tags and the three logging processes. This doc gives the rule that
  produced that table, so you can derive a tag the table does not list.

Two flags recur. `CodeOnTheGo.exp` in `Download/` turns Quick Build on at all.
`CodeOnTheGo.qbbench`, alongside it, turns on the session event log and the adb entry point -
sections 5 and 6 need it, sections 1 to 4 do not.

**Flag files are read once per process.** `FeatureFlags.initialize()` caches on first call and
never re-reads disk, so creating or deleting a flag file changes nothing until CoGo restarts
([`FeatureFlags.kt`](../../common/src/main/java/com/itsaky/androidide/utils/FeatureFlags.kt)).

## 1. My edit did not show up: work down this list

Each step names one check and the observable that settles it. Stop at the first one that
answers.

1. **Was the file watched?** The single most common cause, and it is silent by design.
   See section 2 - the rules are narrow and a file one directory outside them produces no
   event at all.
2. **Did a build start?** Filter logcat on the session tag (section 3). Every state
   transition logs
   `Quick-build session: <from> -> <to> on <event>`
   from `QuickBuildSessionManager`. No transition means no batch reached the session.
3. **Did the classifier send it to Gradle instead?** The same line carries the reason:
   `... on InvalidationDetected(reason=MANIFEST_CHANGED)` and its seven siblings. That is a
   proxy app rebuild, not a live reload - it is slow and it prompts for an install, but it is
   the never-stale invariant working, not a bug.
4. **Did the compile fail?** A compile error produces no payload, so the session stays
   `Ready` at the old generation and the proxy app shows its error overlay. Look for
   diagnostics on the executor tag.
5. **Where did the time go?** One line per generation, `quickbuild-e2e:`, section 3.
   It is the fastest way to see whether a save reached the device at all.
6. **Is the app on screen actually the proxy app?** A Standard Run install occupies the same
   package slot, so both look identical from the launcher. Three markers:
   - the installed package declares `android:appComponentFactory` =
     `com.itsaky.androidide.quickbuild.runtime.QuickBuildAppComponentFactory`
     ([`RealIdInstall.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/domain/RealIdInstall.kt));
   - it logs under the tag `QuickBuildRuntime`;
   - it has a `files/quickbuild/payload/` directory once a deploy has landed.

   A Standard Run install has none of the three.

## 2. Most missing edits were never watched at all

A dropped event is silent: `WatchFilter.isRelevant` returning false costs nothing and warns
nobody ([`WatchFilter.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/domain/WatchFilter.kt)).

What is watched:

- **`<module>/src`, one per discovered module** - recursively, for both inotify and the poll
  sweep. Discovery is a walk from the project root bounded to depth 4, skipping `build/` and
  dot-directories, keyed on the presence of a `build.gradle[.kts]`
  ([`QuickBuildProjectLayout.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/data/QuickBuildProjectLayout.kt)).
- **Named Gradle files, exactly** - `settings.gradle[.kts]`, `gradle.properties`,
  `gradle/libs.versions.toml` at the root, plus `build.gradle[.kts]` per module. Only the
  mtime poll covers these; no inotify watch is registered on their parents.
- Generated source roots from `setup.json` are **compiled but deliberately not watched** -
  they live under `build/`, which Gradle owns, and watching them would feed the loop its own
  output.

What is dropped before the session ever sees it:

| Dropped | Rule |
| --- | --- |
| Anything outside a watched `src` tree that is not a watched Gradle file | not under a root |
| Anything with a `build` directory anywhere in its ancestry | build intermediate |
| Names starting with `.` | temp artifact |
| Names ending `~`, `.tmp`, `.swp`, `.bak`, `.orig`, `.rej` | temp artifact |

A second drop happens later, at batch-settle: a path reported modified that no longer exists
and whose shape names no known role (`sed`'s `sedXXXXXX` and kin) is discarded as rename
noise rather than pushed to a full Gradle build
([`WatcherBatchReconciler.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/domain/WatcherBatchReconciler.kt)).

Two timing facts that explain a save that showed up late rather than not at all:

- The project sits on FUSE-backed `/storage/emulated`, which drops inotify events under load.
  A **2 s mtime sweep** is the backstop, so a lost event costs up to that.
- A burst settles after **150 ms** of quiet, capped at **1 s** from its first event.

**`adb push` preserves mtime.** A same-size rewrite pushed that way collides with the poll's
fingerprint and is invisible to it; only inotify catches it, and inotify is the channel that
drops. Touch the file after pushing if a scripted edit seems to have been ignored.

## 3. Logcat tags are truncated, so guessing a tag does not work

The host side logs through slf4j and CoGo's binding derives the tag from the **simple class
name**, then trims it: keep the **last 23 characters**, overwrite the first two with `..`
([`LogTagUtils.java`](../../logger/src/main/java/com/itsaky/androidide/utils/LogTagUtils.java),
`LogUtils.MAX_TAG_LENGTH = 23`). Names of 23 characters or fewer are untouched. That is the
whole rule - apply it to any class and you have its tag.

Every logger in the Quick Build host code, so nothing is missing from a filter:

- 23 characters or fewer, tag equals the class name: `AndroidProjectWatcher`,
  `AndroidProxyAppLauncher`, `BenchEventsFile`, `DaemonProcessClient`, `DeployChannel`,
  `FileGenerationStore`, `LiveReloadExecutorImpl`, `LiveReloadOrchestrator`,
  `LiveSessionFactory`, `OrchestratorEventRouter`, `PayloadDeployer`,
  `ProxyAppBuildRunner`, `ProxyAppConnections`, `ProxyAppInfo`, `ProxyAppInstaller`,
  `QuickBuildAction`, `QuickBuildBenchActivity`, `QuickBuildHostService`,
  `QuickBuildJumpActivity`.
- One logger is named by a string rather than a class and so has no long form:
  `QuickBuildMetrics`, declared in `MetricsReporting.kt`.
- Longer, tag truncated: the six in the README table.

### The one line worth grepping first

`LiveReloadExecutorImpl` emits exactly one structured line per generation
([`E2eTimeline.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/domain/E2eTimeline.kt)):

```
quickbuild-e2e: gen=7 trigger=1234 compileDone=2100 deploySent=2140 reloadLive=2560
```

```bash
adb logcat | grep 'quickbuild-e2e:'
```

Stamps are a monotonic clock in milliseconds. `reloadLive - trigger` is the whole loop the
user feels; `compileDone - trigger` is compile plus dex; `reloadLive - deploySent` is the
binder round trip plus the proxy app's reload. The line deliberately carries **no** step
timings, spans or counts - the harness parses it and widening it would break that. Those live
in `reload_timeline` (section 5).

### Filter sets

```bash
# precise
adb logcat -s QuickBuildRuntime ..ckBuildSessionManager LiveReloadOrchestrator \
  LiveReloadExecutorImpl PayloadDeployer DaemonProcessClient DeployChannel \
  ProxyAppInstaller ..BuildDaemonController

# blunt, but hard to get wrong
adb logcat | grep -iE 'quickbuild|LiveReload|ProxyApp|daemon\(stderr\)'
```

### The daemon has no log of its own

It writes `[quickbuild-daemon] <message>` to stderr
([`DaemonMain.kt`](../daemon/src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/DaemonMain.kt)).
`DaemonProcessClient` drains that and re-logs it as `daemon(stderr): ...` at **warn**, with
non-JSON stdout as `daemon: ...` at **debug**. There is no daemon log file: if CoGo's process
dies, that output is gone. Reproduce it standalone with the same command CoGo uses -
`<JAVA> -jar <daemon dir>/quickbuild-daemon.jar`, cwd set to the daemon dir, with a clean
environment.

## 4. Where Quick Build's files live on device

`ANDROIDIDE_HOME` is `/data/data/com.itsaky.androidide/files/home/.cg`. Under `run-as
com.itsaky.androidide` the working directory is the data dir, so the relative paths below work
directly. `run-as` needs a debuggable package, which both the CoGo debug build and the proxy
app are.

**The `run-as` target differs per row.** The persisted payload belongs to the user's app, not
to CoGo.

| Path | What | How to read it |
| --- | --- | --- |
| `files/home/.cg/quickbuild/quickbuild-runtime.aar` | staged runtime AAR, re-staged every provision | `run-as com.itsaky.androidide` |
| `files/home/.cg/quickbuild/daemon/` | daemon jar, its full runtime classpath, `compose-compiler-plugin.jar`; deleted and re-extracted every provision | `run-as com.itsaky.androidide` |
| `files/home/.cg/quickbuild/bench-events.jsonl` | session event log; bench flag only | `run-as com.itsaky.androidide` |
| `no_backup/quickbuild-scratch/<name>-<16 hex>/work` | executor payload staging | `run-as com.itsaky.androidide`; **deleted on teardown** |
| `no_backup/quickbuild-scratch/<name>-<16 hex>/out` | daemon output: classes, dex, relinked resources | as above |
| `<project>/.androidide/quickbuild/generation` | the generation counter | plain `adb shell cat` |
| `<project>/<module>/build/quickbuild/setup.json` | the proxy app build's handshake with CoGo | plain `adb shell cat` |
| `/data/data/<user applicationId>/files/quickbuild/payload/` | persisted payload: `payload.dex`, `resources.arsc`, `assets.zip`, `meta.json` | `run-as <the user's package>` |

Projects live under `/storage/emulated/0/CodeOnTheGoProjects/`.

Four traps in that table:

- **The scratch tree is deleted on session teardown**, so inspect it while the session is
  live. Its directory name is `<sanitized project basename>-<first 16 hex of SHA-256 of the
  normalized absolute project path>`
  ([`QuickBuildScratch.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/data/QuickBuildScratch.kt)),
  so `ls` the parent rather than trying to compute it.
- **The generation counter's directory is `.androidide`, not `.cg`.** CoGo's project cache dir
  was renamed to `.cg`; this one path is hardcoded to the old name in
  [`FileGenerationStore.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/data/FileGenerationStore.kt).
  Never reset it to "get a clean test" - the runtime uses it to reject a payload older than
  what is running.
- **`resources.arsc` is not a resource table.** It holds the whole relinked resource apk;
  the filename is historical
  ([`PayloadPersistence.java`](../runtime/src/main/java/com/itsaky/androidide/quickbuild/runtime/PayloadPersistence.java)).
- **The baseline is inside the APK, not on disk** - `assets/quickbuild/gen-0.dex`, with the
  component name map at `assets/quickbuild/components.json`.

## 5. bench-events.jsonl is the session as data, and needs the bench flag

With `CodeOnTheGo.qbbench` present, two extra listeners run beside CoGo's shipping analytics
sink and append to a JSON-lines file. One object per line, each carrying `"v":1` and a
wall-clock `wallMs`
([`BenchEventsFile.kt`](../../app/src/main/java/com/itsaky/androidide/quickbuild/BenchEventsFile.kt)).

```bash
adb shell run-as com.itsaky.androidide \
  cat files/home/.cg/quickbuild/bench-events.jsonl | grep '"state"' | tail -20
```

Seven event types:

| `event` | Carries |
| --- | --- |
| `session_started` | nothing beyond the envelope |
| `state` | `state`, and `generation` where the state has one |
| `build_started` | `buildId`, `route` |
| `build_finished` | `buildId`, `outcome` |
| `reload_timeline` | the full save-to-reload breakdown, below |
| `rebaseline` | `ok`, `durationMillis` (a proxy app rebuild) |
| `invalidation` | `reason` |

### Wire names are frozen and do not match the Kotlin identifiers

The harness string-compares these literals and historical files carry them, so a rename in
code must not change them
([`BenchQuickBuildMetricsSink.kt`](../../app/src/main/java/com/itsaky/androidide/quickbuild/BenchQuickBuildMetricsSink.kt),
[`BenchStateRecorder.kt`](../../app/src/main/java/com/itsaky/androidide/quickbuild/BenchStateRecorder.kt)).
Three will trip you up when grepping:

| In code | On the wire |
| --- | --- |
| state `Prebuilding` | `Prewarming` |
| route `WarmCompile` | `Seed` |
| outcome `RequiresProxyAppRebuild` | `RequiresRebaseline` |

Everything else serializes under its own name: states `Idle`, `Provisioning`, `Ready`,
`Building`, `Deployed`, `Invalidated`, `Degraded`; routes `CodeOnly`, `ResourcesOnly`,
`AssetsOnly`, `CodeAndResources`, `FullGradleBuild`, `NoOp`; outcomes `Success`,
`CompileError`, `DeployFailure`, `InfrastructureFailure`; and the eight `InvalidationReason`
constants verbatim.

### reload_timeline, and why the residual is the point

Five host spans partition the build half - `scanMs`, `compileRpcMs`, `policyMs`, `dexRpcMs`,
`relinkRpcMs`. The daemon's own timings (`kotlinMs`, `javacMs`, `stripMs`, `d8Ms`,
`preSnapMs`, `postSnapMs`, `javaAbiSnapMs`, `aapt2CompileMs`, `aapt2LinkMs`) **nest inside**
those, so they are reported but never summed.

```
accountedMs   = scanMs + compileRpcMs + policyMs + dexRpcMs + relinkRpcMs + (reloadLive - deploySent)
unaccountedMs = totalMs - accountedMs
```

A near-zero residual is healthy. A growing one means a step is running that nothing times, and
the next reader sees the gap rather than misattributing that cost to whatever is measured next
door. A build that measured no spans reports **no** residual rather than blaming the whole
build. Known healthy contributors are small: changed-asset packaging, and payload bookkeeping
before the deploy hand-off.

Each line also carries the daemon's counters - `nAllSources`, `nKotlinCompiled`,
`nJavaSources`, `nChangedClasses`, `nClassFiles`, `classBytes` - plus `compileOrdinal` and
`scratchFs`, without which a timing row cannot be read at all. What each one means, and why
those last two are context rather than cost:
[the protocol reference](../protocol/README.md#per-build-statistics-what-the-op-did-not-just-how-long-two-compilers-took).

Two of those are spelled differently on the two wires, so grep for the right one: the daemon
protocol's `nKotlinToCompile` and `scratchFsType` are written here as `nKotlinCompiled` and
`scratchFs`.

## 6. You can start a session from adb, but only under the bench flag

`QuickBuildBenchActivity` is exported and double-gated on both flags. It opens a project and
fires the first Quick Build tap as the editor initializes, replacing the human's tap. It
accepts only an existing directory inside the projects folder, so a hostile sender can at
worst open one of the user's own projects
([`QuickBuildBenchActivity.kt`](../../app/src/main/java/com/itsaky/androidide/quickbuild/QuickBuildBenchActivity.kt)).

```bash
adb shell am start-activity \
  -a com.itsaky.androidide.quickbuild.action.BENCH_OPEN_PROJECT \
  -n com.itsaky.androidide/.quickbuild.QuickBuildBenchActivity \
  --es com.itsaky.androidide.quickbuild.extra.PROJECT_PATH \
     /storage/emulated/0/CodeOnTheGoProjects/<project>
```

- **Idempotent.** Re-sending for the already-open, already-initialized project just taps Quick
  Build again, so a session can be retried (after an install-confirm timeout, say) without a
  force-stop and full re-open.
- **Optional `--es com.itsaky.androidide.quickbuild.extra.MODE <mode>`**, either `quickbuild`
  (the default) or `standard`. `standard` fires the normal Run button instead, which is how a
  standard build is measured on the same warm daemon. An unknown value rejects the intent
  outright.

A third flag, `CodeOnTheGo.qbnoseed`, is inert unless `qbbench` is also on. What it does and
why it exists: [README, "Running it on a device"](../core/README.md#running-it-on-a-device).

## 7. Tunables: every timeout and bound in the pipeline

A hang usually means one of these fired, or did not. All are compile-time constants, not user
settings. Most are the default of a constructor parameter, so a test can drive them; six are
not, and changing those means editing the constant: `MODULE_SCAN_MAX_DEPTH`, `UID_RETRIES`,
`MAX_INSTALL_AUTO_RETRIES`, `SHUTDOWN_TIMEOUT_MILLIS`, `REBIND_MIN_DELAY_MS`,
`REBIND_MAX_DELAY_MS`.

| Tunable | Where it is read | Default | What changing it does |
| --- | --- | --- | --- |
| Watcher mtime poll interval | `AndroidProjectWatcher.DEFAULT_POLL_MILLIS` | 2 s | Upper bound on how long a save inotify dropped stays unseen. Lowering it re-walks the FUSE-backed project tree more often. |
| Debounce quiet period | `ChangeCoalescingDefaults.QUIET_MILLIS` | 150 ms | Idle gap that ends a burst. Raising it batches more saves into one build and adds that much to every save. |
| Debounce hard cap | `ChangeCoalescingDefaults.MAX_MILLIS` | 1 s | Cap measured from the burst's first event, so a continuous write stream cannot defer a build forever. |
| Module scan depth | `DefaultQuickBuildProjectLayout.MODULE_SCAN_MAX_DEPTH` | 4 | How deep the one-time scan looks for modules to watch. A module nested deeper is never watched, so its edits are silently dropped. |
| Scratch free-space floor | `QuickBuildScratch.DEFAULT_MIN_FREE_BYTES` | 100 MB | Provisioning refuses to start below this on the app-private volume, so a full disk fails in seconds instead of as ENOSPC minutes into the Gradle build. |
| Install confirm timeout | `ProxyAppInstaller.DEFAULT_TIMEOUT_MILLIS` | 180 s | How long the session waits for the OS install dialog to be accepted before parking in `Invalidated(awaitingRetry = true)`. |
| Install confirm poll | `ProxyAppInstaller.DEFAULT_POLL_MILLIS` | 1 s | How often the installer re-checks whether the install landed. |
| Install uid retries | `ProxyAppInstaller.UID_RETRIES` | 5 | Attempts at reading the just-installed package's uid, spaced by the poll interval above, covering PackageManager's lag after the install lands. Exhausting them fails the install. |
| Foreground install auto-retries | `SessionReducer.MAX_INSTALL_AUTO_RETRIES` | 2 | How many times CoGo returning to the foreground re-runs an unconfirmed rebuild before it stops re-prompting. |
| Daemon request timeout | `DaemonProcessClient.DEFAULT_REQUEST_TIMEOUT_MILLIS` | 300 s | Per-request ceiling. Exceeding it fails that request and releases the slot; it does not by itself count as daemon death. |
| Daemon shutdown grace | `DaemonProcessClient.SHUTDOWN_TIMEOUT_MILLIS` | 3 s | How long a polite `shutdown` is given before the child is killed. |
| Deploy round trip | `DeployChannel.DEFAULT_TIMEOUT_MILLIS` | 15 s | One AIDL `onPayload` call. Exceeding it fails the deploy. |
| Restart-deploy disconnect wait | `LiveReloadExecutorImpl.DEFAULT_RESTART_DISCONNECT_TIMEOUT_MILLIS` | 5 s | How long the host waits for the proxy app to exit after a restart deploy. A runtime that acked but kept running is treated as an outdated baseline and forces a proxy app rebuild. |
| Restart-deploy reconnect wait | `LiveReloadExecutorImpl.DEFAULT_RESTART_RECONNECT_TIMEOUT_MILLIS` | 15 s | How long the host waits for the relaunched proxy app to rebind. |
| Runtime rebind backoff floor | `QuickBuildClient.REBIND_MIN_DELAY_MS` | 1 s | First rebind delay inside the proxy app, doubled per failed attempt and reset on every successful connect. |
| Runtime rebind backoff ceiling | `QuickBuildClient.REBIND_MAX_DELAY_MS` | 30 s | Ceiling for that doubling, so a CoGo that never comes back costs one attempt per 30 s. |
