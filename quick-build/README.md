# Quick Build (ADFA-4128)

Live-reload for user projects: tap the lightning-bolt button once and CoGo installs a
generated **test app**; from then on every save hot-reloads with no install — measured
p50 0.87 s build / ~1.0–1.1 s save-to-reload across all 71 measured code edits
(mid-spec test phone, minimal Kotlin app; `corpus/results/phase1-gates-a56/`).
Invariant: **the test app never silently runs stale code** — every edit either
hot-reloads or visibly falls back to a real Gradle build. The repo-level boundary decision is
[ADR 0010](../docs/adr/0010-quick-build-fast-path-boundary.md); design history lives in
Jira ticket ADFA-4128.

The whole loop runs ON DEVICE: edit → watch → compile → dex → deploy → reload happen on
the phone inside/alongside CoGo. No desktop component is part of the feature.

## How a save becomes a reload

```mermaid
sequenceDiagram
    participant W as ProjectWatcher
    participant O as BuildOrchestrator
    participant D as quickbuild-daemon
    participant S as Deploy service (CoGo)
    participant T as Test app (runtime)
    W->>O: changed files (coalesced batch)
    Note over O: ChangeClassifier -> BuildRoute<br/>(code / resources / assets / mixed / full-Gradle / no-op)
    alt fast path (code / resources / assets / mixed)
        O->>D: compile / dex / relink ops (stdio JSON)
        D-->>O: payload (generation N+1)
        O->>S: deploy
        S->>T: payload fds over uid-checked AIDL
        Note over T: swap InMemoryDexClassLoader +<br/>resource table, recreate activities<br/>(service/provider/Application edit -> persist + process restart)
    else full-Gradle route (manifest, deps, native, processor input)
        O->>O: rebaseline: real setup build;<br/>reinstall only if app bytes changed
    end
```

A compile error takes neither branch: no payload is produced, the test app keeps
running the last good generation, and `onBuildStatus` drives its error overlay (see
below). **Hand-back** is bidirectional: an invalidated session falls back to a real
Gradle build, and any completed Standard Gradle build (CoGo's normal Run-button build)
reseeds a live session's baseline.

## Pieces

| Piece | Where | What |
|---|---|---|
| Domain model | `:quick-build` `domain/` | pure-JVM: orchestrator (coalescing, never-lose-pending), change classifier (`ChangeClassifier` -> `BuildRoute`), session reducer, generation counter |
| Setup build | `:gradle-plugin` `QuickBuildPlugin` | real Gradle build, once per baseline: generates the test app from the merged manifest |
| Runtime | `:quickbuild-runtime` | Java-only AAR inside the test app: binds to CoGo, receives payload fds, hot-reloads |
| Daemon | `:quickbuild-daemon` | JVM child process on the bundled JDK: incremental Kotlin compile via BTA (the Kotlin Build Tools API), d8, aapt2 |
| Deploy service | `:quick-build` `service/` | bound service in CoGo; payload as ParcelFileDescriptors, uid-checked |
| Run statistics | `:quick-build` `domain/QuickBuildMetricsSink` | per-build metrics port; see decision 9 (the app wires a Firebase sink, `analytics/quickbuild/`) |

## Design decisions

The repo-level decision — a **bounded, never-stale fast path beside authoritative
Gradle, correct on the covered edit classes rather than universally** — is
[ADR 0010](../docs/adr/0010-quick-build-fast-path-boundary.md). The module-local
decisions, each with its why and cost:

1. **Builds trigger on file save (watcher), not on a tap.** The loop's value is
   removing interaction entirely: save -> running app in ~1.0-1.1 s with zero taps, vs
   a tap + three dialogs on the standard path (both measured:
   `corpus/results/phase1-gates-a56/summary.md`). The lightning button starts/stops a
   session; it never triggers individual builds. Consequence: in-progress code is a
   normal input, so compile errors are ordinary flow (error-only overlay), and the
   watcher filters build outputs/temp files so junk writes don't trigger builds.
2. **The whole loop runs on the device.** No host machine participates — the mission
   constraint (offline, low-end devices) applied literally. This is why daemon memory
   and low-spec fit are first-order product concerns.
3. **Skeleton app: generated proxies under `<appId>.quickbuild`.** One proxy source per
   manifest component — activity, service, receiver and provider (the custom
   `Application` routes through the payload loader without a proxy) — compiled against
   the runtime AAR; suffixed package id gives coexistence with a Standard Run and
   correct `${applicationId}` authorities. Cost: real-id-bound services (Firebase, Maps
   keys, OAuth, FCM, app links, billing) need a Standard Run.
4. **Changes transmit over uid-checked binder IPC, never the network.** AIDL +
   ParcelFileDescriptors; the exported host service gates every call on the uid
   PackageManager reports for the test app. No sockets, no world-readable files.
5. **Compilation lives in a separate warm daemon process** (pure JVM on the bundled
   JDK, stdio JSON protocol). Isolates the compiler's memory (537 MB RSS over a 28-min
   soak on a mid-spec phone; `corpus/results/phase1-gates-a56/`) and its crash domain
   from the IDE; keeps the compiler warm (the biggest latency lever). That RSS is the
   main low-spec risk.
6. **Hot deploys load by generation via `InMemoryDexClassLoader`.** No APK install per
   edit; install only when the setup build changes the app's bytes (hash-checked).
7. **Rebaseline is the one fallback, and hand-back is bidirectional.** Every untrusted
   state converges on a full setup-build rebaseline; any completed Standard Gradle
   build also reseeds a live session, so the two build paths interleave safely.
8. **Everything is gated behind the experiments flag**
   (`FeatureFlags.isExperimentsEnabled`; see "Running it on a device" below). No flag,
   no behavior change; the bar for lifting the gate is a product decision tracked in
   ADFA-4128.
9. **Run statistics exist to prioritize, not to impress.** Events carry change mix,
   route, duration, outcome under a `(qb_session_id, qb_build_id)` join key, to replace
   assumed edit-type frequencies with measured ones before optimizing anything hard.
10. **The corpus lives in-repo; third-party source never does** — synthetic apps are
    checked in with oracles and results; real apps are pinned by `vendor.json` and
    fetched into a gitignored cache (`corpus/README.md`).

## Known limitations (v1)

- **Gradle 9+ projects: the setup build fails before Quick Build even starts.** CoGo's
  init-script plugin injection throws `UnknownPluginException` under Gradle 9.x — a
  `gradle-plugin` defect, not quick-build-specific. Evidence + isolation:
  `corpus/README.md`, sora-editor finding 1.
- **Modules with bidirectional Kotlin <-> Java references can't fast-compile.** The
  daemon compiles Kotlin then Java; a real reference cycle across the language boundary
  fails in either order. Common in mature codebases. Evidence: `corpus/README.md`,
  sora-editor finding 2.
- **Edits touching kapt/KSP input always rebaseline** (Room etc.) — annotation-processor
  correctness needs a real build (the ADR 0010 boundary).
- **Anything bound to the real `applicationId` needs a Standard Run**: Firebase, Maps
  API keys, OAuth/Sign-In, FCM push, verified app links, billing (ADR 0010,
  `.quickbuild` coexistence). The opt-in same-app-id mode
  (`docs/same-app-id-design.md`) lifts this — but even under the real id, services that
  pin a signing certificate (a Firebase/Google Cloud project restricting API keys or
  OAuth clients to a release SHA, or another machine's debug SHA) still fail with a
  cert-mismatch error; the fix is registering this device's CoGo debug SHA in the
  service's console, which only the user can do.
- **Same-app-id restore on API 28 has no guided recovery yet** (followup). With no
  downgrade API on the device floor, the Standard Run restore install fails safe
  (`INSTALL_FAILED_VERSION_DOWNGRADE`, nothing destroyed) but leaves the test app under
  the real id until the user uninstalls it manually. The designed confirmed-uninstall
  recovery (`docs/same-app-id-design.md` section 4) is not wired in v1; the guard hook
  (`SameAppIdGuard.checkUninstall`) is in place and tested for when it lands.
- **API 28/29 resource swaps take a degraded path** that is unit-tested but not yet
  device-verified (see the scope note under "Test-app architecture").
- **The hot relink links only the app's own `res/`, not library resources.** A manifest
  reference to a library-provided resource aborts every resource reload with aapt2
  "resource not found". The one known case — CoGo's LogSenderPlugin injecting
  `@bool/logsender_enabled` into every debug manifest, which blocked resource edits
  product-wide (`corpus/results/phase1-gates-a56/`, product issue 1) — is FIXED: the
  setup build inlines that ref (`QuickBuildManifestTransformer`; on-device relink 525 ms
  post-fix). The general fix (relinking against the base APK's resource table) is a
  tracked followup; until then any NEW library manifest ref hits the same wall.
- **A failed relink wedges the session** (latent, recovery-UX followup): the dirty
  resource delta never clears, so subsequent edits re-fail until a gradle-file touch
  forces a rebaseline (~7-8 s warm / ~17 s first-hit, `phase1-gates-a56/`). Never-stale
  holds and the error overlay surfaces each failure; auto-rebaseline on repeated
  identical relink failure is the tracked followup (product issue 2).
- **A live service/provider keeps calling OLD copies of recompiled non-component helper
  classes until its next restart.** The loader swap re-instantiates through the new
  generation but cannot rewrite a live object graph, so a helper-only edit (a class the
  service *uses*, not the service/provider/Application itself or a supertype in its
  restart closure) leaves a bounded staleness window — the same kind as an activity
  mid-recreate. The closure rule (`domain/DeployPolicy.kt`) catches every change to the
  component's OWN code and its supertypes and restarts for those; only helper edits fall
  in this window. Tracked tightening ("restart on any code deploy while a tracked service
  is live", which `ServiceTracker` already enables) is behind a flag, priced by metrics
  (design contract `docs/component-proxying-design.md`, section 4).
- **Forced-tap and daemon-respawn builds over-restart component apps.** A forced "catch
  up" tap and the incremental-compiler re-seed after a daemon respawn both full-recompile
  every source, so the mtime-diff reports every class as changed and `DeployPolicy`
  cannot see that a service/provider/`Application` class is byte-identical to what the
  live app runs — it decides Restart. This is the never-stale-safe direction (restarting
  when unsure never serves stale code), but it costs an unnecessary process restart (and
  the loss of in-app state) on a no-op rebuild of any app that has a service, provider or
  custom Application. A sound downgrade needs per-component byte fingerprints of the live
  generation, which the gen-0 baseline (built by Gradle, not by the executor) does not
  provide at session start; tracked as a followup. Genuine incremental edits are
  unaffected — the daemon reports only the classes it actually recompiled.
- **A manifest component whose class is not on the compile classpath, or is a `final`
  library class, fails the setup build.** Every manifest component is proxied uniformly
  by a generated `extends <userClass>` subclass compiled in the setup build. Two shapes
  break that compile: (a) a component class present only at RUNTIME (e.g.
  `androidx.startup.InitializationProvider` or `androidx.profileinstaller`'s receiver,
  pulled in transitively) is not on the setup build's compile classpath, so the proxy's
  `extends` cannot resolve (`cannot find symbol`); (b) a `final` library component class
  cannot be extended (`ClassOpener` strips `final` only from project-dir classes, not
  library jars). Both fail LOUD at setup time (never stale), but Quick Build cannot start
  on such an app even when the user never edits those components — use a Standard Run. The
  CoGo-injected LogSender service is the one runtime-only case handled today (its AAR is
  added to the proxy compile classpath); generalizing this (resolve every component from
  the runtime classpath, or skip proxying library components and keep their original
  manifest name) is a tracked followup. Nested user component classes are handled: their
  binary name (`Outer$Inner`) is emitted as the canonical `Outer.Inner` in the proxy
  source.

## Running it on a device

Prerequisite: a CoGo debug build from this branch installed on an Android test phone
(arm-only: `:app:assembleV8Debug` for arm64) — Quick Build has not shipped in any
release. Note the `:app` build needs the gitignored, team-provided
`app/google-services.json` (Firebase config); external contributors without it
currently can't build `:app` — an onboarding gap that is tracked outside this module.

With CoGo installed, Quick Build ships dark behind the experiments flag: create a file
named `CodeOnTheGo.exp` in the device's `Download/` folder (the mechanism is
`utils/FeatureFlags.kt` in `:common`) and restart CoGo. With the flag on, a
lightning-bolt button appears next to Run in the editor toolbar; tapping it starts a
Quick Build session for the open project — the first start runs the setup build,
installs the generated test app (OS install dialogs apply this once), and spawns the
daemon. From then on, saving any file triggers the loop above; tapping the button again
stops the session.

## Same-app-id mode (opt-in)

By default the test app installs as `<appId>.quickbuild` and coexists with the real app
(ADR 0010). Long-press the lightning bolt → "Use real app ID" flips a per-project
opt-in mode that installs the test app under the project's REAL `applicationId` instead
(design contract: `docs/same-app-id-design.md`; adopted under ADR 0010's revisit
clause, still behind the experiments flag). What it buys: everything package-bound
works in the test app — Firebase init, FCM into proxied services, Sign-In/OAuth against
the debug cert, verified app links, billing test tracks — and, the headline, the real
app's data directory: a same-signature install is an *update*, so data, permissions and
accounts survive the switch. Uniform component proxying (Path A,
`docs/component-proxying-design.md`) is what makes this honest — OS entry points that
target the real package land in current-generation code, not a hollow shell.

The cost is a symmetric clobber, confirmed by a destructive-styled warning on every
mode ENTRY (never per deploy or rebaseline) before anything builds or installs:

- **Entering the mode** replaces the installed real app with the test app until a
  Standard Run reinstalls it. Notifications, shortcuts, widgets, alarms and push for
  the app now reach the test app, and app data is SHARED — code under active editing
  can corrupt or migrate it irreversibly. Every Quick Build ↔ Standard Run switch is a
  reinstall with the OS install dialogs (and possibly Play Protect) each time.
- **Standard Run is the restore** — no second warning; it is the entry warning's
  "until a Standard Run reinstalls it" line. Tapping Run ends the episode, stops the
  session, and installs the real app over the test app, requesting a versionCode
  downgrade on API 29+ (the test app runs at a pinned versionCode above the project's;
  the downgrade request is persisted per-project, so a restore cancelled at the OS
  dialog and retried after a CoGo restart still succeeds). On API 28 the guided restore
  is **not yet built** (v1): with no downgrade API, the
  restore install fails safe with a version-downgrade error — nothing is destroyed, but
  the user must uninstall the test app manually to get the real app back. The designed
  confirmed-uninstall recovery is a tracked followup (see Known limitations). The mode
  toggle stays on; the next bolt tap re-enters via the warning.

An app installed under the real id that was NOT built by this device's CoGo (Play
install, sideload, another machine's keystore) refuses the mode outright — the only way
in would be an uninstall, i.e. data loss; back up and uninstall manually first if you
really want the mode. Independent of the UI flow, a JVM-tested guard
(`domain/SameAppIdGuard.kt`) gates every install — suffix mode can never target the real
id, and the real id is never installed over without this episode's confirmed warning;
its uninstall assertion is in place for the planned API-28 restore, which v1 does not
yet wire (no uninstall path exists). Cert-pinned services still fail even under the real
id — see Known limitations.

## Verifying changes

- **JVM suites**: `:quick-build:test`, `:quickbuild-daemon:test`,
  `:quickbuild-runtime:test`, plus the setup-build tests in `:gradle-plugin`. The root
  build sets `ignoreFailures = true` on test tasks — read the test-report XML/HTML
  under `<module>/build/test-results/`, don't trust `BUILD SUCCESSFUL`.
- **Classification changes**: `ChangeClassifierTest.kt` in `:quick-build` is the
  route contract — a changeset routed wrong breaks the never-stale invariant, so new
  file patterns need cases there first.
- **Compile-pipeline changes**: run the host corpus matrix (`corpus/README.md`) and
  commit the results dir. Correctness = the two oracles (recompiled-class bounds +
  output equivalence), not timings.
- **A new edit class or route needs all three**: a classifier test, a corpus edit
  declaring `expected.route`, and — if it produces a deploy — an on-device walk that
  checks the overlay/fallback behavior, not just the happy path. Route execution
  (mapping a `BuildRoute` to daemon ops + a deploy) lives in
  `service/QuickBuildExecutorImpl.kt` — a genuinely new route touches it too.
- **Latency claims cite a results dir** under `corpus/results/`, or say "not yet
  measured".

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
- Generated `Proxy<N><Type>` classes (`Proxy0Activity`, `Proxy0Service`, ...) extend the
  user classes and give the manifest stable component names while the user's class
  hierarchy stays swappable (both live in the payload dex). Manifest carries superset
  permissions + the user's icon/label; applicationId gets a `.quickbuild` suffix so
  test app and real app coexist. A custom `Application` gets no proxy (nothing
  addresses it by manifest name) but routes through the payload loader too.
- Services, providers and a custom `Application` swap via **process restart**, never
  hot-swap of a live instance: a deploy whose recompiled set hits their restart
  closure (`domain/DeployPolicy.kt`) ships with `"restart": "true"` metadata; the
  runtime persists the payload, acks and exits, and CoGo relaunches the launcher
  proxy. Every accepted deploy also persists app-privately (`PayloadPersistence`), so
  a killed-and-relaunched process boots the NEWEST generation instead of the baked
  gen-0 baseline — without that, providers/Application (instantiated before the binder
  connects) would silently pin to baseline code. The store is fingerprint-keyed to the
  baseline dex; a rebaseline discards it.
- Scope: debug builds + D8 only; components declaring `android:process`, isolated
  services or multiprocess providers fail the setup build loudly (Standard Run
  instead). Device floor: **API 28+** — 30+ gets the full-fidelity `ResourcesLoader`
  resource swap; 28/29 take a degraded `addAssetPath` path (`ResourceSwapStrategy` in
  the runtime; unit-tested, not yet device-verified). The payload dex targets min-api
  30 (`QuickBuildPlugin.MIN_PAYLOAD_API`) to skip desugaring; the dex format it emits
  (039) loads on 28+.

## Deploy metadata JSON (`IQuickBuildTarget.onPayload`)

```json
{
	"entryActivity": "com.example.app.MainActivity",
	"changedAssets": ["data/levels.json"],
	"reason": "code|resources|assets|mixed|forced",
	"restart": "true"
}
```

`reason` mirrors the build route, except `forced`: a deploy from an explicit user tap
with no pending changes (rebuild of the current sources). `restart` (string, present
only when true) marks a restart deploy: the recompiled set touched a
service/provider/custom-Application class (CoGo-side `domain/DeployPolicy.kt`), so the
runtime must persist the payload, ack, and exit instead of hot-swapping; CoGo then
relaunches the launcher proxy and the fresh process boots the persisted newest
generation (design contract: `docs/component-proxying-design.md`, section 4). Encoder:
`service/QuickBuildExecutorImpl.kt` (CoGo); parser: `DeployMetadata.java` (runtime).
The AIDL contract (`IQuickBuildHost` / `IQuickBuildTarget`) lives at
`quickbuild-runtime/src/main/aidl/`.

## Build status JSON (`IQuickBuildTarget.onBuildStatus`)

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

## Tap-to-jump + return gesture

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
 "aapt2": "/path/to/aapt2", "d8Jar": "/path/to/d8.jar", "androidJar": "...",
 "minApi": 30, "compilerPlugins": ["/optional/kotlin/compiler/plugin.jar"]}
{"id": 2, "op": "compile", "allSources": ["..."], "changedFiles": ["..."]}
{"id": 3, "op": "dex", "classesDirs": ["..."]}
{"id": 4, "op": "relink", "resDirs": ["..."], "manifest": "..."}
{"id": 5, "op": "ping"}
{"id": 6, "op": "shutdown"}
```

Responses: `{"id": N, "ok": true, ...op-specific...}` or
`{"id": N, "ok": false, "diagnostics": [{"severity": "ERROR", "message": "...",
"file": "...", "line": 7, "column": 13}]}`. `minApi` defaults to 30 (the payload
floor), and a repeated `configure` replaces the daemon's session state — there is no
separate "reconfigure" op. The daemon never exits on a build error; it exits on
`shutdown`, EOF on stdin, or a fatal internal error (exit code != 0, which CoGo treats
as daemon death → respawn per the session model below).

BTA incremental gotchas (re-derived from the ADFA-4128 spike, load-bearing):
`SourcesChanges.Known` required (`ToBeCalculated` falls back to full compile); the
shrunk snapshot — the BTA's compact record of classpath ABI that incremental
invalidation reads — MUST be exactly `<rootProjectDir>/shrunk-classpath-snapshot.bin`;
runtime needs `kotlinx-coroutines-core-jvm` + `trove4j`; pass ALL sources as changed on
the first build to seed IC caches; only set `assureNoClasspathSnapshotsChanges(true)`
after the shrunk snapshot exists.

## Session model

One sealed state type (`domain/QuickBuildSession.kt`): `Idle` -> `Prewarming` (eager
setup build at project open — no install, no daemon) -> `Provisioning` (setup build +
test-app install + daemon spawn) -> `Ready` <-> `Building` -> `Deployed`, plus two
off-ramps: `Invalidated` (manifest/gradle/external change — needs a full Gradle
rebaseline) and `Degraded` (daemon died; respawn + re-seed in progress). A compile
error is NOT a state change: the session stays `Ready` at the old generation with
`lastFailure` set — the test app never moved, which is the never-stale invariant in
state form. `service/QuickBuildSessionManager.kt` turns reducer effects into real work
(provisioning, daemon respawn, Gradle rebaseline). When diagnosing on device, start
from which state the session is in and whether the overlay surfaced a failure —
converging on a rebaseline is intended behavior for any untrusted state, not a bug.
One known exception that does NOT converge on its own: a failed relink wedges the
session at the failed resource delta (Known limitations above — latent since the
logsender fix, but any relink failure re-triggers it); the unwedge today is any
gradle-file touch, which routes to rebaseline.

## Compose projects

When the user project uses Jetpack Compose, hot compiles need the Compose compiler
plugin or every `@Composable` body miscompiles to a plain function. The wiring:

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

Verified host-side (corpus app `compose-kotlin` + daemon unit tests; full-corpus run
`corpus/results/20260719T181349Z/`): the transform runs under the BTA incremental
path, recompile sets stay minimal, and the compiler's runtime-version check accepts
even the old `androidx.compose.runtime:runtime:1.3.0` the offline
`localMvnRepository` bundles.
