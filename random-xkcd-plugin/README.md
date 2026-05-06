# random-xkcd-plugin

A demo CoGo plugin that adds a "XKCD" tab to the editor bottom sheet.

| Gesture | Action |
|---|---|
| Tap | Fetch a new random xkcd comic |
| Double-tap | Copy the comic URL to the clipboard |
| Triple-tap | Copy the comic image (PNG) to the clipboard |

Long-press the tab title for a three-tier tooltip; the Tier 3 button
opens an in-IDE code walkthrough at
`http://localhost:6174/plugin/com.codeonthego.xkcdrandom/index.html`.

## Why this plugin exists

To be the canonical "small CoGo plugin" example. Optimized for clarity
over completeness — minimal classes, obvious naming, comments that
explain *why* not *what*.

Reading order:

1. `XkcdRandomPlugin.kt` — lifecycle, tab + docs registration.
2. `fragments/XkcdPanelFragment.kt` — UI, gestures, clipboard.
3. `net/XkcdApiClient.kt` — HTTP, two endpoints, no auth.
4. `ui/TapCountClassifier.kt` — 1/2/3-tap state machine, with unit tests.
5. `cache/XkcdDiskCache.kt` — last-comic on disk for offline render.

## Build

From the worktree root, inside `flox activate -d flox/local`:

```bash
cd random-xkcd-plugin
./gradlew assembleDebug          # produces build/outputs/apk/debug/random-xkcd-plugin-debug.apk
./gradlew testDebugUnitTest      # runs the TapCountClassifier tests
```

Build output is converted to a `.cgp` plugin file by the
`com.itsaky.androidide.plugins.build` Gradle plugin.

## Permissions

Declared in `AndroidManifest.xml` as `network.access,filesystem.read`:

- `network.access` — `xkcd.com` JSON + image fetch.
- `filesystem.read` — load the cached comic on cold start.

## Notable design choices

- **OkHttp + `org.json`**, not Retrofit / Moshi. The whole network
  surface is < 80 lines and reads top to bottom.
- **No `GestureDetector`.** Android's GestureDetector handles up to
  double-tap; this plugin needs triple-tap. A 25-line
  `TapCountClassifier` state machine is more teachable and
  testable in plain JUnit.
- **No own `FileProvider`.** Plugin manifests are not parsed by the
  OS as installable apps — `<provider>` in a plugin manifest is dead
  code. Triple-tap routes through the host IDE's
  `${packageName}.providers.fileprovider` authority via
  `FileProvider.getUriForFile`.
- **5 MB image cap.** Bounded reads on the network stream + disk
  cache, per the project's review-checklist theme #1.

See `src/main/assets/docs/index.html` for the full code walkthrough.
