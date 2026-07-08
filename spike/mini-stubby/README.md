# Mini-Stubby spike (ADFA-4128)

Throwaway prototype proving the "shell app + dynamically loaded user app" direction:
install a **shell APK once**, then hot-swap the user app's **code + resources +
assets** from an **unsigned, never-installed** payload apk, with no PackageInstaller,
no signing, no zipalign, and no Play Protect / install-confirmation prompts.

## What it is

- `host/` — the shell app (`com.adfa.ministubby.host`), pure framework APIs, no
  androidx, ~180 lines. Installed once. Watches `filesDir/payload/payload.apk`
  with a `FileObserver` and on every change:
  - copies it read-only into `codeCacheDir` (API 34+ blocks writable dex),
  - swaps resources via `ResourcesLoader`/`ResourcesProvider.loadFromApk` (API 30+),
  - loads code via `DexClassLoader` (parent = host classloader),
  - calls `app.payload.Main.render(Activity)` by reflection and re-mounts the view.
- `payload/` — a stand-in "user app": layout + strings/colors + an asset file +
  Java code with interactive state. Linked with **`--package-id 0x80`** so its
  resource ids never collide with the host's `0x7f` table.
- `tools/` — Mac-side build scripts (aapt2 → javac → d8 → zip; flox jdk17):
  - `build_host.sh` — build/sign/install the shell (the ONE-TIME install).
  - `build_payload.sh` — build the payload and hot-deploy it (simulates a save
    in the IDE). Push is `adb push` + `run-as` atomic rename; in real CoGo the
    IDE would hand the payload to the shell via content URI + broadcast, or the
    shell reads the project's build output directly.

## Measured on the Samsung A56 (Android 16), 2026-07-08

| Metric | Result |
|---|---|
| Reload latency, detect → payload view rendered | **17–34 ms** (8 reloads) |
| Cold start of shell with persisted payload | 82 ms |
| Payload build on Mac (aapt2+javac+d8, non-incremental) | ~1.3 s |
| Install/signing/Play-Protect prompts after the one-time shell install | **zero** |
| Code changes picked up (not just resources) | yes (button logic changed live) |
| Resource + asset changes picked up | yes (strings, colors, layout, assets/) |
| Rapid successive reloads | 6-in-6s stable, no leak-crash |
| Shell force-stop → relaunch | reloads latest payload from disk |

The <1s save→screen budget is therefore dominated entirely by the **compile step**
(Kotlin/Java → dex), not by deployment: the deployment half costs ~30 ms.

## What the spike deliberately skips (the real work items)

- Multi-dex / large apps, native `.so` payloads (ticket step 4), Activity/Service
  lifecycle beyond a single hosted view, manifest-declared components (each
  payload Activity needs a proxy/stub Activity in the shell — the classic
  dynamic-loading problem; see plugin frameworks prior art).
- Theme/attr interactions between host theme and payload resources.
- State preservation across reloads (currently the view is rebuilt from scratch).
- Classloader recycling (old generations are dropped, relying on GC).
- API 28/29 devices: `ResourcesLoader` is API 30+; CoGo minSdk is 28 → the
  `AssetManager.addAssetPath` reflection fallback already used by
  `plugin-manager/.../PluginResourceContext.kt` covers those.

## Direct prior art inside CoGo

`plugin-manager/` already dynamic-loads `.cgp` APKs with the exact same pair
(`DexClassLoader` in `loaders/PluginLoader.kt`, `ResourcesLoader` + addAssetPath
fallback in `loaders/PluginResourceContext.kt`, custom package-id handling
included). Mini-Stubby is "the plugin loader, applied to the user's app, in a
separate shell process".

## Build/run

```sh
tools/build_host.sh      # once
tools/build_payload.sh   # every "save"
adb logcat -s MiniStubby # watch reload latency
```
