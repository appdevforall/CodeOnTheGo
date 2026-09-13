# ADFA-4510: Missing tooltips on code actions

**Ticket:** [ADFA-4510](https://appdevforall.atlassian.net/browse/ADFA-4510) (Bug, Important 4/10, `R2-bugs`)
**Branch:** `bugfix/ADFA-4510-missing-tooltips-code-actions`

## Problem

Long-pressing an item in the editor's Code Actions menu shows nothing. The ticket attributes this to
unimplemented tooltip tags. That diagnosis is wrong: 14 of the 15 tags Elissa listed are already
wired to their actions, and all 15 exist in `documentation.db`. The tooltips fail in the render path.

Note on the database: our local copy may be stale, so DB contents are not treated as authoritative
here. This spec changes only code. Any tag that still shows nothing after this work is a content
hand-off item, not a code defect.

### Root cause

Every code action renders through one bind site, `editor/.../EditorActionsMenu.kt:426`:

```kotlin
val action = getInstance().findAction(location, item.itemId)
val tooltipTag = action?.retrieveTooltipTag(false) ?: ""
val tag = tooltipTag.ifEmpty { item.contentDescription?.toString() ?: "" }
```

Three defects stack here.

**1. `action` is always null for code actions.** Code actions are never registered with the registry;
they are added as children of `CodeActionsMenu` (`lsp/api/.../LSPEditorActions.java:47`).
`DefaultActionsRegistry.findAction` (`:117-125`) scans only the flat per-location map and never
recurses into `ActionMenu.children`. The submenu adapter also receives `onGetActionLocation()` =
`EDITOR_TEXT_ACTIONS` (`:493`), the parent's location. Since `itemId = id.hashCode()`
(`ActionItem.kt:113`), no match is possible.

**2. A successful lookup would still return `""`.** `ActionItem` carries two members meaning the same
thing: the property `tooltipTag` (`:73-78`) and the function `retrieveTooltipTag()` (`:92`), both
defaulting to `""`. The bind site calls the function. Across `lsp/` there are **0** overrides of the
function and **22** of the property.

**3. The fallback guarantees a miss.** `item.contentDescription` is set to `action.label`
(`DefaultActionsRegistry.kt:217`) and by nothing else, so the code queries the tooltip DB for a tag
named e.g. `"Comment line"`. `ToolTipManager.kt:211` logs and shows nothing — silence on long-press,
which `REVIEW.md:164` forbids.

`editor.toolbar.codeactions` works because `CodeActionsMenu` is registered *and* overrides the
function (`CodeActionsMenu.kt:41`) — the opposite of its own children on both counts.

### Current state of the 15 tags

| Status | Count | Why |
| --- | --- | --- |
| Show nothing | 11 | Menu items, blocked by defects 1 and 2 |
| Work | 3 | `genconstructor.dialog`, `gentostring.dialog`, `settergetter.dialog` — `FieldBasedAction.kt:250-263` calls `TooltipManager` directly, bypassing the bind site |
| Dead constant | 1 | `overridesuper.dialog` is referenced nowhere; `OverrideSuperclassMethodsAction.kt:212-224` passes the menu-item tag, so the dialog shows the wrong tooltip |

## Design

### 1. Unify the two tag members

`actions/src/main/java/com/itsaky/androidide/actions/ActionItem.kt:92`

```kotlin
fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = tooltipTag
```

Fixes defect 2 for every consumer at once. The change is one-directional and cannot regress:

| Action overrides | Before | After |
| --- | --- | --- |
| neither member | `""` | `""` |
| the function | function value | function value |
| the property | `""` | property value |

### 2. Let an `ActionMenu` find a child by `itemId`

`actions/src/main/java/com/itsaky/androidide/actions/ActionMenu.kt`, mirroring the existing
`findAction(id: String)`:

```kotlin
fun findAction(itemId: Int): ActionItem? = children.find { it.itemId == itemId }
```

Flat, one level. Nested action menus do not occur in this codebase; recursion would be speculative.

### 3. Give the submenu adapter its parent menu

`editor/src/main/java/com/itsaky/androidide/editor/ui/EditorActionsMenu.kt`

`ActionsListAdapter` gains `val actionMenu: ActionMenu? = null`. At `:493` the submenu adapter is
constructed with the resolved parent — `findAction(location, item.itemId)` already returns
`CodeActionsMenu` correctly, so no registry change is needed:

```kotlin
val parent = getInstance().findAction(location, item.itemId) as? ActionMenu
this.list.adapter =
    ActionsListAdapter(item.subMenu, true, editor, location = onGetActionLocation(), actionMenu = parent)
```

The top-level adapter at `:309` passes `null` and behaves exactly as today.

### 4. Resolve tag and category at the bind site

Replaces `:432-434` and `:458-467`. Drops the `contentDescription` fallback and stops hardcoding the
`ide` category, so plugin-contributed code actions resolve against their own `plugin_<id>` category:

```kotlin
val action = actionMenu?.findAction(item.itemId)
    ?: getInstance().findAction(location, item.itemId)
val tag = action?.retrieveTooltipTag(false) ?: ""
val category = action?.retrieveTooltipCategory() ?: TooltipCategory.CATEGORY_IDE
...
button.setOnLongClickListener {
    if (tag.isEmpty()) {
        log.warn("No tooltip tag for action '{}'", item.title)
    } else {
        TooltipManager.showTooltip(editor.context, editor, category, tag)
    }
    true
}
```

A logger is added to the existing companion object (`:81`) following the module idiom,
`LoggerFactory.getLogger(...)` as in `IDEEditor.kt:231`. The warn makes an untagged action visible in
logcat instead of silently absent.

### 5. Tag corrections

- **Drop** `tooltipTag` from `VariableToStatementAction.kt:42` and `FieldToBlockAction.kt:41`. Both
  carry `EDITOR_CODE_ACTIONS_FIX_IMPORTS` by copy-paste; neither touches imports. Unifying the
  members would turn them from silent into actively wrong. They stay silent, correctly.
- **Fix** `OverrideSuperclassMethodsAction.kt:212-224` to pass
  `EDITOR_CODE_ACTIONS_OVERRIDE_SUPER_DIALOG` for the dialog long-press instead of the menu-item tag.
  Retires the dead constant and completes the fourth dialog tag.

## Testing

Both existing tooltip tests pass despite this bug: they assert on the property while the render path
reads the function. New coverage targets the seam that actually failed.

### `actions/src/test/.../ActionTooltipResolutionTest.kt`

First tests in the `actions` module; adds `testImplementation(projects.testing.unit)`. No circular
dependency — `testing/unit` depends on `buildInfo`, `common`, `shared`, `testing/common` only.
Plain JVM, no Robolectric. Covers both halves of the resolution chain with hand-rolled fake
`ActionItem` / `ActionMenu` implementations.

`ActionMenu.findAction(itemId)`:

- returns the matching child
- returns null for an unknown itemId

`ActionItem.retrieveTooltipTag(false)`:

- returns the property value when only `tooltipTag` is overridden (the ADFA-4510 regression)
- returns the function value when only `retrieveTooltipTag` is overridden
- returns `""` when neither is overridden

### `lsp/java/src/test/.../JavaCodeActionTooltipTagTest.kt`

Mirrors `KotlinCodeActionTooltipTagTest`, into an existing test source set. No new dependencies:
`lsp/java/build.gradle.kts:69` already has `testImplementation(projects.testing.lsp)`, which
re-exports `testing/unit` (JUnit, Truth, MockK).

- Whole-map `assertEquals` over all 22 actions in `JavaCodeActionsMenu`, read through
  `retrieveTooltipTag(false)` — the member the render path uses. A whole-map comparison means a newly
  registered untagged action fails automatically.
- Every non-empty tag `startsWith("editor.codeactions.")`.
- The 8 untagged actions pin explicitly to `""`, so tagging one later is a deliberate test edit
  rather than silent drift.

### Manual verification

Build `:app:assembleV8Debug`, install on `emulator-5554`, open a Java file, and long-press each code
action to confirm a popup renders.

## Outcome

14 of 22 Java code actions resolve a tag. All 11 menu tags and all 4 dialog tags from the ticket are
reachable from code.

The 8 actions with no tag — `RemoveClassAction`, `RemoveMethodAction`, `RemoveUnusedThrowsAction`,
`CreateMissingMethodAction`, `SuppressUncheckedWarningAction`, `AddThrowsAction`, plus the two
corrected above — stay silent. They are outside the ticket's scope and need authored content before
tagging is meaningful.

## Out of scope

Filed or noted, not addressed here:

- All 8 Kotlin code-action tags (`editor.codeactions.kotlin.*`) appear to have no DB rows. Content
  hand-off, not code.
- `idetooltips/README.md` documents a Room database and an API that no longer exist (ADFA-4382).
- Tooltip tag/DB reconciliation in CI. The DB lives outside the repo and our copy may be stale, so a
  meaningful check is not possible from this worktree.

## Conflict risk

Open PR #1624 (ADFA-4824) edits `TooltipTag.kt`, `KotlinCodeActionsMenu.kt`, and
`KotlinCodeActionTooltipTagTest.kt`. This work adds no constants to `TooltipTag.kt` and touches no
Kotlin LSP file, so the surfaces do not overlap.
