# ADFA-2436 implementation status (sidebar branch, 2026-05-07)

## Branch context

This is `feature/ADFA-2436-maps-plugin-sidebar`, branched from
`feature/ADFA-2436-maps-plugin` after the recipe-blocking-wizard model
was abandoned (Q1 finding). New architecture is **two-phase**:

1. Plugin registers two **static** `.cgt` templates (read-only + annotate).
   Each scaffolds a generic Map app with stub `tiles.mbtiles` + empty
   `pois.json` so the app builds and runs out of the box. Recipe is plain
   Pebble — no Kotlin-recipe injection, no wizard inside the template
   flow.
2. After project opens, plugin contributes a single **"Map Regions"**
   sidebar entry via `UIExtension.getSideMenuItems()`. Tapping it opens
   `RegionManagerActivity` (full-screen), where the user manages cached
   regions with per-row "Use in this project" / "Refresh" / "Delete" and
   a bottom "+ Download new region" CTA that fires the bbox picker.

Bottom-sheet plugin tab is **gone**. Region management is project-resource
navigation, not process output, so the sidebar slot is the right surface.

## What landed in this branch

### Removed (recipe-blocking wizard plumbing)
- `WizardActivity.kt` (3-step wizard)
- `WizardLauncher.kt` (CompletableDeferred handshake)
- `WizardResult.kt`
- `CachedRegionPickerAdapter.kt`
- 4 wizard layouts (`activity_wizard.xml`, `wizard_step1/2/3`)
- `getEditorTabs()` override on `GisPlugin` (bottom-sheet tab gone)
- Sample Lalibela POI dataset bundled in the read-only template (the
  scaffold now ships an empty `pois.json` — region content arrives via
  the sidebar's "Use in this project")

### Added
- `RegionManagerActivity` — full-screen Activity hosting the regions
  fragment. Material 3 chrome with back-arrow.
- `BboxPickerActivity` — repurposes `BboxOverlayView` as a standalone
  picker. Top app bar, region-name field, live tile/MB estimate, Save +
  Cancel. On Save runs `RegionDownloader` and finishes.
- "Use in this project" affordance on every region card. Copies
  `tiles.mbtiles` + `pois.json` into `<projectDir>/app/src/main/assets/maps/`
  + writes a `region-id.txt` marker so the badge persists.
- "✓ In this project" badge per row when the region is currently bundled.
- "+ Download new region" primary CTA at the bottom of the regions panel.
- `RegionRow` view-model wrapping `RegionInfo` with `isInProject` +
  `isDownloading` flags.
- Empty-state banner in the generated app's `MainActivity` ("No region
  configured — open Map Regions in your IDE sidebar"). Hides itself once
  `pois.json` has any entries.
- `cardview` dependency in the generated app's `build.gradle.kts` (used
  by the empty-state banner).
- Stub `tiles.mbtiles` + empty `pois.json` bundled in every template
  scaffold so the generated app's MapView never inflates against missing
  assets.
- `androidx.cardview` dependency in generated app build.gradle.kts.

### Modified
- `GisPlugin.kt` — drops `getEditorTabs()` + the prior "manual launch
  sidebar" workaround entry. Single `getSideMenuItems()` returns "Map
  Regions" launcher. Exposes `pluginContext` via companion object so the
  hosted Activity can resolve services.
- `RegionAdapter.kt` — listener interface gains `onRegionUseInProject`;
  consumes `RegionRow` instead of bare `RegionInfo`. Inline progress
  indicator + badge wiring.
- `RegionManagerFragment.kt` — promoted from bottom-sheet to full-screen.
  Adds project-aware refresh, "Use in this project" copy + marker write,
  and CTA → BboxPickerActivity launch.
- `item_region.xml` — adds badge, progress indicator, and primary "Use
  in this project" button.
- `fragment_region_manager.xml` — adds bottom "+ Download new region"
  primary button.
- `AndroidManifest.xml` — declares the two new Activities, removes
  `WizardActivity`. Sidebar items count stays at 1.
- `MapTemplateBuilder.kt` — generic-Map scaffolds; reads from
  `assets/maps/pois.json` (not `assets/pois.json`); kind-aware
  `activityMainLayout` so the annotate template doesn't inflate the POI
  drawer.
- `strings.xml` — new strings for Use-in-project / In-this-project /
  Download-new / apply success/failure / no-project; dropped wizard
  strings.

## What survives unchanged from the previous branch's 8 commits

- `BboxOverlayView` math + drag handling
- `RegionCache` + path-traversal-safe delete
- `Bbox` + `TileEstimator` (slippy-map-tilenames math, haversine width/
  height calculations)
- `RegionDownloader` stub (synthetic 50ms-per-step progress so the picker
  has something to wait on)
- Tier 1 / 2 / 3 documentation (template tooltips, generated README,
  OSM tutorial markdown)
- MapLibre 11.11.0 pin
- Generated MainActivity Kotlin + Java sibling lifecycle wiring

## Build verification log

| When | Cmd | Outcome |
|---|---|---|
| 2026-05-07 02:11 PT | `./gradlew :gis-plugin:assembleDebug` (post-rewire) | BUILD SUCCESSFUL in 3s |
| 2026-05-07 02:12 PT | `./gradlew :gis-plugin:assemblePluginDebug` | BUILD SUCCESSFUL; `gis-plugin-debug.cgp` (5.4 MB) emitted |
| 2026-05-07 02:13 PT | `./gradlew clean :gis-plugin:assemblePluginDebug` | BUILD SUCCESSFUL in 1s (clean rebuild) |

## Open questions / risks (still standing)

See `QUESTIONS.md` — Q1 (recipe-blocking) is **resolved by this branch's
architectural pivot** (no longer needed). Q2 (MapLibre 11.11.0 on-device
validation), Q3 (offline tile pack source), Q4 (gitignore mis-config) are
unchanged.

Net: this branch trades the recipe-extension blocker for a simpler model
that ships within the existing API surface. The trade-off is that
projects scaffolded from a Map template start with empty pois.json + a
4-byte stub mbtiles; users complete the loop by downloading a region in
the sidebar and tapping "Use in this project".
