# Phase 4 — real CoGo-style payloads (androidx / Material3 / Kotlin / Compose)

Device: Samsung A56, Android 16. Date: 2026-07-08. Follows the phase-3 coverage-gap list.

Payloads are now built by **real AGP** (`payload-gradle/`), so androidx + Material get
correct resource merging and dexing — the apps CoGo actually emits. The enabling trick:

```kotlin
android { androidResources { additionalParameters +=
    listOf("--package-id", "0x80", "--allow-reserved-package-id") } }
```

AGP links the app **and every library's resources** into one table at package id `0x80`,
disjoint from the host shell's `0x7f`, so `ResourcesProvider.loadFromApk` merges a full
dependency tree with no id collision. (`aapt2 dump packagename` → `id=80`, verified.)

## Results

| Case | Result | Notes |
|---|---|---|
| **androidx + Material3 widgets** (`MaterialCardView`, `MaterialButton`) | **WORKS** | after the theme fix below |
| **`?attr/colorPrimary`** theme-attr lookup in payload layout | **WORKS** | resolves to the payload theme's color |
| **Material theme enforcement** | **FIXED** | see below |
| **Kotlin payload** (`object Main`, kotlin-stdlib) | **WORKS** | stdlib dexed into the payload; 20 MB / 3 dex reloads in 111 ms |
| **Snackbar** | **WORKS** | no crash |
| **Theme isolation across payload swaps** | **WORKS** | no-theme payload reverts to shell theme |

## The Material3 theme wall — reproduced and fixed

Deploying an unmodified AGP Material3 apk to the phase-3 shell threw exactly the predicted
error: `IllegalArgumentException: The style on this component requires your app theme to be
Theme.MaterialComponents (or a descendant)` (`ThemeEnforcement.checkTheme`). The shell's
Activity was `Theme.DeviceDefault`.

**Fix (shell):** read the payload's declared theme from its manifest via
`getPackageArchiveInfo(apk).applicationInfo.theme` (a `0x80` style id, resolvable once the
payload table is merged), and apply it onto a **fresh copy** of the Activity base theme on
every reload — fresh so themes don't accumulate across payload swaps. No special payload
method needed; works for any app that declares `android:theme`. Verified: Material3 card +
button + `?attr/colorPrimary` all render correctly; a subsequent no-theme payload reverts.

## Kotlin compile latency — the honest correction to the <1 s headline

The phase-2/3 sub-second figure was measured with **`javac`** (~100 ms warm). CoGo's default
language is **Kotlin**, which is fundamentally heavier:

| Compiler | Warm (Gradle daemon) | Cold (JVM+compiler startup) |
|---|---|---|
| javac (in-process) | ~100 ms | — |
| **kotlinc, single-file edit** | **620–900 ms** (`compileDebugKotlin` alone) | ~1500 ms |

So for a Kotlin payload, **compile alone is 0.6–0.9 s warm** — realistic Kotlin
save→rendered lands around **1–1.5 s, not sub-second**, and only with a *persistent* Kotlin
daemon (per-save Gradle would be worse). Deployment stays flat (~40–110 ms). The sub-second
promise holds for Java; for Kotlin the target is "1–1.5 s", and hitting even that needs a
warm on-device Kotlin compile service — the single biggest open latency question.

## Jetpack Compose — WORKS (the hardest case)

CoGo ships a `ComposeActivity` template. A `ComposeView` walks up the view tree at
window-attach looking for **ViewTreeLifecycleOwner / ViewTreeViewModelStoreOwner /
ViewTreeSavedStateRegistryOwner** — none of which a plain `android.app.Activity`
provides. Reproduced exactly: `IllegalStateException: ViewTreeLifecycleOwner not
found from android.widget.LinearLayout` (the shell's own root).

**Fix (payload-side, no shell change):** the payload creates its own owner object
implementing all three interfaces (from its bundled androidx.lifecycle/savedstate),
drives a `LifecycleRegistry` to `RESUMED`, and sets the owners on the **host window's
decor view** (not just the ComposeView — Compose's window recomposer resolves the
owner from the top of the hierarchy). Then `ComposeView.setContent { … }`.

Verified on the A56: `Column` + `Text` + Material3 `Button` render, and tapping the
button increments a `remember { mutableStateOf }` counter — **"Recompositions: 3"** —
so recomposition on state change works, not just a static first frame. 27 MB / 4 dex
payload, reload 390 ms.

**Build-side caveat (real):** Compose is version-locked to the Kotlin compiler. AGP
8.11 + Kotlin 1.9.22 + legacy `composeOptions.kotlinCompilerExtensionVersion` failed
with `Couldn't inline remember` IR errors (the compose-compiler plugin wasn't applied
correctly). The clean fix was **Kotlin 2.0.21 + the first-party
`org.jetbrains.kotlin.plugin.compose` plugin**, where the compiler version tracks
Kotlin exactly. Implication for CoGo: an on-device Compose build path must pin a
coherent Kotlin/compose-compiler/BOM triple — this is a real toolchain-bringup cost,
separate from the (working) runtime hosting.

## Native .so, Fragments, and the manifest walls

| Case | Result | Mechanism |
|---|---|---|
| **Native `.so` (JNI)** | **WORKS** (shell fix) | shell `extractNativeLibs()` unpacks `lib/<abi>/*.so` to a private per-gen dir, passes it as the DexClassLoader library path. Verified: `System.loadLibrary` + two JNI calls (`add(20,22)=42`). Ticket step 4. |
| **androidx Fragments** | **WORKS** (payload fix) | payload supplies its own `FragmentController` + `FragmentHostCallback` backed by payload-created Lifecycle/ViewModelStore/SavedStateRegistry/OnBackPressedDispatcher owners; drives to RESUMED; `commitNow`. Verified interactive (Fragment button taps: 3). |
| **Multiple Activities** | **BLOCKED** | `startActivity(payload.SecondActivity)` → `ActivityNotFoundException: Unable to find explicit activity class {…/app.payload.SecondActivity}`. The manifest is fixed at shell-install time and can't register payload Activities. |
| **Runtime permissions** | **BLOCKED** | a permission the shell manifest doesn't declare (`CAMERA`) reports `checkSelfPermission=-1` and `requestPermissions` shows no prompt. Permissions are frozen in the shell manifest. |

**One subtle Fragment gotcha worth recording:** the `FragmentHostCallback.onGetLayoutInflater()`
must return `LayoutInflater.from(activity).cloneInContext(activity)`, **not** the Activity's
own inflater. The shell already called `setFactory2` on the Activity inflater (for payload
custom views), and Fragment insists on setting its own factory — the raw inflater throws
`IllegalStateException: A factory has already been set`. `cloneInContext` resets the
factory-set flag while inheriting the shell's Factory2, so both coexist.

## The manifest wall — the one real architectural blocker

Everything that lives in the app's **AndroidManifest** is frozen at shell-install time and
cannot come from the hot-loaded payload: **additional Activities, declared permissions, the
app icon/label, exported components, intent filters, custom `Application` class**. Three of
these have known shell-side fixes with real cost:

- **Multiple Activities** → pre-declare a small pool of generic **proxy Activities** in the
  shell and route the payload's `startActivity` through them (Tencent Shadow's pattern). The
  payload's "Activity" becomes a delegate the proxy hosts. Non-trivial: `Intent` rewriting,
  lifecycle forwarding, `onActivityResult`, task/back-stack semantics.
- **Permissions** → the shell manifest declares a **superset** of the permissions user apps
  might plausibly need; the payload just requests at runtime. Coarse but workable for a
  fixed catalog (camera, location, storage, mic, …).
- **Custom Application / providers / exported components** → generally not supported; a real
  design would document these as out of scope for hot-reload and require a full build.

So the honest architectural boundary is: **anything the OS reads from the manifest before
your code runs is the shell's, not the payload's.** Single-Activity apps (the overwhelming
majority of what CoGo's audience builds — and every CoGo template is single-Activity) are
fully covered. Multi-Activity and custom-permission apps need the proxy/superset scaffolding
above.

## Phase-4 bottom line

Of the phase-3 "untested" list, the four highest-value cases — **androidx+Material3, Kotlin,
Jetpack Compose, native .so** — all now work on-device, three of them after a targeted fix
(theme adoption, decor-view owners, native-lib extraction) and one payload-side pattern
(Fragment host). The remaining blockers are all the **manifest wall**, which is a known,
bounded problem with a known (if non-trivial) proxy-Activity + permission-superset solution.
The load-and-reload core is far more capable than phase 3 could show. The two genuinely open
questions are now: (1) **Kotlin/Compose on-device compile latency** (needs a warm non-Gradle
compile service to approach 1–1.5 s), and (2) the **proxy-Activity scaffolding** for
multi-screen apps.
