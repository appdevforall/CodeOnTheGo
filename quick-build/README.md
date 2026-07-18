# Quick Build (ADFA-4128)

Live-reload for user projects: tap the lightning-bolt button once and CoGo installs a
generated **test app**; from then on every save hot-reloads in ~1–2 s with no install.
Invariant: **the test app never silently runs stale code** — every edit either
hot-reloads or visibly falls back to a real Gradle build. Full design:
`docs/product/plans/2026-07-16_ADFA-4128_live-reload-v1/plan.md` (wrapper repo).

The whole loop runs ON DEVICE: edit → watch → compile → dex → deploy → reload happen on
the phone inside/alongside CoGo. No desktop component is part of the feature.

## Pieces

| Piece | Where | What |
|---|---|---|
| Domain model | `:quick-build` `domain/` | pure-JVM: orchestrator (coalescing, never-lose-pending), tier classifier, session reducer, generation counter |
| Setup build | `:gradle-plugin` `QuickBuildPlugin` | real Gradle build, once per baseline: generates the test app from the merged manifest |
| Runtime | `:quickbuild-runtime` | Java-only AAR inside the test app: binds to CoGo, receives payload fds, hot-reloads |
| Daemon | `:quickbuild-daemon` | JVM child process on the bundled JDK: BTA incremental Kotlin compile, d8, aapt2 |
| Deploy service | `:quick-build` `service/` | bound service in CoGo; payload as ParcelFileDescriptors, uid-checked |
| Run statistics | `:quick-build` `domain/QuickBuildMetricsSink` | per-build metrics port (route, change-set size/kB, duration, invalidations, rebaseline cost); the app wires a Firebase sink (`analytics/quickbuild/`) emitting `quick_build_{started,completed,invalidated,rebaseline}` |

## Design decisions

The repo-level decision — a **bounded, never-stale fast path beside authoritative
Gradle, correct on the covered edit classes rather than universally** — is
[ADR 0010](../docs/adr/0010-quick-build-fast-path-boundary.md). The module-local
decisions, each with its why and cost:

1. **Builds trigger on file save (watcher), not on a tap.** The loop's value is
   removing interaction entirely: save -> running app in ~1.0-1.1 s, zero taps (the
   standard path costs a tap + three dialogs). The lightning button starts/stops a
   session; it never triggers individual builds. Consequence: in-progress code is a
   normal input, so compile errors are ordinary flow (error-only overlay), and the
   watcher filters build outputs/temp files so junk writes don't trigger builds.
2. **The whole loop runs on the device.** No host machine participates — the mission
   constraint (offline, low-end devices) applied literally. This is why daemon memory
   and low-spec fit are first-order product concerns.
3. **Skeleton app: generated proxies under `<appId>.quickbuild`.** One proxy source per
   activity compiled against the runtime AAR; suffixed package id gives coexistence
   with a Standard Run and correct `${applicationId}` authorities. Cost: real-id-bound
   services (Firebase, Maps keys, OAuth, FCM, app links, billing) need a Standard Run.
4. **Changes transmit over uid-checked binder IPC, never the network.** AIDL +
   ParcelFileDescriptors; the exported host service gates every call on the uid
   PackageManager reports for the test app. No sockets, no world-readable files.
5. **Compilation lives in a separate warm daemon process** (pure JVM on the bundled
   JDK, stdio JSON protocol). Isolates ~540 MB RSS and the crash domain from the IDE;
   keeps the compiler warm (the biggest latency lever). That RSS is the main low-spec
   risk.
6. **Hot deploys load by generation via `InMemoryDexClassLoader`.** No APK install per
   edit; install only when the setup build changes the app's bytes (hash-checked).
7. **Rebaseline is the one fallback, and hand-back is bidirectional.** Every untrusted
   state converges on a full setup-build rebaseline; any completed Standard Gradle
   build also reseeds a live session, so the two build paths interleave safely.
8. **Everything is gated behind the experiments flag.** No flag, no behavior change;
   the release bar for lifting the gate is a product decision in the ticket docs.
9. **Run statistics exist to prioritize, not to impress.** Events carry change mix,
   route, duration, outcome under a `(qb_session_id, qb_build_id)` join key, to replace
   assumed edit-type frequencies with measured ones before optimizing anything hard.
10. **The corpus lives in-repo; third-party source never does** — synthetic apps are
    checked in with oracles and results; real apps are pinned by `vendor.json` and
    fetched into a gitignored cache (`corpus/README.md`).

## Test-app architecture (the classloading contract)

The test-app APK contains the runtime AAR, the user's library dependencies and
resources — but **no user classes**. User classes + generated proxy activities travel
ONLY in the payload dex:

- The setup build compiles user sources + proxies to `classes.dex` and bakes it into the
  APK as `assets/quickbuild/gen-0.dex` (the baseline payload).
- The runtime declares an `android:appComponentFactory` that instantiates activities
  through the CURRENT generation's `InMemoryDexClassLoader` (parent = APK classloader:
  framework/androidx resolve from the APK; user classes exist only in the payload, so
  parent-first delegation cannot serve a stale copy).
- A deploy hands over new fds; reload = swap the payload classloader (+ a resource-table
  swap: `ResourcesLoader`/`ResourcesProvider.loadFromTable` on API 30+, a degraded
  `addAssetPath` shim on 28/29 — see `ResourceSwapStrategy`) and recreate the activity
  stack. Recreated activities are instantiated from the new loader — that is what makes
  reload real.
- Generated `Proxy<N>Activity extends <UserActivity>` classes give the manifest stable
  component names while the user's class hierarchy stays swappable (both live in the
  payload dex). Manifest carries superset permissions + the user's icon/label;
  applicationId gets a `.quickbuild` suffix so test app and real app coexist.
- v1 scope: activities only (no Service/Receiver/Provider proxying), default
  `Application` class, API 28+ (resource reloads on 28/29 take the degraded
  `addAssetPath` path, plan B5), debug + D8 only (D3).

## Deploy metadata JSON (`IQuickBuildTarget.onPayload`)

```json
{
	"entryActivity": "com.example.app.MainActivity",
	"changedAssets": ["data/levels.json"],
	"reason": "code|resources|assets|mixed|forced"
}
```

## Build status JSON (`IQuickBuildTarget.onBuildStatus`, plan A1)

A compile error never produces a payload, so this message is how the running test app
learns a build failed (its overlay then says it still runs the last working version;
tap jumps to the error in CoGo). `build_ok` clears a shown failure. All values are
STRINGS (the runtime's MiniJson reads only strings); unknown kinds/fields are ignored
by the runtime, and an older test app ignores the whole call (appended AIDL method) -
both directions of the version skew are safe.

```json
{"kind": "build_failed", "file": "/abs/path/Foo.kt", "line": "12", "column": "5",
 "message": "first line of the first error", "moreErrors": "2"}
{"kind": "build_ok"}
```

Encoder: `service/BuildStatusJson.kt` (CoGo); parser: `BuildStatus.java` (runtime).

## Tap-to-jump + app-switcher gesture (plan A1/A3)

- Overlay tap on a build failure -> explicit intent
  `com.itsaky.androidide.quickbuild.action.JUMP_TO_ERROR` (extras: FILE, LINE, COLUMN;
  1-based) to CoGo's `QuickBuildJumpActivity` trampoline, which validates the file
  against the open project, posts `QuickBuildErrorJumpEvent`, and finishes - revealing
  the editor, which opens the file at the error line.
- 3-finger tap anywhere in the test app returns to CoGo: the generated proxy
  activities' `dispatchTouchEvent` feeds `QuickBuildGestures` (observation only - every
  event still reaches the app via `super`, so normal 1-2 finger input is never consumed
  or delayed). A one-time hint banner on first launch makes the gesture discoverable.

## Daemon protocol (line-delimited JSON over stdin/stdout)

One request in flight at a time (the orchestrator serializes). Requests:

```json
{"id": 1, "op": "configure", "projectRoot": "...", "classpath": ["..."], "outDir": "...",
 "aapt2": "/path/to/aapt2", "d8Jar": "/path/to/r8.jar", "androidJar": "...",
 "compilerPlugins": ["/optional/kotlin/compiler/plugin.jar"]}
{"id": 2, "op": "compile", "allSources": ["..."], "changedFiles": ["..."]}
{"id": 3, "op": "dex", "classesDirs": ["..."]}
{"id": 4, "op": "relink", "resDirs": ["..."], "manifest": "..."}
{"id": 5, "op": "ping"}
{"id": 6, "op": "shutdown"}
```

Responses: `{"id": N, "ok": true, ...op-specific...}` or
`{"id": N, "ok": false, "diagnostics": [{"severity": "ERROR", "message": "...",
"file": "...", "line": 7, "column": 13}]}`. The daemon never exits on a build error;
it exits on `shutdown`, EOF on stdin, or a fatal internal error (exit code != 0, which
CoGo treats as daemon death → respawn per the session model).

BTA incremental gotchas (re-derived from the ADFA-4128 spike, load-bearing):
`SourcesChanges.Known` required (`ToBeCalculated` falls back to full compile); the
shrunk snapshot MUST be exactly `<rootProjectDir>/shrunk-classpath-snapshot.bin`;
runtime needs `kotlinx-coroutines-core-jvm` + `trove4j`; pass ALL sources as changed on
the first build to seed IC caches; only set `assureNoClasspathSnapshotsChanges(true)`
after the shrunk snapshot exists.

## Compose projects

When the user project uses Jetpack Compose, hot compiles need the Compose compiler
plugin or every `@Composable` body miscompiles to a plain function. The wiring (D3):

- `QuickBuildPlugin` detects Compose in `finalizeDsl` (`buildFeatures.compose` or the
  `org.jetbrains.kotlin.plugin.compose` plugin) and writes `composeEnabled` into
  `setup.json`.
- CoGo stages `compose-compiler-plugin.jar` next to the daemon jar (it rides
  `quickbuild-daemon.zip`; see `:app`'s `quickBuildDaemonZip`). The jar is
  `kotlin-compose-compiler-plugin-embeddable`, version-matched to the DAEMON's bundled
  compiler -- deliberately NOT the project's own Compose compiler artifact, which
  tracks the project's (possibly older) Kotlin.
- On `composeEnabled`, the session manager passes that jar via
  `configure.compilerPlugins`; the daemon turns each entry into `-Xplugin=` on the BTA
  incremental compile. The compose runtime classes needed on the compile classpath
  already arrive via `setup.json`'s `classpath` (the variant compile classpath).

Verified host-side (corpus app `compose-kotlin` + daemon unit tests): the transform
runs under the BTA incremental path, recompile sets stay minimal, and the compiler's
runtime-version check accepts even the old `androidx.compose.runtime:runtime:1.3.0`
the offline `localMvnRepository` bundles.
