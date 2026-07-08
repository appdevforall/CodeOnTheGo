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
