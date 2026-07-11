# Plugin API — surface, stability & compatibility

Guidance for **maintainers changing the Code on the Go plugin API**. It defines what counts as the plugin contract, the current compatibility policy, and how to change the API without breaking plugins already in the field by accident.

This is *not* a plugin-authoring how-to. For that — project layout, manifest contract, building a `.cgp` — see the **Plugin Development wiki** (<https://appdevforall.atlassian.net/wiki/x/BQANIQ>), the authoritative API reference, and the in-tree example plugins.

## What counts as the plugin API (the contract)

The surface a plugin binds to is broader than one module. All of the following are contract:

- **The `:plugin-api` module** — package `com.itsaky.androidide.plugins.*`:
  - Core: `IPlugin` (lifecycle), `PluginContext`, `PluginLogger`, `ServiceRegistry`, `ResourceManager`.
  - Extension interfaces plugins **implement**: `UIExtension`, `EditorExtension`, `EditorTabExtension`, `DocumentationExtension`, `BuildActionExtension`, `SnippetExtension`, `ProjectExtension`, `FileOpenExtension`.
  - IDE service interfaces plugins **call** (via `ServiceRegistry.get(X::class.java)`): `IdeProjectService`, `IdeEditorService`, `IdeFileService`, `IdeEnvironmentService`, `IdeArchiveService`, `IdeBuildService`, `IdeUIService`, `IdeEditorTabService`, `IdeTooltipService`, `IdeThemeService`, `IdeFeatureFlagService`, `IdeCommandService`, `IdeTemplateService`, `IdeSnippetService`, `IdeSidebarService`.
  - Data classes plugins **construct** (e.g. `MenuItem`, `TabItem`, `EditorTabItem`, `NavigationItem`, `ToolbarAction`, `FabAction`, `PluginBuildAction`, `SnippetContribution`, `PluginTooltipEntry`).
  - Enums / sealed types plugins **reference**: `PluginPermission`, `ShowAsAction`, `ArchiveFormat`, `BuildActionCategory`, `ToolbarActionIds`, `CommandSpec`, `CommandResult`, `ExtractResult`.
- **Wire/format contracts outside the module:**
  - Manifest `<meta-data>` keys — `plugin.id`, `plugin.name`, `plugin.version`, `plugin.description`, `plugin.author`, `plugin.main_class`, `plugin.min_ide_version`, `plugin.max_ide_version`, `plugin.permissions`, `plugin.sidebar_items`, `plugin.icon_day`, `plugin.icon_night`. Matched **by string** — a rename silently breaks every plugin.
  - Permission **key strings** (`filesystem.read`, `filesystem.write`, `network.access`, `system.commands`, `ide.settings`, `project.structure`, `native.code`, `ide.environment.write`) — also matched by string.
  - The path allowlist and per-plugin data directories enforced by `IdeFileService` / `IdeArchiveService`.
  - File/format contracts: the `.cgp` package format, the `.cgt` template format, the `plugin_documentation.db` schema, `.codeonthego/scripts.json`, the TextMate snippet syntax, and the `http://localhost:6174/` help-server URL scheme with the `plugin/<pluginId>/` namespace.

## Current compatibility policy — read this

**The plugin API is still evolving and is NOT frozen. We do not yet guarantee source or binary backward compatibility across IDE releases.** This is a deliberate early-stage decision — we're still discovering what plugins need, and freezing too early would lock in mistakes.

That is **not** license to break plugins casually. The rule for now:

> Every change to the surface above must be **deliberate, documented, and justified.** Know when you're changing the contract, say so in the PR (what breaks, who's affected, why it's worth it), and record it here or in a changelog. Prefer **additive** changes; gate behavior with `plugin.min_ide_version` / `plugin.max_ide_version` where it helps.

When the API is later frozen, this doc gains a formal compatibility guarantee and (ideally) automated enforcement — see Follow-ups.

## Know when you're making a breaking change (Kotlin traps)

These look source-compatible but break already-built `.cgp` plugins:

- **Data-class constructor parameters.** Adding a parameter *even with a default value* changes the synthetic constructor and `copy()` signatures — binary-incompatible for any plugin that constructs or copies the class (`MenuItem`, `PluginBuildAction`, `SnippetContribution`, …). If compatibility matters, add a secondary constructor or a builder instead.
- **Interface methods — direction matters.**
  - *Extension interfaces* (`UIExtension`, `BuildActionExtension`, …) are implemented **by plugins**: adding a method is breaking for them (even a defaulted one can break depending on compilation). Provide defaults and prefer additive optional hooks.
  - *Service interfaces* (`Ide*Service`) are implemented **by the host** and only called by plugins: **adding** a method is safe; changing or removing a signature is breaking.
- **Enum constants.** Removing or renaming a constant (`PluginPermission`, `ShowAsAction`, `ArchiveFormat`, `ToolbarActionIds`, `BuildActionCategory`) breaks plugins that name it; adding one can still break an exhaustive `when`.
- **Types & nullability.** Flipping nullable↔non-null, changing a parameter/return type, or `val`↔`var` on an API property.
- **Moving or renaming** any class/package under `com.itsaky.androidide.plugins.*` — breaks imports and `ServiceRegistry.get(...)` lookups.
- **Manifest key or permission-string renames** — silently break every existing plugin, since both are matched by string.

## Before you change the plugin API — checklist

- [ ] Is the change to `:plugin-api` (or a manifest key / permission string / format) actually necessary? Prefer additive over breaking.
- [ ] If it breaks compatibility, is that called out explicitly in the PR — what breaks, who's affected, why it's worth it?
- [ ] Recorded here or in a changelog so plugin authors can find it.
- [ ] Impact-checked against the in-tree example plugins — `apk-viewer-plugin/`, `markdown-preview-plugin/`, `keystore-generator-plugin/` — and, for significant changes, the external `plugin-examples` repo: do they still compile and load?
- [ ] Version gating (`plugin.min_ide_version` / `plugin.max_ide_version`) considered if runtime behavior changes.

## Follow-ups

- **No automated binary-compatibility checking yet.** `:plugin-api` has no API dump / validator (e.g. Kotlin's binary-compatibility-validator with checked-in `.api` files). Adding one would turn "know when you're breaking the API" from reviewer discipline into a CI gate — worth doing when the API firms up.

## Related

- [REVIEW.md](../REVIEW.md) §13 — reviewing a change's impact on plugins.
- [ARCHITECTURE.md](../ARCHITECTURE.md) — where `:plugin-api` / `:plugin-manager` sit in the module map.
