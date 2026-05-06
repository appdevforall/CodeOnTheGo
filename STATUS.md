# ADFA-2436 implementation status (overnight run, 2026-05-06 / 07)

## Plan vs. reality

Plan target tonight: C1 + C2 + C3, C4+ if time permits.

## Current state

(updated as work progresses)

- [x] C1 — Plugin scaffolding + stub templates **(modified — see Q1 in QUESTIONS.md)**
- [/] C3 — "Map Regions" bottom-sheet plugin tab **(stub fragment + empty state landed in C1; live data wires up in C2)**
- [ ] C2 — Wizard with full multi-step flow (BLOCKED on Q1)
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
