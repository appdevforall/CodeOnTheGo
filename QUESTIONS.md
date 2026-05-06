# ADFA-2436 — Questions and Blockers (for Bryan, 2026-05-07)

Append-only. Most-recent-first.

---

## Q1 — BLOCKER: "Recipe-blocks-on-WizardActivity" pattern is not supported by the current `IdeTemplateService` API

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

**Blocker?** Yes, for the plan as written. **What I'm doing tonight:** scaffold C1 with a *no-wizard stub* CGT (just two templates that scaffold an empty MainActivity, no wizard launch, no MapLibre). This still proves: (a) plugin module builds, (b) `.cgt` files register via `IdeTemplateService`, (c) templates appear in the grid, (d) tap-to-create works end-to-end. C2/C3 can land on top once the recipe extension point exists.

---
