# Component proxying design contract (Path A)

- **Status:** Contract for implementation — ADFA-4128 follow-on, plan
  `plan-component-proxying.md` Path A.
- **Date:** 2026-07-20
- **Scope:** proxy Services, manifest-declared BroadcastReceivers, and
  ContentProviders (plus custom `Application` carry-through) in the Quick Build
  proxy app. Swap semantics for services/providers is **process restart**, never
  hot-swap of a running instance. Same-id mode (Path B) is out of scope.

The rules below extend the proxy-app architecture in `../README.md` and stay inside
the ADR 0010 boundary: manifest *changes* still take a proxy app rebuild; this contract only widens
what the *generated* proxy app can host.

## 1. components.json v2 and the setup.json `components` list

Two consumers, two shapes:

**APK asset `assets/quickbuild/components.json`** stays a flat
`"userClass": "proxyClass"` string map — the existing `ComponentMap` parser accepts
it unchanged (non-string values are dropped by design). v2 adds entries for every
proxied service/receiver/provider alongside the activities, plus one marker entry
`"schema": "2"`. The runtime uses the map only for user-class -> manifest-name
translation (entry-activity launch); it never needs per-component attributes, and
skew is moot because the asset ships in the same APK as the runtime that parses it.

**CoGo-side metadata** (the manifest-info intermediate and `build/quickbuild/setup.json`)
gains a `components` array — the rich representation. Existing keys
(`testAppId`, `entryActivity`, `activities`) stay for current consumers.

```json
"components": [
	{"type": "activity", "userClass": "com.example.MainActivity",
	 "proxyClass": "com.example.quickbuild.proxies.Proxy0Activity",
	 "launcher": true, "supertypes": ["com.example.BaseActivity"]},
	{"type": "service", "userClass": "com.example.SyncService",
	 "proxyClass": "com.example.quickbuild.proxies.Proxy0Service",
	 "foregroundServiceType": "dataSync", "supertypes": []},
	{"type": "receiver", "userClass": "com.example.BootReceiver",
	 "proxyClass": "com.example.quickbuild.proxies.Proxy0Receiver", "supertypes": []},
	{"type": "provider", "userClass": "com.example.DataProvider",
	 "proxyClass": "com.example.quickbuild.proxies.Proxy0Provider",
	 "authorities": ["com.example.app.quickbuild.data"], "supertypes": []},
	{"type": "application", "userClass": "com.example.App", "supertypes": []}
]
```

- `supertypes`: the user-side superclass chain (project-compiled classes only,
  recorded from compiled class headers at proxy-app-build time; stops at the first
  non-project class). Feeds the restart closure (section 5).
- `authorities`: the post-rewrite (proxy-app-id) values, for diagnostics.
- Intent filters, `exported`, `permission`, `enabled`, meta-data are **not**
  duplicated into JSON — they transfer verbatim in the transformed manifest, and no
  JSON consumer reads them. The JSON carries only what the deploy policy and UX need.
- `application` appears as a component entry with no `proxyClass` (section 3).
- `foregroundServiceType` is carried for UX messaging ("restarting: foreground
  service changed"), not for any routing decision.

Transformer model: `ProxiedActivity` generalizes to
`ProxiedComponent(type, userClass, proxyClass, isLauncher, foregroundServiceType?,
authorities, supertypes)`; `ManifestTransformResult.activities` remains as a
filtered view so existing call sites keep compiling.

## 2. Manifest transformation rules

Uniform rule, matching today's activities: **every** manifest component is proxied,
whether its class is user code or library code — keeping the transform free of
user-vs-library discrimination. The proxy is a generated `extends <userClass>` subclass
compiled in the proxy app build; for most library components the superclass resolves from
the compile classpath, but two shapes break that compile and fail the proxy app build loud
(never stale, but Quick Build cannot start): a component class present only on the
RUNTIME classpath (`extends` cannot resolve) and a `final` library class (cannot be
extended). See README "Known limitations". `ComponentProxiabilityResolver`
(`QuickBuildPayloadDexTask.checkProxiability`) generalizes the DETECTION of the `final`/
unresolvable shape - it reads a component's actual class file off the real proxy
compile's classpath and fails the proxy app build with one clear, actionable line before the
doomed javac attempt, instead of a raw multi-line diagnostic - but the SKIP itself still
has to be a NAMED exception below, because the only task that can affect the packaged
manifest (`QuickBuildGenerateSourcesTask`) runs before compilation in AGP's pipeline and
cannot safely read the variant's classpath or compiled classes without a real Gradle
task-graph cycle (tried both; each cycles back through `generateQuickBuildSources`
itself). Auto-skip (resolve from the runtime classpath, or detect + skip any final/
unresolvable library class automatically instead of naming each one) remains a tracked
followup, blocked on finding a cycle-free way to know the variant's classpath at
manifest-generation time. Nested user component classes ARE handled — the binary name
`Outer$Inner` is emitted as the canonical `Outer.Inner` in the proxy source. Proxy names
are manifest-order per type: `Proxy<N>Service`, `Proxy<N>Receiver`, `Proxy<N>Provider`
(companion of the existing `Proxy<N>Activity`).

**Named exceptions to the uniform rule** (`QuickBuildManifestTransformer`,
`UNPROXIABLE_LIBRARY_COMPONENTS`): these keep their real manifest name — never proxied,
no `ProxiedComponent` entry — because none of them is ever recompiled by the daemon, so
proxy-and-swap semantics buy nothing; left under their real name they instantiate through
the framework's default `AppComponentFactory` path exactly like any other Android app
(`LoaderRouter` falls back to the default loader for a class it can't find in the payload
dex, which none of these ever are). Checked once per component; a name added here costs
nothing at build time.

- `androidx.startup.InitializationProvider` (ADFA-4128 Bug 4): androidx's
  `AppInitializer` resolves its OWN component by hardcoded name
  (`PackageManager.getProviderInfo(ComponentName(pkg, InitializationProvider))`) to read
  its `<meta-data>` initializer list; every androidx library that uses the startup
  mechanism (lifecycle-process, profileinstaller, emoji2, WorkManager, ...) merges its
  initializer into this ONE provider element, so nearly every androidx-based project
  declares it. Renaming it to a `Proxy<N>Provider` FQN breaks the self-lookup with
  `NameNotFoundException -> StartupException`, crashing the proxy app during
  `handleBindApplication` before any activity or the quick-build runtime binds. This
  provider IS resolvable at proxy-app-build compile time (its jar is a transitive
  dependency), so it fails at RUNTIME via self-lookup, not at proxy-app-build compile time —
  a correctness exclusion, not a classpath one.
- `androidx.compose.ui.tooling.PreviewActivity` (ADFA-4128 Bug 7): `final` — the
  generated `Proxy<N>Activity extends` it cannot even compile (`cannot inherit from
  final ...`). Debug-only Compose `@Preview` tooling: never a real launcher, never
  edited by user code. Blocked every Compose template's proxy app build.
- `androidx.profileinstaller.ProfileInstallReceiver` (ADFA-4128 Bug 7): resolves on
  some variant classpaths (it's in the merged manifest) but not on every proxy app build's
  proxy-compile classpath (an AGP/transitively-injected runtime-only dependency in some
  projects, absent in others — e.g. present for the Compose template that hit this, but
  the plain Java BasicJ template that hit Bug 4 declares no such receiver at all), so
  `extends` fails "cannot find symbol". The same runtime-only-classpath hazard as the
  LogSender case below, generalized by exclusion instead of by adding the AAR to the
  classpath — simpler because, unlike LogSender, nothing needs this receiver proxied
  (checked its decompiled bytecode: no self-lookup, so proxying it would be harmless IF
  it compiled; excluding it is just the cheaper fix). A classpath fix (resolve every
  component from the runtime classpath generally) would obsolete this entry along with
  the LogSender special-case below, but isn't built yet.
- `androidx.room.MultiInstanceInvalidationService` (ADFA-4128, generalizing Bug 7): also
  `final`, same shape as `PreviewActivity` above (`Proxy<N>Service extends` it fails
  `cannot inherit from final`) — but discovered via a real project's live proxy-app-build
  crash rather than a template, since it's Room's own manifest-merged service rather
  than a Compose/androidx-tooling one. Any Room project using multi-instance
  invalidation tracking pulls this in.

| Component | Rewritten | Verbatim | Unsupported for v1 -> proxy app build fails with a named diagnostic |
|---|---|---|---|
| `<service>` | `android:name` -> proxy FQN | intent-filters, `exported`, `permission`, `enabled`, `directBootAware`, `foregroundServiceType`, `meta-data` | `android:process`, `android:isolatedProcess="true"` |
| `<receiver>` | `android:name` -> proxy FQN | intent-filters, `exported`, `permission`, `enabled`, `meta-data` | `android:process` |
| `<provider>` | `android:name` -> proxy FQN; `android:authorities` (rule below) | `exported`, `grantUriPermissions`, `permission`/`readPermission`/`writePermission`, `path-permission`, `initOrder`, `meta-data` | `android:process`, `android:multiprocess="true"` |
| `<application>` | `android:name` stays the **user** class (no proxy; section 3); auto-backup neutralized: `android:allowBackup="false"`, `backupAgent`/`fullBackupContent`/`dataExtractionRules` dropped | everything else already handled by v1 | — |

**Authority rewrite:** authorities already under the proxy-app id pass **verbatim** —
the plugin applies the `.quickbuild` suffix via `applicationIdSuffix` before the
manifest merges, so AGP resolves `${applicationId}` to the suffixed id and those
authorities are already correct (re-prefixing would double the suffix). Each authority
under the real `applicationId` (i.e. hardcoded) is rewritten to start with the
proxy-app id. Authorities that embed neither id are left **verbatim**:
rewriting them would silently break user code that queries the literal string, while
leaving them fails *loud* — an `INSTALL_FAILED_CONFLICTING_PROVIDER` at proxy-app
install time if the real app is also installed, surfaced as a setup failure.
Loud beats silent (never-stale spirit).

**Fallback shape for unsupported attributes:** the transformer throws with the
component name and attribute, the proxy app build fails with the message intact, and the
session never starts — the user is told to use a Standard Run. No stripping, no
silent component loss. Multi-process support is a tracked follow-up (per-process
payload/generation coherence is unverified).

API 31+ `exported` explicitness needs no handling: the merged manifest of a
buildable app already satisfies it, and we transfer it verbatim.

## 3. Proxy shape, factory overrides, early init, persisted generation

**Proxy shape: thin `extends` subclass for all four types**, same reasoning as
activities — proxy and user class both travel in the payload dex, so a swap replaces
the whole hierarchy together. Per type:

- `Proxy<N>Service extends UserService` — overrides `onCreate`/`onDestroy` only to
  register/unregister with a runtime `ServiceTracker` (`super` always called). Gives
  the runtime an honest live-service census for UX and future policy tightening.
- `Proxy<N>Receiver extends UserReceiver` — empty body. Manifest receivers are
  instantiated fresh per delivery through the factory, so they always run current
  code with no tracking needed.
- `Proxy<N>Provider extends UserProvider` — empty body.
- `Application` gets **no proxy**: nothing external addresses it by manifest name,
  and `instantiateApplication` already routes through the payload loader. The
  transformer keeps the user FQN and records it in `components` for the restart
  closure.

**Factory:** `QuickBuildAppComponentFactory` adds the three missing overrides —
`instantiateService`, `instantiateReceiver`, `instantiateProvider` (all API 28+,
matching the device floor). Each follows the existing contract exactly:
`ensureBaseline(cl)` first, `pickLoader` second, fall back to the default loader on
any failure so an injected app never crashes because of us.

**Provider early-init ordering:** the framework calls `instantiateApplication`, then
`instantiateProvider` for each provider, then `Application.onCreate`. Since
`instantiateApplication` already runs `ensureBaseline`, the payload loader exists
before any provider instantiates; the `ensureBaseline` call inside
`instantiateProvider` is defense-in-depth for exotic entry orders, not the primary
path. `QuickBuildRuntime.install` keeps deferring Context-dependent work — nothing
in provider init may touch the binder client.

**Persisted current generation (revises plan D1's "nothing on disk").** Today a
fresh process always boots on the baked `gen-0` baseline and catches up over binder.
That is fine for activities (recreate applies the catch-up) but breaks
restart-based swap: providers and the Application instantiate *before* the binder
connects and are never re-instantiated, so their code would be pinned to the
baseline forever, and a sticky service the OS restarts early could run baseline code
silently — a never-stale violation. Therefore:

- On every accepted deploy the runtime persists the current payload (dex + arsc +
  assets) to its private files dir, newest generation only, keyed by a baseline
  fingerprint from the proxy app build.
- Process start loads the newest persisted payload instead of `gen-0`; on a
  fingerprint mismatch (a proxy app rebuild/reinstall happened) or any read/parse failure it
  deletes the persisted payload and falls back to `gen-0`. Loading stays
  `InMemoryDexClassLoader` from bytes.
- Binder catch-up remains the reconciliation path when CoGo advanced while the app
  was dead — the same sanctioned mechanism as today, now starting from a much
  nearer generation.

## 4. Deploy policy: restart vs recreate

After a successful code-bearing quick build, the executor decides:

- **Activity recreate (hot swap, today's path)** when the recompiled class set does
  not intersect the restart closure.
- **Process restart** when it does — i.e. the recompiled set touches any
  service/provider class, the custom `Application`, one of their user-side
  supertypes, or a nested class of any of those (section 5). Receivers are
  deliberately *not* in the restart set: fresh per-delivery instantiation through
  the factory already serves current code.
- Resource-only and asset-only deploys never restart.

**How the restart is driven honestly:**

1. CoGo sends the payload normally with deploy metadata `"restart": "true"`
   (string, per the runtime's MiniJson convention).
2. The runtime persists the payload (section 3), acks, and exits its process
   (`Process.killProcess(myPid())`) instead of hot-swapping.
3. CoGo observes the binder death, then relaunches the proxy app via an explicit
   launch intent to the launcher proxy — CoGo is the foreground app while the user
   edits, so the launch is permitted.
4. The fresh process boots from the persisted newest generation, so every component
   — provider, Application, service, activity — instantiates current code; binder
   catch-up reconciles if anything advanced meanwhile. A killed-and-relaunched test
   app catching up to the newest generation is the existing, never-stale-safe
   mechanism; restart just invokes it deliberately.

**What the user sees:** CoGo's session status line reports
"Restarting <app> — <service|provider|Application> code changed"; the proxy app
relaunches to its launcher activity. Back stack and all in-process state are lost —
inherent to a restart, same trade Android Studio's Apply Changes makes on structural
changes. The deploy metrics event records `restart` as the outcome flavor.

**Skew guard:** an installed baseline whose runtime predates v2 would ignore the
unknown `"restart"` field and hot-swap instead — stale. `setup.json` records the
runtime AAR version baked at setup time; when the baseline's runtime is pre-v2, any
restart-requiring deploy routes to a full proxy app rebuild instead. The proxy app rebuild also
remains the answer for everything outside this contract (ADR 0010 boundary
unchanged: manifest edits, deps, native, processor input).

**Accepted residual (documented, not silent):** a *live* service or provider
instance keeps calling old copies of recompiled non-component helper classes until
its next restart — the loader-swap mechanism cannot update live object graphs, only
instantiation. The closure rule catches every change to the component's own code;
helper-only changes leave a bounded window identical in kind to an activity
mid-recreate. Goes in README Known limitations; the tracked tightening (behind a
flag, priced by metrics) is "restart on any code deploy while a tracked service is
live", which `ServiceTracker` already enables.

## 5. Restart-closure signal

The path-shape `ChangeClassifier` is unchanged — it cannot know recompiled classes
before compilation. The restart decision is post-compile, in a new pure domain type
(`DeployPolicy` beside the classifier; JVM-unit-testable, >=90% bar):

- **Recompiled class set:** the daemon's `compile` response gains
  `"classesChanged": ["relative/Foo.class", ...]` — the class files this
  incremental run emitted. The executor maps paths to FQNs (`Outer$Inner` counts as
  `Outer`'s nested family via `Outer$` prefix matching).
- **Component classes:** from `setup.json`'s `components` (section 1), which is how
  the manifest facts reach the CoGo side.
- **Supertype index:** populated from the baked `supertypes` chains at baseline, then
  kept live by the executor parsing the class-file headers (superclass +
  interfaces — a cheap constant-pool read, `ClassOpener` territory) of each build's
  changed classes. This catches hierarchy edits: re-parenting a service recompiles
  the service itself (direct hit) and updates the index so the *new* parent's future
  edits also hit.
- **Decision:** restart iff
  `recompiled ∩ (componentClasses ∪ supertypes ∪ nested-of-either) != ∅`, over
  services, providers, and the Application entry only. Deterministic, computed
  entirely CoGo-side, no runtime round-trip.

## Test obligations (per README "Verifying changes")

`DeployPolicy` + transformer + `QuickBuildJson` v2 + factory/persistence each get
JVM suites; a new corpus app with a service/receiver/provider declares
`expected.route` plus a restart-vs-recreate oracle; the device walk covers the
restart path (service edit -> relaunch -> event delivery) and the overlay/fallback
behavior, not just the happy path.
