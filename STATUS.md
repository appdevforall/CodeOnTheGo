# ADFA-2436 implementation status (overnight run, 2026-05-06 / 07)

## Plan vs. reality

Plan target tonight: C1 + C2 + C3, C4+ if time permits.

## Current state

(updated as work progresses)

- [x] C1 — Plugin scaffolding + stub templates **(modified — see Q1 in QUESTIONS.md)**
- [x] C2 — Wizard UI: 3-step flow with Gaia-style bbox picker, live tile/MB estimate, stub downloader writes cache layout **(not yet wired to recipe — see Q1)**
- [x] C3 — "Map Regions" bottom-sheet plugin tab: delete with confirmation dialog, re-download via wizard re-launch, refresh-on-resume, path-traversal-safe delete
- [x] C4 — MapLibre-backed read-only + annotate templates **(plugin compiles green; generated-APK runtime not yet validated — see Q2)**
- [ ] C5 — Read-only template POI loader (would build on C4 templates)
- [ ] C6 — Annotate template (UX + Room + CameraX)
- [ ] C7 — Annotate submitter (BLOCKED on Q1 because the wizard's submitter-config screen needs a way back to the recipe)
- [x] C8 — Three-tier docs **(Tier 1 tooltip strings, Tier 2 generated README per template, Tier 3 in-IDE markdown OSM tutorial all shipped)**

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
| 2026-05-07 03:10 PT | `./gradlew assembleDebug` (post-C4) | BUILD SUCCESSFUL in 1s. Plugin module only — generated APK MapLibre runtime not yet validated. |
| 2026-05-07 03:30 PT | `./gradlew assembleDebug` (post-C8) | BUILD SUCCESSFUL in 2s |

## What landed in C8

- Tier 1 — tooltip strings on each template card (in `GisPlugin.getTooltipEntries`).
  Now include a "OSM + MapLibre tutorial" button that deep-links into Tier 3.
- Tier 2 — `README.md` emitted into every generated project. Audience-specific
  ("annotate" template's README has a submitter section, "read-only" doesn't).
  Pebble-templated so the user's `{{APP_NAME}}` and `{{PACKAGE_NAME}}` show up
  in code blocks and headings.
- Tier 3 — `gis-plugin/src/main/assets/docs/osm-tutorial.md`. ~6 pages
  covering OSM tag model, MapLibre style basics, where tiles come from, how
  to swap `pois.json`, Overpass API for adding new POI categories.
  `GisPlugin.getTier3DocsAssetPath()` now returns `"docs"` so the IDE walks
  the tree at install time and inserts each file under
  `plugin/com.codeonthego.gisplugin/...` in the docs DB.

## What landed in C4

- New `MapTemplateBuilder` replaces the C1 stub. Both registered templates
  now scaffold an Android project that compiles MapLibre Native 11.11.0,
  Material 3, AppCompat, Play Services Location.
- Generated `MainActivity.kt` and `MainActivity.java` initialise MapLibre,
  forward all 8 lifecycle events into the `MapView` (per upstream docs —
  skipping any leaks GPU memory), and centre the camera on the user's
  GPS fix via `FusedLocationProviderClient.getCurrentLocation`.
- Generated `activity_main.xml` uses `org.maplibre.android.maps.MapView`
  (the post-rebrand class — older docs still reference the
  `com.mapbox.mapboxsdk.maps.MapView` Mapbox class).
- Generated `assets/style.json` is a tiny raster style hitting MapLibre's
  demo tile server. Sufficient for "map renders" smoke testing; the real
  offline-mbtiles-bundle wire-up is logged in `QUESTIONS.md` Q3.
- Annotate template manifest declares `CAMERA` permission and the
  `android.hardware.camera` feature so first-launch UX surfaces it before
  C6's photo-capture flow lands.
- Old `StubTemplateBuilder` deleted now that real templates ship.

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
