# Phase 3 results — complex app on the Mini-Stubby shell (ADFA-4128)

Device: Samsung A56, Android 16. Date: 2026-07-08. All numbers measured, not projected.

Payload: **FieldSurvey** — an offline water-point survey app. 13 classes / 3 packages,
custom `Canvas` View referenced by class name in layout XML, ListView + BaseAdapter,
shape/selector drawables, styles + `values-night/`, two AlertDialog flows, ValueAnimator,
SharedPreferences, background thread + Handler, `org.json` over a bundled asset,
state-preserving reload via `saveState()` / `render(Activity, Bundle)`.

## What worked (all verified on-device)

| Stressor | Result |
|---|---|
| Custom `View` by class name in layout XML | **Works** — needed a payload-classloader `Factory2` on the Activity's inflater |
| Reload N+1 with custom views on screen | **No `ClassCastException`** — factory retargets classloader + clears ctor cache per generation |
| Assets (`getAssets().open`) served from the loader apk | **Works** — least-proven mechanism going in; questions.json parsed and rendered |
| Resources: layouts, styles, drawables, `values-night/` | **Works**, incl. system dark-mode toggle |
| ListView + adapter, dialogs, Canvas, ValueAnimator | **Works** |
| SharedPreferences across reloads *and* restarts | **Works** |
| State-preserving hot reload (`saveState()`) | **Works** — reloaded mid-survey, ratings + route preserved |
| Bad payload (render throws) | **Old UI keeps running**, correct resources, status shows "load FAILED"; next good save recovers |
| Multidex payload (1.4 MB, 3 dex, 84.7k methods) | **Loads** — `18/18` sampled classes incl. secondary-dex |

## Reload latency does NOT scale with app size

Device-side reload (detect → rendered), FileObserver to first frame:

| Payload | Size | Dex | Reload |
|---|---|---|---|
| FieldSurvey | 29 KB | 1 | 36–48 ms |
| stress S | 48 KB | 1 | 54 ms |
| stress M | 238 KB | 1 | 44 ms |
| stress L | 786 KB | 1 | 63 ms |
| stress XL | 1.4 MB | 3 | 54 ms |

**Deployment is effectively O(1) in app size.** What scales is *compilation*
(Mac-side, warm: XL javac 2.05 s + d8 3.83 s). The <1 s budget is spent entirely
in the compiler, never in the shell.

## End-to-end save→rendered (one clock, complex app, over adb)

| Edit kind | Total | Breakdown |
|---|---|---|
| Java only | **487–580 ms** | javac 85–132, d8 55–75, aapt2 cached, deploy 224–292 |
| Resource edit | **918–979 ms** | + aapt2 relink 334–343 |

On-device warm compile (CoGo's own bundled JDK 21 + d8, `bench/RESULTS.md`) is ~300 ms,
and on-device "deploy" is a local file write instead of `adb push` (~250 ms) — so a
CoGo-hosted daemon projects to **~350 ms save→rendered** for code edits.

## Real limits found

1. **Custom XML attributes: direct use WORKS; `?attr/` theme lookup does not.**
   (Corrected 2026-07-08 by direct on-device test — an earlier note here claimed, by false
   analogy to CoGo's plugin system, that custom attrs couldn't work at all. They can.)
   - **Works:** `<declare-styleable>` + `app:fsLineColor="#FFCC00CC"` in a payload layout,
     read by the payload's own View via `obtainStyledAttributes(attrs, R.styleable.X)`.
     Verified: `indexCount=2 fsLineColor=#ffcc00cc fsLineWidth=16.875`. It works because
     the view class, its `R.styleable` int array, and the compiled layout **all come from
     the payload** at package id `0x80` — self-consistent. CoGo *plugins* fail here for a
     different reason: their Material widget classes load **parent-first from the host apk**
     and resolve attrs against the host's `0x7f` ids, never seeing the plugin's table.
   - **Fails:** referencing a payload-defined attr through the **theme** (`?attr/fsLineColor`).
     `UnsupportedOperationException: Failed to resolve attribute at index 5:
     TypedValue{t=0x2/d=0x80010000}, theme={... android:style/Theme.DeviceDefault.DayNight ...}`
     — the Activity's theme chain is built from host + framework resources and contains no
     `0x80` attrs. Consequence: a payload cannot ship a theme whose attrs its own layouts
     reference. This is the real, and much narrower, limitation.
   - Implication for real apps: **Material3/AppCompat widgets reference `?attr/colorPrimary`
     etc. through the theme.** If those library classes load from the payload's own dex (with
     library resources merged into the payload table) the ids are self-consistent and this
     may work — but the Activity must then also be themed `Theme.Material3.*`, which the
     shell's `Theme.DeviceDefault` is not. **Untested. This is the #1 thing to test next.**
2. **`saveState()` Bundle must hold framework types only.** A payload-defined class in the
   Bundle carries the old generation's classloader into the new one → `ClassCastException`.
3. **Fragments are unsupported** as-is — the shell is a plain Activity. Real support needs a
   payload-classloader-aware `FragmentFactory` (CoGo's plugin system already has this pattern).
4. **The manifest is not dynamic.** Activities/permissions/app icon can't come from the payload.
   Multi-Activity user apps need pre-declared proxy Activities in the shell (Shadow's pattern).
5. **Activity recreation** (rotation, system dark-mode toggle) resets the generation counter and
   drops in-progress `saveState()` data — persisted data survives. Fixable (persist the Bundle).
6. **Reload while an old-gen dialog is open** leaves that dialog acting on a detached view tree.
   Cosmetic; a real implementation should dismiss payload dialogs on reload.

## Two real bugs the build found (both fixed)

- **Stale read-only `payload-genN.apk`**: on relaunch the generation counter resets to 1 but the
  previous process's read-only gen-1 file still exists → `EACCES`. Delete before copy.
- **Resource rollback on failed load**: when `render()` threw *after* the ResourcesLoader swap,
  the still-mounted old UI resolved its ids against the *new* resource table (aapt2 renumbers ids
  when resources are added/removed) → wrong strings or `Resources$NotFoundException`. The catch
  now restores the previous loader, symmetric with the classloader rollback.

## Ask-Claude on the complex app

Prompt typed on the phone: *"Add a search box above the visit list that filters visits by site
name as you type."* Claude (headless `claude -p`, cwd = payload sources) edited the multi-class
app, the daemon rebuilt each save, and the shell hot-reloaded — final feature works, including a
"no matches" empty state and query preservation across reload (Claude wired it into `saveState()`
on its own). Two intermediate reloads at 487 ms and 609 ms while Claude was still editing.

## Coverage honesty: what phase 3 did NOT test

FieldSurvey is a *framework-only, Java* app. Real CoGo user apps are not. From
`assets/core.cgt` (the emitted templates): every template but `NoAndroidX` pulls in
**androidx.appcompat + com.google.android.material**, extends **`AppCompatActivity`**,
and is themed **`Theme.Material3.DayNight`**. `ComposeActivity` pulls the **Compose BOM**.
Five of six template Activities are **Kotlin**.

Untested, ranked by how likely they are to bite:

1. **androidx / Material3 payloads.** Needs the Activity themed `Theme.Material3.*` (shell is
   `Theme.DeviceDefault`) — Material widgets throw `IllegalArgumentException: The style on this
   component requires your app theme to be Theme.MaterialComponents (or a descendant)`. The
   shell must adopt the payload's theme, which the manifest can't supply. Likely solvable by
   applying the payload's theme programmatically (`setTheme`) before inflation.
2. **Kotlin payloads.** The devloop only runs `javac`. Real per-save compile needs `kotlinc`
   (much heavier than javac — the <1 s figure is a Java figure, not yet a Kotlin one) plus
   bundling `kotlin-stdlib` into the payload dex.
3. **Jetpack Compose payloads.** No XML inflation at all; a `ComposeView` needs a
   `LifecycleOwner`/`SavedStateRegistryOwner` on the host Activity. Different animal entirely.
4. **AppCompatActivity semantics** — the payload gets a plain `Activity`; anything expecting
   `AppCompatActivity`, a `Lifecycle`, or a `ViewModelStoreOwner` fails.
5. **Fragments** (known), **multiple Activities / `startActivity`**, Services, BroadcastReceivers,
   ContentProviders — all manifest-bound, hence proxy-component territory.
6. **Native `.so` payloads** (the ticket's step 4), **runtime permissions**, **notifications**,
   **launcher icon / deep links** — all shell-owned, not payload-owned.
7. **kapt/KSP processors** (Room, Moshi) — separately known to be unrunnable on-device.
8. **Multi-module Gradle projects**, release/signed output for sharing the finished app.
9. **Debugging ergonomics**: stack traces from payload dex map to source; step-debugging does not.

The phase-3 result is therefore: *the loading mechanism is sound and fast for a framework-only
app.* Whether it holds for the apps CoGo actually emits (androidx + Material3 + Kotlin, often
Compose) is the next and much larger question.
