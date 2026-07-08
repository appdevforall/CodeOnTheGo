# Mini-Stubby phase 3 — "complex app" stress test (ADFA-4128)

Goal: find out where the shell-app approach BREAKS by running a realistic,
non-toy Android app as the payload. Honest failure reporting beats a green demo.

Three components, built in parallel. These contracts are the source of truth.

## Payload entry contract (EXTENDED — backward compatible)

The shell resolves, in order:
1. `public static View render(Activity host, Bundle savedState)` — preferred
2. `public static View render(Activity host)` — phase-1/2 fallback

Optional, called by the shell BEFORE tearing down the old payload:
- `public static Bundle saveState()` — payload returns its own UI state; the
  shell passes it into the next `render(host, savedState)`. Enables
  state-preserving hot reload. Must never throw; shell tolerates absence.

Everything else unchanged: resources linked at `--package-id 0x80`, own `R`,
framework widgets only (no androidx), assets via `host.getAssets()`.

## Component D — the complex payload app ("FieldSurvey")

Replaces `payload/` with a plausible offline data-collection app — the kind of
thing CoGo's actual users build. It MUST exercise, at minimum:

1. **A custom `View` subclass referenced BY CLASS NAME in layout XML**
   (e.g. `<app.payload.ui.SparklineView .../>`). This is the single most
   important stressor — it forces the inflater to resolve a payload class.
2. **Multi-screen navigation** via an in-payload view stack (no Fragments — the
   shell is a plain Activity; note Fragments as a known gap, don't use them).
3. **`ListView` + a custom `BaseAdapter`** with a per-row layout + view holder.
4. **Custom `Canvas` drawing** in the custom view (a sparkline of survey scores).
5. **Drawables**: a `<shape>` background, a `<selector>` for button states.
6. **Styles + dimens + a `<style>` applied to widgets**, plus `values-night/`.
7. **An `AlertDialog`** flow (add/confirm-delete).
8. **A `ValueAnimator`** (e.g. animating the sparkline or a progress bar).
9. **`SharedPreferences`** persistence across reloads and restarts.
10. **A background thread + `Handler`** posting results to the UI.
11. **Assets**: parse a bundled `assets/questions.json` with `org.json`.
12. **≥10 classes across ≥3 packages** (`app.payload`, `app.payload.ui`,
    `app.payload.data`), incl. lambdas + an inner class (exercises desugaring).
13. **`saveState()`/`render(host, savedState)`** implementing state-preserving reload.

Keep it framework-only and self-contained. Style it to look like a real app.

## Component E — shell v3 (`host/`)

Keep everything from v2 (reload mechanics, Ask-Claude, /reloaded reporting).
Add:

1. **Payload-classloader-aware `LayoutInflater`.** The host's inflater resolves
   custom view class names against the HOST classloader, so a payload custom
   view in XML throws `ClassNotFoundException` (this is exactly the CoGo plugin
   `PluginFragmentHelper.getPluginInflater` lesson). Fix: hand the payload an
   inflater whose `Factory2.onCreateView` loads the class from the payload's
   `DexClassLoader` (cache constructors; fall back to the default path for
   framework widgets). The payload must be able to just call
   `LayoutInflater.from(host)` and have it work — so install the Factory2 on the
   inflater the payload will get. Document exactly how you achieved that (a
   `ContextThemeWrapper` overriding `getSystemService(LAYOUT_INFLATER_SERVICE)`
   is the plugin-system pattern; the payload receives that wrapper as its
   `Activity`-typed arg ONLY IF the signature allows — if it can't, pass the
   Activity but set the Factory2 on the Activity's own inflater once, and
   describe the tradeoff in your report).
   NOTE: `LayoutInflater.setFactory2` can only be called ONCE per inflater.
   Reload N+1 must not crash on "A factory has already been set". Solve this
   (e.g. a stable Factory2 that delegates to the CURRENT payload classloader
   via a mutable field). This is a real design constraint — get it right.
2. **`saveState()`/`render(host, savedState)` support** per the entry contract,
   with graceful fallback.
3. **Multidex payloads**: a payload apk containing `classes.dex` +
   `classes2.dex` must load (verify DexClassLoader handles it; if not, report).
4. **Status bar shows payload size + dex count** alongside gen/reload-ms, and
   the `/reloaded` POST body gains `"apkBytes":N,"dexCount":N`.

VERIFY with `SKIP_INSTALL=1 sh tools/build_host.sh`. Do NOT touch the device.

## Component F — devloop multidex + stress harness

1. **`devloop/` packaging must handle multiple dex files** (`classes.dex`,
   `classes2.dex`, …) — currently it zips only `classes.dex`. Also pass
   `--min-api 30` and let d8 produce multidex naturally. Keep all timing output.
2. **`stress/gen_stress.py`**: generates a synthetic payload overlay of N extra
   classes and M extra string resources into a SCRATCH copy of the payload (never
   mutate `payload/` itself), so we can measure reload latency vs payload size.
   Must be able to push past **64k methods** to force multidex (generate classes
   with many methods; document the knob).
3. **`stress/measure.md`**: a short runbook the main agent will execute on the
   device (the main agent owns all adb). Include the exact commands to build a
   stress payload at sizes S/M/L/XL and where the timing lines appear.
   Do NOT run adb yourself.

VERIFY: build stress payloads at a couple of sizes with the daemon in
`--dry-run` (no device), confirm multidex actually appears at the large size
(`unzip -l` shows classes2.dex) and that the daemon packages ALL dex files.

## Hard rules (all components)

- No device access (no adb) except the main agent. No git commands. No pushes.
- Toolchain via `tools/env.sh` (flox jdk17, aapt2, d8).
- Don't modify files owned by another component.
- Report honestly: if something can't work, say so and explain the mechanism.
