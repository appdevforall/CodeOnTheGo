# ADFA-2436 implementation status (sidebar branch, 2026-05-07 mid-day)

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
   sidebar entry via `UIExtension.getSideMenuItems()`. Tapping it surfaces
   a host-resolved editor tab (`EditorTabExtension.getMainEditorTabs()`)
   hosting `RegionManagerFragment`. The "+ Download new region" CTA swaps
   `BboxPickerFragment` into the same tab via `childFragmentManager`.

Bottom-sheet plugin tab is **gone**. Plugin Activities are also gone (see
mid-day fix below) — all UI lives in host-resolved Fragments.

## What landed this branch (cumulative)

### Removed
- Recipe-blocking-wizard plumbing (the prior branch's morning work):
  `WizardActivity`, `WizardLauncher`, `WizardResult`,
  `CachedRegionPickerAdapter`, 4 wizard layouts.
- **Mid-day P1 fix:** `RegionManagerActivity`, `BboxPickerActivity`,
  `activity_region_manager.xml`, `activity_bbox_picker.xml`, and the
  two `<activity>` entries in `AndroidManifest.xml`. Plugin Activities
  silently no-op against a `DexClassLoader`-loaded plugin APK because
  the host's `PackageManager` never sees them. Sibling plugins use
  host-resolved fragments via `IdeEditorTabService.selectPluginTab`;
  Maps now matches.

### Added
- `RegionManagerFragment` (host-resolved): the regions panel as a
  `MainEditorTab`. Wraps inflater via `PluginFragmentHelper.getPluginInflater`.
  Internal navigation between list view and bbox picker via
  `childFragmentManager.replace(picker_container, ...)`.
- `BboxPickerFragment` (new): replaces `BboxPickerActivity`. Hosted under
  the same tab as the regions fragment. Listener interface lets the
  regions fragment swap itself back in on save / cancel. Supports prefill
  args (regionId, displayName, bbox) for refresh flows.
- `fragment_bbox_picker.xml`: layout for the new fragment (mirror of the
  old activity layout, no Activity-level chrome).
- "Use in this project" pipeline with **atomic-rename + free-space
  precheck**: `tiles.mbtiles` and `pois.json` are written to `.tmp`
  then `Files.move(... ATOMIC_MOVE)`-d, marker file last. Errors are
  logged via `pluginContext.logger.error` so failures surface in
  Logcat (REVIEW2.md M5 fix).
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
- **Unit-test scaffold** (`gis-plugin/src/test/`): 42 tests, all passing.
  - `RegionCacheTest` — regionId allowlist (path-traversal vectors:
    `..`, `.`, `foo/bar`, `foo\bar`, `/etc/passwd`, leading dots,
    Unicode), delete idempotence, symlink-escape refusal,
    malformed/missing meta.json fallback, list-sort, bbox parse/drop.
  - `BboxTest` — construction guards, boundary-inclusive `contains()`,
    width/height/area math at equator and 45°, aroundPoint() longitude
    scaling at high lat, lat clamp at poles, haversine sanity.
  - `TileEstimatorTest` — known-good slippy counts (z=0/1/2 worldwide,
    z=0..2 sum, mid-lat box at z=14), zoom range guards, ~4× growth
    per zoom level, MB conversion.

### Modified
- `GisPlugin.kt`:
  - Implements `EditorTabExtension`; registers `gis_regions_main_tab`
    via `getMainEditorTabs()` with `RegionManagerFragment`.
  - Sidebar action calls `IdeEditorTabService.selectPluginTab(...)`
    (no Activity launch).
  - `dispose()` clears the static `pluginContext` so a plugin reload
    doesn't leave Fragments resolving services from a defunct context
    (REVIEW2.md I5 fix).
  - `PLUGIN_ID` and `REGIONS_TAB_ID` constants exposed.
- `RegionDownloader.kt`:
  - Tightened `regionId` validation to a positive allowlist regex
    (`^[a-z0-9][a-z0-9-]*$`) via `RegionCache.isValidRegionId`. Rejects
    `..`, `.`, leading dots, slashes, etc. Throws
    `IllegalArgumentException` with a clear message (REVIEW2.md MB1 /
    theme #3 fix).
  - Atomic-rename pattern for tiles + pois + meta. Marker file
    written last so its presence implies tiles + pois are valid
    (REVIEW2.md I3 / theme #4 fix).
  - Dropped the `if (!exists())` skip-on-existing guards — refresh
    rewrites the payload (REVIEW2.md I4 fix).
- `RegionCache.kt`:
  - `isValidRegionId(...)` is now the authoritative validator.
  - `delete()` rejects invalid ids first, then canonicalises and
    asserts containment (defense in depth).
  - `RegionInfo` now carries `bbox: DoubleArray?` parsed from
    `meta.json` so refresh can pre-fill the picker.
  - Added `listFromRoot(root)` / `deleteFromRoot(root, id)` /
    `readDir(dir)` JVM-testable overloads.
- `RegionManagerFragment.kt`:
  - Free-space precheck before applying a region (REVIEW2.md MB1 /
    theme #1 fix).
  - Atomic-rename for tiles + pois + marker; marker last.
  - Bounded marker read (1024 bytes max) per CodeRabbit theme #1.
  - Refresh now passes `regionId` + `displayName` + `bbox` to
    `BboxPickerFragment` so it pre-fills the same region (REVIEW2.md I4
    fix). Refresh rewrites payload because `RegionDownloader` no longer
    skips existing files.
  - `applyRegionToProject` defends `srcDir` against the cache root in
    addition to defending `targetDir` against the project root (defense
    in depth).
  - Logs failures via `pluginContext.logger.error` so swallowed
    exceptions are debuggable (REVIEW2.md M5 fix).
  - Implements `BboxPickerFragment.Listener` so the picker hands back
    cleanly via fragment-manager swaps.
- `Bbox.kt`:
  - Added `Bbox.contains(lat, lon)` (boundary-inclusive) + `areaKm2()`.
  - **Real-bug fix in `TileEstimator.estimate`**: the destructuring
    `val (xMin, yMax) = lonLatToTile(west, north, z)` had y-axis
    naming reversed (slippy maps NORTH to LOW y). The bug made
    `(yMax - yMin + 1)` always negative; `coerceAtLeast(0)` then
    silently produced **zero tiles for every estimate** — the bbox
    picker's "X tiles · Y MB" readout was always wrong. Caught by
    new unit tests; fixed in same commit.
- `fragment_region_manager.xml`: now hosts both list_container and a
  picker_container slot for swap-in BboxPickerFragment.
- `AndroidManifest.xml`: dropped both `<activity>` entries; comment
  documents why.
- `build.gradle.kts`: adds `testImplementation` deps (junit + json).

## Survives unchanged from earlier C-passes

- `BboxOverlayView` math + drag handling (only its hosting changed).
- `Bbox` core (south/west/north/east, haversine, aroundPoint).
- Tier 1 / 2 / 3 documentation.
- MapLibre 11.11.0 pin.
- Generated MainActivity Kotlin + Java sibling lifecycle wiring.

## Build verification log

| When | Cmd | Outcome |
|---|---|---|
| 2026-05-07 02:11 PT | `./gradlew :gis-plugin:assembleDebug` (post-rewire) | BUILD SUCCESSFUL in 3s |
| 2026-05-07 02:12 PT | `./gradlew :gis-plugin:assemblePluginDebug` | BUILD SUCCESSFUL; `gis-plugin-debug.cgp` (5.4 MB) emitted |
| 2026-05-07 02:13 PT | `./gradlew clean :gis-plugin:assemblePluginDebug` | BUILD SUCCESSFUL in 1s (clean rebuild) |
| 2026-05-07 mid-day | `assembleDebug` post host-resolved-fragment migration | BUILD SUCCESSFUL in 4s |
| 2026-05-07 mid-day | `testDebugUnitTest` (first run) | 42 tests; 5 failed in TileEstimator → caught the slippy y-axis bug |
| 2026-05-07 mid-day | `testDebugUnitTest` (post-fix) | **42 tests, 0 failed, 0 ignored** — all green |
| 2026-05-07 mid-day | `assembleDebug testDebugUnitTest` together | BUILD SUCCESSFUL |
| 2026-05-07 mid-day | `assemblePluginDebug` | BUILD SUCCESSFUL; `.cgp` emitted |

## Open questions / risks

See `QUESTIONS.md`:
  - Q1 (recipe-blocking) **resolved** by branch architecture pivot.
  - Q5 (plugin-Activity launch) **resolved** by mid-day fix; runtime
    install + tap on a real CodeOnTheGo IDE still TODO (host-side
    verification, not catchable by gradle alone).
  - Q2 (MapLibre 11.11.0 on-device validation): unchanged, requires a
    real device.
  - Q3 (offline tile pack source): unchanged.
  - Q4 (gitignore mis-config): unchanged.

## What's still open after this commit

- **Runtime verification on a real device.** The fragment-host migration
  is consistent with sibling plugins that are known to work, but no one
  has yet run the plugin against a published CodeOnTheGo IDE and
  confirmed the sidebar tap surfaces the tab.
- **Stub mbtiles foot-gun** (REVIEW.md B1 / REVIEW2.md M9). Not addressed
  here. The 4-byte stub is fine for the default scaffold (which uses
  `https://demotiles.maplibre.org` raster, not `mbtiles://`), but
  following the README's "switch to `mbtiles://maps/region.mbtiles`"
  step without first downloading a region will crash at runtime. Two
  fix shapes: bundle a real ~1 MB world overview, or reorder the README
  guidance. Defer.
- **Synthetic tile estimate at fixed lat/lon** (REVIEW2.md M1). The
  picker estimate hardcodes (37.0, -122.0) until the real MapLibre
  projection lands. Known stub; flagged in REVIEW2.md.
- **Synthetic 1-second download delay** (REVIEW2.md M2). Stays for
  parity with the plan's "progress UI" demo path; should be gated on
  `BuildConfig.DEBUG` once real HTTP fetches land.

Net: the diff is now mergeable per REVIEW2.md's stated criteria
(C1/I1/I2/I3/I4 + path-traversal test) modulo the runtime-on-device
verification.
