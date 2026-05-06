# ADFA-2436 implementation status (overnight run, 2026-05-06 / 07)

## Plan vs. reality

Plan target tonight: C1 + C2 + C3, C4+ if time permits.

## Current state

(updated as work progresses)

- [x] C1 — Plugin scaffolding + stub templates **(modified — see Q1 in QUESTIONS.md)**
- [x] C2 — Wizard UI: 3-step flow with Gaia-style bbox picker, live tile/MB estimate, stub downloader writes cache layout **(not yet wired to recipe — see Q1)**
- [x] C3 — "Map Regions" bottom-sheet plugin tab: delete with confirmation dialog, re-download via wizard re-launch, refresh-on-resume, path-traversal-safe delete
- [ ] C4 — Read-only template (MapLibre core) (BLOCKED on Q1)
- [ ] C5 — Read-only template POI loader (BLOCKED on Q1)
- [ ] C6 — Annotate template (BLOCKED on Q1)
- [ ] C7 — Annotate submitter (BLOCKED on Q1)
- [ ] C8 — Three-tier docs (partial — Tier 1 tooltip strings shipped in C1)

## What landed in C1

- New module `gis-plugin/` with `plugin.json` declaring permissions
  `filesystem.read,filesystem.write,network.access` and main_class
  `com.codeonthego.gisplugin.GisPlugin`.
- `GisPlugin` (`IPlugin` + `UIExtension` + `DocumentationExtension`):
    - registers two stub `.cgt` templates via `IdeTemplateService`
    - contributes a "Map Regions" bottom-sheet tab via `getEditorTabs()`
    - ships Tier 1 tooltip strings for both templates and the regions tab
- `WizardActivity` + `WizardLauncher` ready for the recipe-blocking pattern
  once the `IdeTemplateService` API is extended (see QUESTIONS.md Q1).
- Stub CGTs scaffold a minimal Material 3 / AppCompat single-Activity
  Android project (no MapLibre yet — that arrives in C4).
- `RegionCache` reads `/sdcard/CodeOnTheGo/maps/*/meta.json` and exposes
  it to `RegionManagerFragment` (empty cache → empty state).

## Working notes

See `QUESTIONS.md` for unresolved blockers / decisions. **Q1 is the
blocker that gates C2 / C4–C7.**

## Build verification log

| When | Cmd | Outcome |
|---|---|---|
| 2026-05-07 01:10 PT | `./gradlew assembleDebug` (gis-plugin) | BUILD SUCCESSFUL in 7s; `gis-plugin-debug.apk` (5.6 MB) produced |
| 2026-05-07 01:11 PT | `./gradlew assembleRelease` (gis-plugin) | BUILD SUCCESSFUL in 16s |
| 2026-05-07 01:12 PT | `./gradlew assemblePluginDebug` | BUILD SUCCESSFUL; `gis-plugin-debug.cgp` written to `build/plugin/` |
| 2026-05-07 02:25 PT | `./gradlew assembleDebug` (post-C2) | BUILD SUCCESSFUL in 4s |
| 2026-05-07 02:50 PT | `./gradlew assembleDebug` (post-C3) | BUILD SUCCESSFUL in 2s |

## What landed in C3

- `RegionAdapter` gains a `Listener` interface; per-row Delete + Re-download
  buttons wire to the fragment.
- `RegionCache.delete(regionId)` — path-traversal-safe recursive delete
  (refuses to remove anything outside the cache root).
- `RegionManagerFragment`:
    - confirms delete via AlertDialog, toasts result, refreshes
    - re-download re-launches the wizard from `WizardLauncher` and refreshes
      on return
    - `onResume`-based refresh catches external mutations (the wizard
      finishing while the user is on another tab).

## What landed in C2

- `Bbox` + `TileEstimator` — slippy-map-tilenames math, haversine widthKm/
  heightKm, default-square-around-point helper.
- `BboxOverlayView` — direct-manipulation custom View. 48 dp corner-handle
  hit zones, 20 dp visible dots, drag-interior-to-translate, drag-corner-to-
  resize, min 48 dp side, parent-touch-interception while dragging.
- 3-step wizard layout: pick region (cached / download), Gaia-style bbox
  picker with live tile/MB readout, download progress.
- `RegionDownloader` — stub (no HTTP yet) that writes the canonical cache
  layout `{tiles.mbtiles, pois.json, meta.json}` under
  `/sdcard/CodeOnTheGo/maps/<region-id>/`. Synthetic 50 ms-per-step progress
  callback so the UI animates and a real downloader can drop in unchanged.
- `CachedRegionPickerAdapter` — single-select picker that reuses the C1 row
  layout but hides per-row buttons. Driven by `RegionCache.list()`.
- Wizard state machine + cancellation contract: any exit without a successful
  step-3 finish calls `WizardLauncher.complete(null)`. Matters because the
  recipe coroutine (when wired) blocks on the same deferred.
