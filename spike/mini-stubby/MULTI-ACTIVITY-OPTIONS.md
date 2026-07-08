# Multi-Activity for hot-loaded payloads: VirtualApp, Instrumentation hooks, and the less-fragile path (ADFA-4128)

Context: a hot-loaded payload can't register its own `<activity>` in the shell's fixed
manifest. The spike already ships a working **proxy-Activity** answer. This doc explains the
heavier "transparent" approaches (VirtualApp, Instrumentation interception), whether they're
testable across OS versions, and why the proxy the spike already has is the right target.

## What VirtualApp did, and why it's the fragile extreme

**Goal.** Run an *arbitrary, unmodified, already-built* APK — one not installed and not in the
host manifest — inside the host's own process, so the guest app believes it is normally
installed. This is the basis of "app cloning / dual-space." (asLody/VirtualApp; forks
BlackBox, NewBlackbox.)

**How it works — it impersonates the Android framework.** The OS only talks to apps through a
few singleton Binder proxies and per-thread objects; VirtualApp swaps those for its own:

- **Reimplements AMS and PMS inside the app** (`VActivityManagerService`, `VPackageManagerService`)
  and replaces the cached `IActivityManager` singleton (`ActivityManager.IActivityManagerSingleton`,
  older `ActivityManagerNative.gDefault`) and `PackageManager` (`ActivityThread.sPackageManager`)
  so every `startActivity`/`getPackageInfo`/`resolveActivity` from the guest is intercepted and
  answered with fake "you're installed" data.
- **A pool of pre-declared stub components** in the host manifest — `StubActivity$C0…C{N}` in
  several launchMode/process variants, plus `StubService`, one `StubContentProvider`. On launch
  it does the **intent-swap** ("占坑/pick-a-pit"): rewrite the guest Intent to target a stub the
  OS knows about, stash the real Intent as an extra; the system launches the stub; then
  **`Instrumentation.newActivity` swaps the stub back for the real guest Activity class**, loaded
  from a per-guest `DexClassLoader`.
- **Hooks `ActivityThread.mInstrumentation`** (subclass `AppInstrumentation`, self-heals via an
  `isEnvBad()` re-inject) and `ActivityThread.mH`'s `mCallback` to un-stub at handler time.
- **Fakes the package environment**: a fake `LoadedApk`, per-guest ClassLoader, redirected
  `/data/data/<guest>` paths (historically via native `open`/`stat` syscall hooks).

**Why it's fragile — the treadmill.**
1. **Non-SDK (hidden-API) restrictions.** Nearly every hook reflects into non-SDK internals
   (`ActivityThread`, `Instrumentation`, `IActivityManager`, `LoadedApk`, `Singleton`). Android 9
   greylisted these; 10 tightened; **11 killed the "meta-reflection" bypass** VirtualApp relied on.
   Post-11 you need native/JNI unrestriction shims that are themselves version-brittle.
2. **Per-OS churn of the launch pipeline.** Android 9 replaced discrete `H.LAUNCH_ACTIVITY`
   handler messages with `ClientTransaction`/`LaunchActivityItem` (`H.EXECUTE_TRANSACTION`), so the
   companion hooks and intent-restore timing had to be rewritten. Android 10+ scoped storage broke
   IO-redirect; Android 14 stricter background-activity-start + "safer intents" constrains the
   intent-swap. Every major release is a re-port.
3. **Play Integrity / SafetyNet.** The guest runs under the host's UID/signature with a hooked
   framework, so device attestation fails — Google actively flags virtualization engines.
4. **Security / abuse.** The host can read guest data, intercept Binder, inject code — VirtualApp
   is a known spyware/repackaging vector (the BlackBox README literally says "do anything you want").
5. **Maintenance → staleness.** Open VirtualApp went commercial; BlackBox last released 2022;
   only community forks (NewBlackbox) and commercial engines claim Android 14/15. Full
   virtualization is a permanent treadmill.

**Relevance to us: none of it is needed.** VirtualApp's whole burden comes from running
*arbitrary unmodified installed APKs*. CoGo emits and loads *its own* payload with full
cooperation — so it never needs PMS/AMS virtualization, fake-install, or IO redirect. The only
thing worth borrowing is the *idea* of a stub/proxy Activity pool, which the cooperative
frameworks isolate cleanly.

## The Instrumentation-hook middle ground (VirtualAPK / RePlugin)

The cooperative plugin frameworks keep the intent-swap but drop the PMS/AMS virtualization.
VirtualAPK hooks just `Instrumentation.execStartActivity` (rewrite plugin-Activity intent → stub)
and `newActivity` (restore from the plugin ClassLoader), with a small pre-declared stub pool.
Much smaller blast radius than VirtualApp — but it **still replaces `ActivityThread.mInstrumentation`
by reflection**, so it still rides the non-SDK treadmill (§1–§2 above): the field name
`mInstrumentation` is stable across 8→16, but the *legality of writing it* (blocked without a
bypass on 11+) and the *surrounding transaction machinery* are not. RePlugin goes further
(hooks the host `PathClassLoader`) and is effectively stuck at ~Android 9.

## Can we test the Instrumentation approach across OS versions via emulator? YES — cheaply.

This is one of the few places emulators are clearly the right tool. Two reasons the usual
"physical A56 only" rule (from `cloud-android-emulator.md`) doesn't apply here:

- **The Instrumentation hook is pure-Java framework reflection — architecture-independent.** It's
  about `ActivityThread`/`Instrumentation` internals, not native code. So **x86_64 emulators are
  fine** (unlike CoGo's native-heavy build, which forced the ARM64 physical device). The payload
  dex is architecture-independent too (as long as the test payload has no native `.so`).
- **What you're testing is exactly "does the hidden-API hook survive on API N"** — a per-OS-version
  behavior question, which is what an AVD matrix answers.

**Concrete test setup (feasible on the Mac Mini or a CI runner):**
- Create x86_64 AVDs at **API 26, 28, 29, 30, 31, 33, 34, 35, 36** (the OS versions where the
  non-SDK policy and launch pipeline changed: 28→29 non-SDK tightening, 30/31 the 11 bypass
  removal, 34 BAL changes). Google ships x86_64 system images for all of these.
- Install a tiny shell app that (a) installs the Instrumentation hook and (b) launches a
  DexClassLoader-loaded payload Activity via the swap. Assert it renders + back-stack works;
  watch logcat for `NoSuchFieldException`/hidden-API `AccessException`/`ClassCastException`.
- Run headless via `emulator -no-window` + `adb`, scripted across the AVD list (~a few minutes
  each). This gives a definitive per-version pass/fail matrix for the hook.

**Caveats worth stating:** (1) emulator system images have hidden-API enforcement *identical* to
retail for these checks, so a pass/fail is meaningful — but (2) some OEM builds (Samsung/MIUI)
harden reflection or change `ActivityThread` details beyond AOSP, so an AOSP-AVD pass is
necessary-but-not-sufficient; you'd still smoke-test the hook on the physical A56 (One UI) and
ideally one Xiaomi. (3) `google_apis` images (with Play) are stricter about attestation than
plain `aosp` images if you also want to observe Play Integrity behavior. Net: **an x86_64 AVD
matrix is a cheap, high-signal way to know exactly which OS versions the Instrumentation hook
survives on — do this before betting on it.** I did not run it in this spike (no emulators
provisioned on this machine), but it's a half-day of scripting, not a research risk.

## The less-fragile answer — and the spike already ships it

The research is unambiguous: **Tencent Shadow** hosts multiple plugin-Activities in one process
with **zero reflection and zero hidden-API** — it passes strict-mode non-SDK checks and is in
production in QQ (hundreds of millions of users). Its mechanism is a **delegating container
Activity**: declare one generic `PluginContainerActivity` in the host manifest; it instantiates
the plugin Activity from a `DexClassLoader` and **forwards every lifecycle callback**
(`onCreate/onResume/onPause/onSaveInstanceState/…`) to it. No `ActivityThread`/`Instrumentation`
patching, so it's off the non-SDK treadmill, off Play Integrity's hooking radar, and doesn't
re-break each Android release.

**The spike's `ProxyActivity` IS this pattern** (a lighter version of it). It's a generic host
Activity declared once in the manifest that loads the payload "screen" from the live payload
classloader and renders it — real window, real back-stack, verified on-device. The gap between
what I shipped and full Shadow-grade transparency is only:

- **Screen contract.** My payload screens expose `render(Activity)` and are launched via an
  explicit proxy Intent; Shadow's plugin screens are real `Activity` subclasses whose lifecycle
  the container forwards. Closing that gap = have `ProxyActivity` forward the full lifecycle to a
  payload `Activity`-shaped object (no reflection needed — the payload defines a small base type
  the container calls). This is ordinary code, not a hidden-API hook.
- **Transparent `startActivity`.** Only *this* — making `startActivity(Intent(this, Foo.class))`
  work without the explicit proxy Intent — needs the Instrumentation `execStartActivity` hook.
  It's the one piece that buys "the payload is unmodified Android code," at the cost of the
  treadmill.

## Recommendation

Ranked by fragility, least to most:

1. **Single-Activity + view/Fragment/Compose navigation** (what CoGo's templates and most target
   apps already are). No manifest, no hooks, zero risk. Already fully working in the spike.
2. **Shadow-style delegating `ProxyActivity`** for genuine multi-`Activity` apps — extend the
   spike's proxy to forward the full Activity lifecycle to a payload `Activity`-shaped base. Real
   Activity semantics, still **zero hidden-API**. This is the recommended target for multi-screen.
3. **Instrumentation `execStartActivity` hook** *only* if fully-transparent unmodified
   `startActivity` is a hard requirement — and only after running the x86_64 AVD matrix above to
   know which OS versions it survives on, with a plan to re-port each Android release. Treat as a
   last resort, not the default.

Do **not** pursue VirtualApp-style full virtualization: it solves a problem CoGo doesn't have
(arbitrary installed APKs) at a maintenance cost nothing in the open-source ecosystem is paying
past Android 13.
