# Mini-Stubby capability matrix (ADFA-4128) — final

Every common Android app shape, tested on-device (Samsung A56, Android 16). This is the
one-page answer to "what can a hot-loaded payload do in the shell?"

| Capability | Status | How / fix | Where |
|---|---|---|---|
| Framework-only Java/Kotlin UI | ✅ Works | DexClassLoader + ResourcesLoader | phase 1–3 |
| Custom `View` in layout XML | ✅ Works | payload-classloader `Factory2` on Activity inflater | phase 3 |
| Resources / assets / `values-night` | ✅ Works | ResourcesLoader merge at package-id 0x80 | phase 3 |
| Custom `declare-styleable` attrs (direct) | ✅ Works | payload's own R.styleable, self-consistent at 0x80 | phase 3 |
| `?attr/…` **theme** lookup | ✅ Works | payload theme adopted onto Activity (see Material3) | phase 4 |
| State-preserving reload | ✅ Works | `saveState()` / `render(Activity, Bundle)` | phase 3 |
| Multidex (64k+ methods) | ✅ Works | ART opens all `classesN.dex`; shell packages all | phase 3 |
| **androidx + Material3** | ✅ Works | shell adopts payload's manifest theme per reload | phase 4a |
| **Kotlin** (CoGo default) | ✅ Works | stdlib dexed into payload | phase 4b |
| **Jetpack Compose** (interactive) | ✅ Works | payload supplies ViewTree owners on decor view | phase 4c |
| **Native `.so`** (JNI) | ✅ Works | shell extracts `lib/<abi>/*.so` → DexClassLoader path | phase 4d |
| **androidx Fragments** | ✅ Works | payload-supplied FragmentController + owners; clone inflater | phase 4e |
| **Multiple Activities** | ✅ Works | shell `ProxyActivity` + `PayloadRuntime` singleton | phase 4 (close) |
| **Runtime permissions** | ✅ Works | shell manifest declares a superset; payload requests | phase 4 (close) |
| Fully-transparent Activity (`startActivity` on a real payload Activity subclass) | ⚙️ Scope | needs `Instrumentation` hook (Shadow/VirtualAPK) + `onActivityResult` forwarding | — |
| Custom `Application` / exported components / providers | ⚙️ Scope | manifest-bound; out of hot-reload scope, needs full build | — |

✅ = verified working on-device. ⚙️ = known engineering scope, not a blocker for the common case.

## Latency (on-device, warm)

| Language | Warm compile | + dex + deploy | save→rendered (projected, warm service) |
|---|---|---|---|
| Java | ~100–300 ms | + ~30 ms d8 + ~40 ms reload | **~0.35 s** |
| Kotlin | ~350–550 ms | + d8 + deploy | **~0.5–0.8 s** |
| Compose | ~same as Kotlin | + d8 + deploy | **~0.5–0.8 s** |

Deployment is O(1) in app size (44–63 ms from 48 KB to 1.4 MB). The entire budget is the
compiler. **Requires a persistent in-process compile service** (cold Kotlin start is 6.6 s;
per-save Gradle is worse). No sub-1 s claim for Kotlin/Compose without that service.

## The one true architectural boundary

Anything the OS reads from the **AndroidManifest before your code runs** belongs to the
shell, not the payload: additional Activities (→ proxy), permissions (→ superset), app
icon/label, exported components, custom Application. Everything the payload's *code* touches
at runtime — views, resources, themes, native libs, Compose, Fragments — is fully hot-loadable.

## Recommended direction (unchanged, now fully evidenced)

1. Ship silent-install (PackageInstaller installer-of-record) as a near-term friction win.
2. Build Mini-Stubby v1 on the plugin-loader foundation: shell installed at Create Project;
   payload from the Gradle output; the shell fixes in this spike (theme adoption, Factory2,
   native extraction, proxy Activity, permission superset) are the required host changes.
3. The larger work item: a **persistent on-device compile service** (warm Kotlin/Compose
   compiler + d8, recompiling only changed files) — this is what buys the <1 s loop.
4. Optional later: transparent Instrumentation-based Activity interception if the
   `render()`-contract screen model isn't acceptable for multi-Activity apps.
