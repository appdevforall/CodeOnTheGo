# Plugin API Changelog

A version-mapped history of the Code on the Go **plugin API** — which release week
first shipped each plugin capability. It exists so a plugin author can choose a
correct `plugin.min_ide_version`.

- **Audience:** plugin developers (and maintainers changing the API).
- **Companions:** [PLUGIN_AUTHORING.md](PLUGIN_AUTHORING.md) (how to write a plugin),
  [plugin-api.md](plugin-api.md) (what counts as the API + compatibility policy).

## How to use this

Set `plugin.min_ide_version` to the **highest** version below among the
capabilities your plugin actually uses. Only `min` is enforced at install today;
`max` is parsed but advisory.

```xml
<!-- src/main/AndroidManifest.xml -->
<meta-data android:name="plugin.min_ide_version" android:value="26.30" />
<meta-data android:name="plugin.max_ide_version" android:value="99.0.0" />
```

Versions are bare `YY.WW` — two-digit ISO year, two-digit ISO week (`26.30` =
2026, week 30). Not the R1 / R2 marketing names, and there is no separate integer
"API level" — the `YY.WW` string is the whole contract.

## Changelog

Newest first. Every change so far is **additive** — no capability has been
removed or had its signature broken since the plugin system shipped. A future
breaking change belongs here as a `breaking` row.

Legend: `added` = new capability, safe to adopt · `tooling` = API-stability
milestone. **[verified]** = read from the checked-in ABI dump. **[reconstructed]**
= diffed from `plugin-api/src` history (predates the dump; symbol-accurate).

### 26.31 — 2026-07-29
- **tooling — Plugin API & builder resolvable by Maven coordinate on-device** _(ADFA-4911)_
  The plugin API and the builder Gradle plugin are injected into the on-device
  local Maven repository at onboarding, so a plugin resolves them by coordinate,
  offline, without committing `libs/*.jar`:
  `compileOnly("com.itsaky.androidide:plugin-api:1.0.0")` and
  `plugins { id("com.itsaky.androidide.plugins.build") version "1.0.0" }`.
  `plugin-api:1.0.0` bundles `:plugin-api` + `common` + `eventbus-events` +
  `idetooltips`. (No-`libs/` project detection lands separately in ADFA-4913.)

### 26.30 — 2026-07-20
- **added — Project-search providers** _(ADFA-4723, `88c20f3`)_ **[verified]**
  Plugins contribute their own project-search sources that render as dedicated
  result sections.
  `ProjectSearchExtension.searchProject(ProjectSearchRequest): CompletableFuture`,
  `ProjectSearchRequest`, `ProjectSearchResult`, `ProjectSearchSection`.

### 26.29 — 2026-07-14
- **added — Cross-plugin services & lifecycle** _(ADFA-4584, `875853d`)_ **[verified]**
  Register services other plugins consume, observe plugin lifecycle, query
  whether a plugin is active or its version, read app preferences.
  `PluginContext.registerService` / `unregisterService` / `getPluginService` /
  `getProvidedServices` / `addPluginLifecycleListener` / `isPluginActive` /
  `getPluginVersion` / `getAppSharedPreferences`, `PluginLifecycleListener`,
  `SharedServices`.
- **added — Editor inline suggestions (ghost text)** _(ADFA-4584, `875853d`)_ **[verified]**
  Show/dismiss inline completions and observe editor content changes.
  `IdeEditorService.showInlineSuggestion` / `dismissInlineSuggestion` /
  `addContentChangeListener`, `EditorContentChangeListener`.
- **added — Dynamic toolbar icons** _(ADFA-4584, `875853d`)_ **[verified]**
  `UIExtension.getIconProvider` / `setIconProvider`, `IdeUIService.refreshToolbarActions()`.

### 26.28 — 2026-07-07
- **added — Per-module context & task execution** _(ADFA-4582, `cc2f592`)_ **[verified]**
  Resolve a context for a specific Gradle module and run build tasks against it.
  `IdeProjectService.getModuleContext(String): ModuleContext`,
  `IdeBuildService.executeTasks(vararg String): CompletableFuture`.
- **tooling — API-stability baseline** _(ADFA-3588, `440e7dd`)_ **[verified]**
  First release where the whole public surface is frozen into a checked-in ABI
  dump (`plugin-api/api/plugin-api.api`) guarded by the binary-compatibility
  validator. From here on every API change is a reviewable diff. Adds the
  `@InternalPluginApi` opt-out marker.

### 26.18 — 2026-04-23
- **added — Archive & environment services** _(ADFA-3787, `88d2f4a`)_ **[reconstructed]**
  Extract archives (xz/gzip/tar/zip), locate IDE-managed directories (SDK, NDK,
  home, tmp), write binary/streamed files. Adds the `ide.environment.write`
  permission.
  `IdeArchiveService`, `IdeEnvironmentService`, `ArchiveFormat`, `ExtractResult`,
  `PluginPermission.IDE_ENVIRONMENT_WRITE`,
  `ResourceManager.openPluginResource` / `openPluginAsset`,
  `IdeFileService.writeBinary` / `writeStream` / `delete`.

### 26.17 — 2026-04-21 / 2026-04-17
- **added — Day / night plugin icons** _(ADFA-3694, `e5383d2`)_ **[reconstructed]**
  Ship separate light/dark icons; the manager renders the one matching the theme.
  Manifest keys `plugin.icon_day` / `plugin.icon_night`;
  `PluginMetadata.iconDayPath` / `iconNightPath`.
- **added — Code snippets** _(ADFA-3546, `683b551`)_ **[reconstructed]**
  Contribute reusable snippets in TextMate syntax.
  `SnippetExtension`, `IdeSnippetService`, `SnippetContribution`.

### 26.16 — 2026-04-13
- **added — Build actions & custom commands** _(ADFA-3580, `98b9ba1`)_ **[reconstructed]**
  Contribute actions to the build toolbar; run shell commands or Gradle tasks
  with streamed output; includes the toolbar-action IDs a plugin may hide.
  `BuildActionExtension`, `IdeCommandService`, `CommandExecution`,
  `PluginBuildAction`, `BuildActionCategory`, `ToolbarActionIds`,
  `CommandSpec` (`ShellCommand` / `GradleTask`), `CommandResult`.

### 26.14 — 2026-03-29 / 2026-03-26
- **added — Project-template contribution** _(#1122, `93ae25c`)_ **[reconstructed]**
  Contribute new-project templates in the `.cgt` format.
  `IdeTemplateService`, `CgtTemplateBuilder`.
- **added — Feature-flag access** _(ADFA-2808, `d924652`)_ **[reconstructed]**
  `IdeFeatureFlagService.isExperimentsEnabled()`.

### 26.12 — 2026-03-12
- **added — File-open handling** _(ADFA-3162, `0681d66`)_ **[reconstructed]**
  Intercept file opens and contribute file-tab menu items — the basis for custom
  viewers like the APK viewer.
  `FileOpenExtension` (`canHandleFileOpen` / `handleFileOpen` / `getFileTabMenuItems`),
  `FileTabMenuItem`.

### 26.09 — 2026-02-17
- **added — Material 3 theming** _(ADFA-1718, `a004fc5`)_ **[reconstructed]**
  Read the active theme and react to theme changes.
  `IdeThemeService`, `ThemeChangeListener`.

### 26.02 — genesis (2025-09-24) + sidebar slots (2025-12-01)
- **added — Sidebar slots** _(ADFA-2139, `9fa0f17`)_ **[reconstructed]**
  Declare how many sidebar slots a plugin needs and query availability.
  `IdeSidebarService` (`getAvailableSidebarSlots` / `canAddSidebarItems` /
  `getMaxSidebarItems`), manifest key `plugin.sidebar_items` (Int).
- **added — Plugin system foundation (genesis)** _(#406, `6fdbe8e`)_ **[reconstructed]**
  The plugin system itself: lifecycle, the context + service registry handed to
  every plugin, the first extension points and core IDE services, and the
  manifest contract including `plugin.min_ide_version` / `plugin.max_ide_version`
  (present from day one).
  `IPlugin` (`initialize` / `activate` / `deactivate` / `dispose`),
  `PluginContext`, `ServiceRegistry`, `ResourceManager`, `PluginLogger`,
  `PluginMetadata`, `PluginPermission`, `UIExtension`, `EditorExtension`,
  `EditorTabExtension`, `ProjectExtension`, `DocumentationExtension`,
  `IdeProjectService`, `IdeEditorService`, `IdeUIService`, `IdeBuildService`,
  `IdeFileService`, `IdeEditorTabService`, `IdeTooltipService`,
  and the manifest `<meta-data>` contract.

## Caveats

- **Pre-26.28 has no ABI dump.** The validator arrived in 26.28. Earlier entries
  (`[reconstructed]`) were recovered by diffing each `plugin-api/src` commit — the
  symbol lists are accurate, but read from source rather than a frozen contract.
- **Early weeks collapse onto 26.02.** The oldest release tag is `26.02`, so both
  the September genesis and the December sidebar work report `26.02` as their
  first shipped version — not because they were written that week, but because no
  earlier release was ever tagged. A plugin can safely floor at `26.02` for
  anything in that band.
- **This is a history, not a compatibility guarantee.** The plugin API is
  deliberately still evolving — see [plugin-api.md](plugin-api.md).
- App-side/manager-only symbols (e.g. `IdeNavigationRailView`, `PluginValidation`,
  `.codeonthego/scripts.json`) are intentionally omitted — plugins don't compile
  against them.

## Regenerating this doc

The source of truth is the ABI dump, so each new release's additions can be listed
mechanically. For the window between two release tags:

```bash
git log --oneline 26.29..26.30 -- plugin-api/api/plugin-api.api
git diff 26.29 26.30 -- plugin-api/api/plugin-api.api
```

Map any commit to the release that first shipped it:

```bash
git tag --list --contains <sha> | grep -E '^[0-9]{2}\.[0-9]{2}$' | sort -V | head -1
```
