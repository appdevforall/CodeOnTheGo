# ADFA-2436 — Questions and Blockers (for Bryan, 2026-05-07)

Append-only. Most-recent-first.

---

## Q5 (resolved by P1-fix commit) — Plugin Activities silently no-op against `DexClassLoader`-loaded plugin APK

**Resolution.** The 2026-05-07 mid-day commit (`12ca5aa9`) replaces both
`RegionManagerActivity` and `BboxPickerActivity` with host-resolved
Fragments. Plugin APKs are loaded with `DexClassLoader` (in
`plugin-manager/.../PluginLoader.kt`) and never registered with the
host's `PackageManager`, so `Intent(host, RegionManagerActivity::class)
.startActivity(...)` resolves to null and throws
`ActivityNotFoundException`. The previous `runCatching { startActivity()
}.onFailure { logger.warn(...) }` block swallowed the exception silently
— the sidebar tap on "Map Regions" was a no-op.

The new shape:
  - `RegionManagerFragment` is contributed via
    `EditorTabExtension.getMainEditorTabs()` as `gis_regions_main_tab`;
    the sidebar action calls `IdeEditorTabService.selectPluginTab(...)`.
  - `BboxPickerFragment` is swapped into the same tab via
    `childFragmentManager.beginTransaction().replace(...)`. Listener
    interface (`onBboxPickerSaved` / `onBboxPickerCancelled`) lets the
    regions fragment swap itself back in.
  - All `<activity>` declarations dropped from the manifest.
  - Both activity files + their layouts deleted.

This matches the pattern apk-viewer-plugin / markdown-preview-plugin /
keystore-generator-plugin / forms-plugin all use.

**Validation.** `gis-plugin assembleDebug` + `assemblePluginDebug` +
`testDebugUnitTest` all green. Runtime install + sidebar-tap
verification on a CodeOnTheGo IDE running the published plugin is still
TODO and requires Bryan or a teammate with a device. The new structure
is consistent with sibling plugins that are known to work, so
high-confidence-but-unverified.

---

## Q1 (resolved by sidebar branch) — BLOCKER: "Recipe-blocks-on-WizardActivity" pattern is not supported by the current `IdeTemplateService` API

**Resolution.** The `feature/ADFA-2436-maps-plugin-sidebar` branch
abandons the recipe-blocking handshake entirely in favour of a two-phase
architecture:

1. Two **static** `.cgt` templates (read-only + annotate) — each scaffolds
   a generic Map app with stub `tiles.mbtiles` + empty `pois.json`. Pebble
   recipe only; no plugin-Kotlin injection point needed. Apps build + run
   out of the box.
2. After project opens, plugin contributes a **"Map Regions" sidebar
   entry** via `UIExtension.getSideMenuItems()`. It launches
   `RegionManagerActivity` where the user manages cached regions and
   applies one to the open project via "Use in this project" (copies
   `tiles.mbtiles` + `pois.json` into `<projectDir>/app/src/main/assets/maps/`).

This trades the recipe-extension blocker for a model that ships within
the existing API. No `templates-impl` changes required. Original Q1
analysis kept below for reference.

### Original analysis (pre-resolution)

**Context.** Plan §5.2 (launch pattern row) and §5e of the Forms ADFA-2435 plan both say: "Template recipe blocks on a `WizardActivity`. Recipe runs on a background thread (per `TemplateDetailsFragment` line 109), launches `WizardActivity` via `context.startActivity(intent.addFlags(FLAG_ACTIVITY_NEW_TASK))`, blocks on a `CompletableDeferred` until the wizard returns a `WizardResult`."

That model assumes the plugin owns the `TemplateRecipe<ProjectTemplateRecipeResult>` Kotlin function and gets it called by the IDE on a background thread. **It doesn't, at present.**

**What I found in the code.**
- `IdeTemplateService.registerTemplate(cgtFile: File): Boolean` is the only plugin entry point (`plugin-api/src/main/kotlin/com/itsaky/androidide/plugins/services/IdeTemplateService.kt`). It copies the CGT into `Environment.TEMPLATES_DIR` and tells the existing `TemplateProviderImpl` (in `templates-impl`) to reload.
- `TemplateProviderImpl.initializeTemplates` (`templates-impl/.../TemplateProviderImpl.kt:54-74`) uses `ZipTemplateReader` which **always** wires the project template's `recipe` to `ZipRecipeExecutor` (`templates-impl/.../zip/ZipTemplateReader.kt:116-119`). The recipe field of every CGT-loaded `ProjectTemplate` is a wrapper around the fixed `ZipRecipeExecutor`.
- `ZipRecipeExecutor.execute` (`templates-impl/.../zip/ZipRecipeExecutor.kt:52-214`) does only:
  1. Pebble-render every `*.peb` file in the zip with the resolved parameter map
  2. Copy non-templated files verbatim
  3. Run `keystore()` to copy debug keystore artifacts
  
  There is **no callout to plugin code** at any point. The recipe never runs Kotlin code the plugin authored.
- The CGT format does support a Pebble *extension* JAR (`META-INF/extension.jar`) loaded via `DexClassLoader` (`ZipRecipeExecutor.kt:84-90, 416-477`). But Pebble extensions are filters / functions / parsers / token-parsers — they're invoked **during template evaluation**, inside `template.evaluate(writer, identifiers)`. Plugins can ship arbitrary Java/Kotlin code in this jar, but the contract is "given a value, return a value to splice into a Pebble template," not "launch an Activity and block."

**Why this matters for ADFA-2436.** The wizard (region picker → bbox draw → tile download → POI fetch) needs to run **before** files are written to disk, so the recipe knows which region to bundle. Without a recipe-level extension point, there's no single hand-off between "user clicked Create Project" and "files get written."

**Three potential paths forward, in order of cleanliness.**

1. **Cleanest — extend `templates-api` / `templates-impl` to support a plugin-owned recipe.** Add an extension point: `IdeTemplateService.registerTemplate(name, recipe: TemplateRecipe<ProjectTemplateRecipeResult>)` (overload), or have the CGT format declare an optional `meta.json` field `"recipeClass": "com.codeonthego.gisplugin.MapRecipe"` which `ZipTemplateReader` would load via DexClassLoader and use instead of `ZipRecipeExecutor`. **Touches templates-impl + plugin-api**, which the plan explicitly said to avoid (measurable outcome #6: "no changes to templates-api/ or templates-impl/ core").
2. **Pebble-function-as-wizard-trigger (cute, fragile).** Ship a Pebble extension JAR with a custom function `launchMapWizard()`. Wire a templated file (e.g. `region-id.txt`) that does `\${% set region = launchMapWizard() %}\${{ region.id }}`. The function runs synchronously during template evaluation, can launch a `WizardActivity` from the application context with `FLAG_ACTIVITY_NEW_TASK`, and block on a `CompletableDeferred`. Subsequent Pebble templates can use the result. **Risks:** (a) Pebble's threading model is single-threaded per evaluation, but blocking inside a Pebble function is unusual and may fight against `strictVariables=true`; (b) the Pebble-rendering loop walks zip entries one-by-one — the wizard would need to fire on the first templated file processed and stash its result for later files, but ordering of `zip.entries()` is not guaranteed; (c) the wizard's tile-download step is hundreds of MB and minutes long, blocking template evaluation for that long is brittle (process kill, etc.).
3. **Sidebar-item-as-launcher (out of plan §5).** Don't ship templates at all. Instead, a sidebar item launches the wizard, the wizard runs to completion, and at the end the plugin scaffolds a project programmatically (writing files directly via `IdeFileService`, opening it via `IdeProjectService`). **Breaks the plan's UX promise** ("two new templates in the project-creation wizard") but works within the existing API.

**Suggested answer.** Path 1 is right. The plan flagged this in open question O14 ("Wizard cancellation semantics. Plan §5.2 launch-pattern row says the recipe blocks on a `CompletableDeferred` ... Confirm in C1") and the answer is "no, the current API doesn't support it." Need a small `templates-impl` change that lets a plugin contribute a Kotlin recipe instead of (or alongside) the Pebble-rendered one. Concretely:

> Add a `recipe.class` entry to `template.json`. When present, `ZipTemplateReader` loads the named class via DexClassLoader (using `META-INF/extension.jar`), instantiates it as `TemplateRecipe<ProjectTemplateRecipeResult>`, and uses it in place of (or wrapping) the default `ZipRecipeExecutor`. The plugin's recipe gets the same `RecipeExecutor` (so it can `executor.copy(...)`), can launch a wizard, await results, then either delegate the file-emission to the standard ZipRecipeExecutor with extra Pebble identifiers or write files itself.

**Blocker?** Yes, for the plan as written. **What I did tonight:** C1 with a no-wizard stub CGT, then C2 (full wizard UI), C3 (Map Regions tab with delete + re-download), C4 (MapLibre-backed templates). The wizard, Map Regions tab, and MapLibre templates all build green. Once Q1 is unblocked, the recipe → wizard → cache → CGT chain is one wire-up away.

---

## Q2 — MapLibre version pin

The C4 templates pin `org.maplibre.gl:android-sdk:11.11.0` (current stable as of 2026-05). Plan open question O1 asks "What MapLibre Android SDK version did the historical `com.example.maplibreplugin` use?" — the plugin survey (`docs/notes/plugin-survey-answers.md`) confirms no MapLibre code currently lives in tree, so there's no version to match.

**Suggested answer:** Pin 11.11.0 for now. Hal's preferred version (per O1) overrides if different. The pin lives in one place: `MapTemplateBuilder.MAPLIBRE_VERSION` — easy to bump.

**Blocker?** No, but **MapLibre's runtime behaviour in the generated app is not yet validated**. The plugin compiles green, which only proves the CGT contents are syntactically valid Kotlin/Java/Pebble. The generated APK's actual compile against MapLibre 11.11.0 is what could surface the historical `InflateException` (plan §7 R1). Validation requires:
  1. running the IDE with the plugin installed
  2. scaffolding a project from one of the templates
  3. running `./gradlew assembleDebug` in the generated project
  4. installing on a real low-end device (Galaxy A35/A36 per plan §8)

Without the recipe extension point (Q1), step 2 isn't possible from the IDE today. Workaround: extract the generated project from a built .cgt manually, run gradle, capture any failures. **Not done tonight** — left for a follow-up morning session.

---

## Q3 — Generated app's tile pack source

The C4 read-only template's `assets/style.json` currently points at the public OpenMapTiles demo tile server (`https://demotiles.maplibre.org/tiles/{z}/{x}/{y}.png`). That's:
  - an internet-required first run (fails the plan's offline-first promise);
  - a raster source rather than the vector tiles we actually want for the offline pack.

**Plan §5.2 says:** the generated app should read from `mbtiles://...` pointing at `assets/maps/region.mbtiles`, bundled from the wizard's downloaded cache at scaffold time.

**Why I left it on demotiles tonight:**
  1. with Q1 blocking the recipe extension, there's no way for the plugin to copy a cached region's tiles into the CGT at scaffold time — the scaffold runs before the wizard;
  2. MapLibre's `mbtiles://` source loader requires the renderer to know how to interpret the bundled bytes; that's a separate plumbing piece (a `MBTilesSource` Java implementation or a third-party loader);
  3. the demo tile fallback at least lets a human visually confirm the MapView inflated correctly.

**Suggested answer:** Once Q1 is unblocked, the recipe (a) reads the user-selected region's `tiles.mbtiles` from the cache, (b) writes it into the CGT's `assets/maps/region.mbtiles` slot via `addStaticFile`, (c) generates `style.json` with `"sources": {"region": {"type":"vector", "url": "mbtiles://maps/region.mbtiles"}}` and a vector-tile layer stack. The `MBTilesSource` plumbing is a follow-up.

**Blocker?** No, gates final UX of the read-only template only.

---

## Q4 — `core.excludesfile` configured to a sibling worktree's `.gitignore.local`

Independent of ADFA-2436 itself: the shared CodeOnTheGo `.git/config` has `core.excludesfile = /Volumes/Data/Users/bryanchan/dev/appdevforall/worktrees/adfa-2433-xkcd/.gitignore.local`. That file lists `STATUS.md` and `QUESTIONS.md`, which means `git status` ignores them in this worktree too.

**Suggested answer:** Bryan's call. Worth either (a) moving the `.gitignore.local` into a shared location and adding STATUS / QUESTIONS handling per-worktree, or (b) removing the global `core.excludesfile` and keeping per-worktree `.git/info/exclude` instead. **Not touched tonight** — git config edits are user-only per `.claude/rules/security-posture.md`.

**Blocker?** No. Worked around with `git add -f STATUS.md QUESTIONS.md`.

---
