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

1. **Custom XML attributes (`declare-styleable`) cannot work.** A payload-defined `attr`
   compiles into the payload's `0x80` attr namespace; styled-attribute resolution runs
   against the host Activity's theme (`0x7f`/`0x01`). Same wall as CoGo plugin theming.
   Style custom views from code. (Payload `<style>`s containing only `android:` items DO work.)
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
