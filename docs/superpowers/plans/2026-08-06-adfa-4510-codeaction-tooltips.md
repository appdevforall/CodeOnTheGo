# ADFA-4510 Code Action Tooltips Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make long-press show a tooltip on every tagged item in the editor's Code Actions menu.

**Architecture:** `ActionItem` carries two members meaning the same thing (`tooltipTag` property, `retrieveTooltipTag()` function); the code-action render path reads the function while all 22 LSP actions override the property. We unify them at the interface, teach `ActionMenu` to look up a child by `itemId` (the registry cannot see submenu children), hand the submenu adapter its parent menu, and delete a fallback that guaranteed a failed lookup. Two mis-copied tags are dropped and one dead dialog constant is wired up.

**Tech Stack:** Kotlin, Android (`com.android.library` modules with `v7`/`v8` ABI flavors), JUnit 4 + Truth + Robolectric via `projects.testing.unit`, Gradle wrapped in `flox`, Spotless/ktlint with a `ratchetFrom = "origin/stage"` file-level ratchet.

**Spec:** `docs/superpowers/specs/2026-08-06-adfa-4510-codeaction-tooltips-design.md`

## Global Constraints

- **Indentation is TABS, line endings LF.** Enforced by Spotless. Every Kotlin snippet below is already tab-indented — preserve it.
- **The Spotless ratchet is file-level, not line-level.** Touching one line of a space-indented file pulls the *whole file* under the ratchet and reformats it to tabs. Task 1 exists solely to get that churn into its own commit. Do not skip it.
- **Never run bare `./gradlew`.** Always `flox activate -d flox/local -- ./gradlew <task>`.
- **Unit test task for these modules is `testV8DebugUnitTest`**, not `test`. The aggregate `test` task rejects `--tests`.
- **Do not add tooltip tag constants.** `TooltipTag.kt` is untouched by this plan — open PR #1624 edits it and we must not collide.
- **Do not edit anything under `lsp/kotlin/`.** Same conflict reason.
- **New test files carry no license header** — match `KotlinCodeActionTooltipTagTest.kt`, which starts directly with `package`.
- **Branch:** `bugfix/ADFA-4510-missing-tooltips-code-actions`. Commit after every task.

---

### Task 1: Reindent space-indented target files to tabs

Four files we must edit are space-indented. Reformatting them is mechanical and must not be mixed with logic changes. The ratchet only reformats files that differ from `origin/stage`, so we make a throwaway whitespace change first to make Spotless see them.

**Files:**
- Modify: `actions/src/main/java/com/itsaky/androidide/actions/ActionItem.kt`
- Modify: `actions/src/main/java/com/itsaky/androidide/actions/ActionMenu.kt`
- Modify: `lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/diagnostics/VariableToStatementAction.kt`
- Modify: `lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/diagnostics/FieldToBlockAction.kt`

**Interfaces:**
- Consumes: nothing
- Produces: nothing. This task is whitespace-only by construction and is verified as such.

- [ ] **Step 1: Make each file differ from `origin/stage` so the ratchet picks it up**

```bash
cd "$(git rev-parse --show-toplevel)"
for f in actions/src/main/java/com/itsaky/androidide/actions/ActionItem.kt \
         actions/src/main/java/com/itsaky/androidide/actions/ActionMenu.kt \
         lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/diagnostics/VariableToStatementAction.kt \
         lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/diagnostics/FieldToBlockAction.kt; do
  printf '\n' >> "$f"
done
git diff --stat
```

Expected: 4 files listed, 1 insertion each.

- [ ] **Step 2: Run Spotless**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
```

Expected: BUILD SUCCESSFUL. The trailing blank lines are removed and all four files are reindented to tabs.

- [ ] **Step 3: Prove the change is formatting-only**

ktlint does more than reindent, so `git diff -w` will NOT be empty. Expect these
behaviour-preserving normalisations, and nothing else:

- blank line removed after a declaration opens
- parameter lists exploded one-per-line with a trailing comma
- block bodies collapsed to expression bodies (`{ return x }` becomes `= x`)
- enum entries gaining a trailing comma and `;`
- a `a; b` one-liner split onto two lines

```bash
git diff -w
```

Read every hunk. Each must fall into the list above. If you see a changed
identifier, literal, condition, or call argument — anything that could alter
behaviour — STOP and report BLOCKED without committing.

Then prove it compiles:

```bash
flox activate -d flox/local -- ./gradlew :actions:compileV8DebugKotlin :lsp:java:compileV8DebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Confirm the files are now tab-indented**

```bash
grep -c $'^\t' actions/src/main/java/com/itsaky/androidide/actions/ActionItem.kt
```

Expected: a non-zero count (was 0 before).

- [ ] **Step 5: Commit**

```bash
git add actions/src/main/java/com/itsaky/androidide/actions/ActionItem.kt \
        actions/src/main/java/com/itsaky/androidide/actions/ActionMenu.kt \
        lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/diagnostics/VariableToStatementAction.kt \
        lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/diagnostics/FieldToBlockAction.kt
git commit -m "style(ADFA-4510): reformat files to tabs ahead of edits

Spotless ratchets whole files, so reformatting these four up front keeps the
following commits pure logic. ktlint normalisations only -- tabs, trailing
commas, expression bodies. No behaviour change; both modules compile."
```

---

### Task 2: Unify the tag members and add `ActionMenu.findAction(itemId)`

The two fixes at the heart of the bug, developed test-first. This is also the `actions` module's first unit test, so it needs test wiring.

**Files:**
- Modify: `actions/build.gradle.kts` (add `testImplementation`)
- Modify: `actions/src/main/java/com/itsaky/androidide/actions/ActionItem.kt` (line ~92 after Task 1)
- Modify: `actions/src/main/java/com/itsaky/androidide/actions/ActionMenu.kt`
- Create: `actions/src/test/java/com/itsaky/androidide/actions/ActionTooltipResolutionTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `ActionMenu.findAction(itemId: Int): ActionItem?` — returns the child whose `itemId` matches, else `null`. Used by Task 4.
  - `ActionItem.retrieveTooltipTag(isReadOnlyContext: Boolean): String` now defaults to `tooltipTag` instead of `""`. Used by Task 3 and Task 4.

- [ ] **Step 1: Add the test dependency**

In `actions/build.gradle.kts`, inside the existing `dependencies { ... }` block, add this line after `implementation(libs.google.material)`:

```kotlin
	testImplementation(projects.testing.unit)
```

`testing/unit` brings JUnit 4, Truth, MockK and Robolectric. It depends only on `buildInfo`, `common`, `shared` and `testing/common`, so there is no dependency cycle with `actions`.

- [ ] **Step 2: Write the failing test**

Create `actions/src/test/java/com/itsaky/androidide/actions/ActionTooltipResolutionTest.kt`:

```kotlin
package com.itsaky.androidide.actions

import android.graphics.drawable.Drawable
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the two halves of code-action tooltip resolution that failed in ADFA-4510: finding a
 * submenu child by its menu item id, and reading a tag from whichever member the action overrode.
 *
 * Code actions are children of CodeActionsMenu and are never registered with the registry, so the
 * render path can only reach them through [ActionMenu.findAction]. They override the `tooltipTag`
 * property while the render path reads `retrieveTooltipTag()`, so both must resolve to the same
 * value.
 */
@RunWith(RobolectricTestRunner::class)
class ActionTooltipResolutionTest {
	private open class FakeAction(
		override val id: String,
	) : ActionItem {
		override var label: String = id
		override var visible: Boolean = true
		override var enabled: Boolean = true
		override var icon: Drawable? = null
		override var requiresUIThread: Boolean = false
		override var location: ActionItem.Location = ActionItem.Location.EDITOR_CODE_ACTIONS

		override suspend fun execAction(data: ActionData): Any = true
	}

	private class PropertyOnlyAction : FakeAction("fake.propertyOnly") {
		override var tooltipTag: String = "editor.codeactions.comment"
	}

	private class FunctionOnlyAction : FakeAction("fake.functionOnly") {
		override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String =
			"editor.codeactions.gotodef"
	}

	private class UntaggedAction : FakeAction("fake.untagged")

	private class FakeMenu : ActionMenu {
		override val children: MutableSet<ActionItem> = mutableSetOf()
		override val id: String = "fake.menu"
		override var label: String = "Fake menu"
		override var visible: Boolean = true
		override var enabled: Boolean = true
		override var icon: Drawable? = null
		override var requiresUIThread: Boolean = false
		override var location: ActionItem.Location = ActionItem.Location.EDITOR_TEXT_ACTIONS
	}

	private fun menuOf(vararg actions: ActionItem) = FakeMenu().apply { actions.forEach(::addAction) }

	@Test
	fun `findAction by itemId returns the matching child`() {
		val child = PropertyOnlyAction()
		val menu = menuOf(UntaggedAction(), child)

		assertThat(menu.findAction(child.itemId)).isSameInstanceAs(child)
	}

	@Test
	fun `findAction by itemId returns null when no child matches`() {
		val menu = menuOf(UntaggedAction())

		assertThat(menu.findAction("nothing.registered".hashCode())).isNull()
	}

	@Test
	fun `retrieveTooltipTag reads an action that overrides only the property`() {
		assertThat(PropertyOnlyAction().retrieveTooltipTag(false))
			.isEqualTo("editor.codeactions.comment")
	}

	@Test
	fun `retrieveTooltipTag reads an action that overrides only the function`() {
		assertThat(FunctionOnlyAction().retrieveTooltipTag(false))
			.isEqualTo("editor.codeactions.gotodef")
	}

	@Test
	fun `retrieveTooltipTag is empty when the action overrides neither member`() {
		assertThat(UntaggedAction().retrieveTooltipTag(false)).isEmpty()
	}
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
flox activate -d flox/local -- ./gradlew :actions:testV8DebugUnitTest \
  --tests "com.itsaky.androidide.actions.ActionTooltipResolutionTest"
```

Expected: FAIL. Two distinct failures:
- a compile error, `Unresolved reference: findAction` (the `Int` overload does not exist yet)
- once that compiles, `retrieveTooltipTag reads an action that overrides only the property` fails with `expected: editor.codeactions.comment but was: ` (empty)

- [ ] **Step 4: Add the `itemId` lookup to `ActionMenu`**

In `actions/src/main/java/com/itsaky/androidide/actions/ActionMenu.kt`, directly below the existing `findAction(id: String)` function, add:

```kotlin
	/**
	 * Find the child action with the given menu item ID.
	 *
	 * Child actions are not registered with the [ActionsRegistry], so the registry cannot resolve
	 * them; a submenu's renderer must look them up here (ADFA-4510).
	 *
	 * @return The action item or `null` if not found.
	 */
	fun findAction(itemId: Int): ActionItem? {
		return children.find { it.itemId == itemId }
	}
```

- [ ] **Step 5: Unify the tag members in `ActionItem`**

In `actions/src/main/java/com/itsaky/androidide/actions/ActionItem.kt`, change the body of `retrieveTooltipTag`. Replace:

```kotlin
	fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = ""
```

with:

```kotlin
	fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = tooltipTag
```

Then extend the existing KDoc's `@return` line so the delegation is documented. Replace:

```kotlin
	 * @return The appropriate tooltip tag for the given context, or an empty string if
	 * no tooltip is available.
	 */
```

with:

```kotlin
	 * @return The appropriate tooltip tag for the given context, or an empty string if
	 * no tooltip is available. Defaults to [tooltipTag], so an action may override either
	 * member and every consumer sees the same value (ADFA-4510).
	 */
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
flox activate -d flox/local -- ./gradlew :actions:testV8DebugUnitTest \
  --tests "com.itsaky.androidide.actions.ActionTooltipResolutionTest"
```

Expected: PASS, 5 tests.

- [ ] **Step 7: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add actions/build.gradle.kts \
        actions/src/main/java/com/itsaky/androidide/actions/ActionItem.kt \
        actions/src/main/java/com/itsaky/androidide/actions/ActionMenu.kt \
        actions/src/test/java/com/itsaky/androidide/actions/ActionTooltipResolutionTest.kt
git commit -m "fix(ADFA-4510): resolve tooltip tags from either ActionItem member

retrieveTooltipTag() defaulted to \"\" while every LSP code action overrides the
tooltipTag property, so the code-actions renderer always read an empty tag.
Default the function to the property instead.

Add ActionMenu.findAction(itemId) so a submenu's renderer can reach children,
which are never registered with the ActionsRegistry."
```

---

### Task 3: Pin Java code action tags and drop two mis-copied ones

`VariableToStatementAction` (converts a field to a local variable) and `FieldToBlockAction` both carry `EDITOR_CODE_ACTIONS_FIX_IMPORTS` by copy-paste. Neither touches imports. Before Task 2 they were silent; after it they would show import-fixing help on unrelated actions. Dropping the overrides keeps them silent, which is correct.

The pinning test reads through `retrieveTooltipTag(false)` — the member the render path uses — unlike the Kotlin test which reads the property. All 22 actions are asserted as one map so a newly registered untagged action fails automatically.

**Files:**
- Create: `lsp/java/src/test/java/com/itsaky/androidide/lsp/java/actions/JavaCodeActionTooltipTagTest.kt`
- Modify: `lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/diagnostics/VariableToStatementAction.kt`
- Modify: `lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/diagnostics/FieldToBlockAction.kt`

**Interfaces:**
- Consumes: `ActionItem.retrieveTooltipTag(isReadOnlyContext: Boolean)` from Task 2, which must already delegate to `tooltipTag`.
- Produces: nothing consumed by later tasks.

No new dependency is needed: `lsp/java/build.gradle.kts:69` already has `testImplementation(projects.testing.lsp)`, which re-exports `testing/unit`.

- [ ] **Step 1: Write the failing test**

Create `lsp/java/src/test/java/com/itsaky/androidide/lsp/java/actions/JavaCodeActionTooltipTagTest.kt`:

```kotlin
package com.itsaky.androidide.lsp.java.actions

import com.itsaky.androidide.idetooltips.TooltipTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins each Java code action to its tooltip tag. Tooltip content is authored per tag and looked up
 * by that tag, so a wrong tag fails silently at runtime: the action shows another action's tooltip
 * or none at all (ADFA-4510).
 *
 * Tags are read through retrieveTooltipTag(), the member the code-actions renderer calls. The
 * Kotlin equivalent asserts on the tooltipTag property instead, which is why it kept passing while
 * ADFA-4510 was live.
 *
 * Actions pinned to "" have no authored tooltip yet. Tagging one later must be a deliberate edit
 * here, not a silent drift.
 */
class JavaCodeActionTooltipTagTest {
	private val actualTags
		get() = JavaCodeActionsMenu.actions.associate { it.id to it.retrieveTooltipTag(false) }

	@Test
	fun `every java code action maps to its own tooltip tag`() {
		val expected =
			mapOf(
				"ide.editor.lsp.java.commentLine" to TooltipTag.EDITOR_CODE_ACTIONS_COMMENT,
				"ide.editor.lsp.java.uncommentLine" to TooltipTag.EDITOR_CODE_ACTIONS_UNCOMMENT,
				"ide.editor.lsp.java.gotoDefinition" to TooltipTag.EDITOR_CODE_ACTIONS_GOTO_DEF,
				"ide.editor.lsp.java.findReferences" to TooltipTag.EDITOR_CODE_ACTIONS_FIND_REFS,
				"ide.editor.lsp.java.diagnostics.addImport" to TooltipTag.EDITOR_CODE_ACTIONS_FIX_IMPORTS,
				"ide.editor.lsp.java.diagnostics.autoFixImports" to TooltipTag.EDITOR_CODE_ACTIONS_FIX_IMPORTS,
				"ide.editor.lsp.java.diagnostics.implementAbstractMethods" to
					TooltipTag.EDITOR_CODE_ACTIONS_OVERRIDE_SUPER,
				"ide.editor.lsp.java.generator.settersAndGetters" to
					TooltipTag.EDITOR_CODE_ACTIONS_SETTER_GETTER,
				"ide.editor.lsp.java.generator.overrideSuperclassMethods" to
					TooltipTag.EDITOR_CODE_ACTIONS_OVERRIDE_SUPER,
				"ide.editor.lsp.java.generator.missingConstructor" to
					TooltipTag.EDITOR_CODE_ACTIONS_GEN_CONSTRUCTOR,
				"ide.editor.lsp.java.generator.constructor" to
					TooltipTag.EDITOR_CODE_ACTIONS_GEN_CONSTRUCTOR,
				"ide.editor.lsp.java.generator.toString" to TooltipTag.EDITOR_CODE_ACTIONS_GEN_TO_STRING,
				"ide.editor.lsp.java.removeUnusedImports" to
					TooltipTag.EDITOR_CODE_ACTIONS_UNUSED_IMPORTS,
				"lsp_java_organizeImports" to TooltipTag.EDITOR_CODE_ACTIONS_ORGANIZE_IMPORTS,
				// No authored tooltip yet.
				"ide.editor.lsp.java.diagnostics.variableToStatement" to "",
				"ide.editor.lsp.java.diagnostics.fieldToBlock" to "",
				"ide.editor.lsp.java.diagnostics.removeClass" to "",
				"ide.editor.lsp.java.diagnostics.removeMethod" to "",
				"ide.editor.lsp.java.diagnostics.removeUnusedThrows" to "",
				"ide.editor.lsp.java.diagnostics.createMissingMethod" to "",
				"ide.editor.lsp.java.diagnostics.suppressUncheckedWarning" to "",
				"ide.editor.lsp.java.diagnostics.addThrows" to "",
			)
		assertEquals(expected, actualTags)
	}

	/** Guards a Java action drifting onto a Kotlin tag or some unrelated namespace. */
	@Test
	fun `no java code action borrows a non java code action tag`() {
		actualTags.forEach { (id, tag) ->
			if (tag.isEmpty()) return@forEach
			assertTrue(
				"$id uses tag '$tag' outside the java code actions namespace",
				tag.startsWith("editor.codeactions.") && !tag.startsWith("editor.codeactions.kotlin."),
			)
		}
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
flox activate -d flox/local -- ./gradlew :lsp:java:testV8DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.java.actions.JavaCodeActionTooltipTagTest"
```

Expected: FAIL on `every java code action maps to its own tooltip tag`. The map differs at two keys — `variableToStatement` and `fieldToBlock` return `editor.codeactions.fiximports` where `""` is expected.

- [ ] **Step 3: Drop the mis-copied tag from `VariableToStatementAction`**

In `lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/diagnostics/VariableToStatementAction.kt`, delete this line:

```kotlin
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_FIX_IMPORTS
```

Also remove the now-unused `import com.itsaky.androidide.idetooltips.TooltipTag` if no other reference to `TooltipTag` remains in the file (check with `grep -n TooltipTag` on that file).

- [ ] **Step 4: Drop the mis-copied tag from `FieldToBlockAction`**

In `lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/diagnostics/FieldToBlockAction.kt`, delete this line:

```kotlin
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_FIX_IMPORTS
```

Remove the now-unused `TooltipTag` import on the same condition as Step 3.

- [ ] **Step 5: Run the test to verify it passes**

```bash
flox activate -d flox/local -- ./gradlew :lsp:java:testV8DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.java.actions.JavaCodeActionTooltipTagTest"
```

Expected: PASS, 2 tests.

- [ ] **Step 6: Confirm the Kotlin pinning test still passes**

Task 2 changed a shared interface default, so re-run the neighbouring suite. Do not edit it.

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV8DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.KotlinCodeActionTooltipTagTest"
```

Expected: PASS, 2 tests.

- [ ] **Step 7: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add lsp/java/src/test/java/com/itsaky/androidide/lsp/java/actions/JavaCodeActionTooltipTagTest.kt \
        lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/diagnostics/VariableToStatementAction.kt \
        lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/diagnostics/FieldToBlockAction.kt
git commit -m "fix(ADFA-4510): pin java code action tooltip tags

VariableToStatementAction and FieldToBlockAction carried the fiximports tag by
copy-paste; neither touches imports. They were silent before this branch and
would have started showing wrong help. Drop both overrides.

Add JavaCodeActionTooltipTagTest, reading through retrieveTooltipTag() so it
exercises the member the renderer actually calls."
```

---

### Task 4: Resolve tag and category at the code actions bind site

The render-path fix. `ActionsListAdapter` gains an optional parent menu so submenu children resolve, the `contentDescription` fallback is deleted, and the hardcoded `ide` category is replaced by the action's own category so plugin-contributed code actions look up their `plugin_<id>` rows.

**Files:**
- Modify: `editor/src/main/java/com/itsaky/androidide/editor/ui/EditorActionsMenu.kt`

**Interfaces:**
- Consumes: `ActionMenu.findAction(itemId: Int): ActionItem?` and the `retrieveTooltipTag` delegation, both from Task 2.
- Produces: nothing consumed by later tasks.

This file is already tab-indented, so no reformat churn. It has no logger yet; we add one to the existing companion object following the module idiom (`IDEEditor.kt:231`).

- [ ] **Step 1: Add the imports**

In `editor/src/main/java/com/itsaky/androidide/editor/ui/EditorActionsMenu.kt`, add to the import block, each in its existing alphabetical position:

```kotlin
import com.itsaky.androidide.actions.ActionMenu
import com.itsaky.androidide.idetooltips.TooltipCategory
```

`org.slf4j.LoggerFactory` goes with the other non-`com.itsaky` imports at the bottom of the block:

```kotlin
import org.slf4j.LoggerFactory
```

- [ ] **Step 2: Add a logger to the companion object**

Replace the existing companion object (around line 81):

```kotlin
	companion object {
		const val DELAY: Long = 200
	}
```

with:

```kotlin
	companion object {
		const val DELAY: Long = 200

		private val log = LoggerFactory.getLogger(EditorActionsMenu::class.java)
	}
```

- [ ] **Step 3: Give `ActionsListAdapter` an optional parent menu**

Replace the adapter's constructor (around line 403):

```kotlin
	private class ActionsListAdapter(
		val menu: Menu?,
		val forceShowTitle: Boolean = false,
		val editor: IDEEditor,
		val location: ActionItem.Location,
	) : RecyclerView.Adapter<VH>() {
```

with:

```kotlin
	private class ActionsListAdapter(
		val menu: Menu?,
		val forceShowTitle: Boolean = false,
		val editor: IDEEditor,
		val location: ActionItem.Location,
		// Children of a submenu are not registered with the ActionsRegistry, so they can only be
		// resolved through their parent menu (ADFA-4510). Null for the top-level actions row.
		val actionMenu: ActionMenu? = null,
	) : RecyclerView.Adapter<VH>() {
```

- [ ] **Step 4: Resolve the action, tag and category in `onBindViewHolder`**

Replace these three lines (around line 432):

```kotlin
			val action = getInstance().findAction(location, item.itemId)
			val tooltipTag = action?.retrieveTooltipTag(false) ?: ""
			val tag = tooltipTag.ifEmpty { item.contentDescription?.toString() ?: "" }
```

with:

```kotlin
			val action =
				actionMenu?.findAction(item.itemId)
					?: getInstance().findAction(location, item.itemId)
			val tag = action?.retrieveTooltipTag(false) ?: ""
			val category = action?.retrieveTooltipCategory() ?: TooltipCategory.CATEGORY_IDE
```

The dropped fallback read `item.contentDescription`, which `DefaultActionsRegistry.kt:217` sets to the action's human-readable label. It could never match a tag, so it only turned "no tooltip" into a silent database miss.

- [ ] **Step 5: Show the tooltip in the action's own category, and log an untagged action**

Replace the long-click listener (around line 458):

```kotlin
			button.setOnLongClickListener {
				if (tag.isNotEmpty()) {
					TooltipManager.showIdeCategoryTooltip(
						context = editor.context,
						anchorView = editor,
						tag = tag,
					)
				}
				true
			}
```

with:

```kotlin
			button.setOnLongClickListener {
				if (tag.isEmpty()) {
					log.warn("No tooltip tag for action '{}'", item.title)
				} else {
					TooltipManager.showTooltip(
						context = editor.context,
						anchorView = editor,
						category = category,
						tag = tag,
					)
				}
				true
			}
```

- [ ] **Step 6: Pass the parent menu when building the submenu adapter**

Replace these lines in `onMenuItemSelected` (around line 490):

```kotlin
			this.list.layoutManager = LinearLayoutManager(editor.context)
			this.list.adapter =
				ActionsListAdapter(item.subMenu, true, editor, location = onGetActionLocation())
```

with:

```kotlin
			this.list.layoutManager = LinearLayoutManager(editor.context)
			val parentMenu = getInstance().findAction(onGetActionLocation(), item.itemId) as? ActionMenu
			this.list.adapter =
				ActionsListAdapter(
					item.subMenu,
					true,
					editor,
					location = onGetActionLocation(),
					actionMenu = parentMenu,
				)
```

`CodeActionsMenu` *is* registered at `DefaultActionsRegistry.kt:61`, so this lookup succeeds — it is only its children that the registry cannot see.

- [ ] **Step 7: Compile the module**

```bash
flox activate -d flox/local -- ./gradlew :editor:compileV8DebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Re-run both pinning suites and the resolver suite**

```bash
flox activate -d flox/local -- ./gradlew :actions:testV8DebugUnitTest \
  :lsp:java:testV8DebugUnitTest :lsp:kotlin:testV8DebugUnitTest
```

Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 9: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add editor/src/main/java/com/itsaky/androidide/editor/ui/EditorActionsMenu.kt
git commit -m "fix(ADFA-4510): resolve code action tooltips at the bind site

Pass the parent ActionMenu to the submenu adapter so code actions resolve; the
registry only holds top-level actions.

Drop the contentDescription fallback. It read the action's label, which can
never match a tag, so it converted a missing tooltip into a silent DB miss.
Log a warning instead.

Use the action's own tooltip category rather than hardcoding 'ide', so
plugin-contributed code actions hit their plugin_<id> rows."
```

---

### Task 5: Point the override-superclass dialog at its own tooltip tag

`EDITOR_CODE_ACTIONS_OVERRIDE_SUPER_DIALOG` is declared but referenced nowhere. The dialog passes the menu-item tag instead, so long-pressing it shows the wrong tooltip. The three sibling dialogs in `FieldBasedAction.kt` already do this correctly.

**Files:**
- Modify: `lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/generators/OverrideSuperclassMethodsAction.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: nothing consumed by later tasks.

This file is already tab-indented. The change is behavioural only inside a dialog callback, which no unit test can reach without an Android dialog; it is verified manually in Task 6.

- [ ] **Step 1: Confirm the constant is currently unreferenced**

```bash
grep -rn 'EDITOR_CODE_ACTIONS_OVERRIDE_SUPER_DIALOG' --include=*.kt .
```

Expected: exactly one hit, the declaration in `idetooltips/.../TooltipTag.kt`.

- [ ] **Step 2: Point both dialog long-press handlers at the dialog tag**

In `OverrideSuperclassMethodsAction.kt` (around lines 211-224), replace:

```kotlin
		val listView = dialog.listView
		listView.setOnItemLongClickListener { _, view, position, _ ->
			showTooltip(context, view, tooltipTag)
			true
		}

		dialog.setOnShowListener {
			val root = dialog.window?.decorView ?: return@setOnShowListener

			root.applyLongPressRecursively {
				showTooltip(context, root, tooltipTag)
				true
			}
		}
```

with:

```kotlin
		val listView = dialog.listView
		listView.setOnItemLongClickListener { _, view, position, _ ->
			showTooltip(context, view, TooltipTag.EDITOR_CODE_ACTIONS_OVERRIDE_SUPER_DIALOG)
			true
		}

		dialog.setOnShowListener {
			val root = dialog.window?.decorView ?: return@setOnShowListener

			root.applyLongPressRecursively {
				showTooltip(context, root, TooltipTag.EDITOR_CODE_ACTIONS_OVERRIDE_SUPER_DIALOG)
				true
			}
		}
```

`TooltipTag` is already imported in this file (it is used for the `tooltipTag` override); confirm with `grep -n 'import com.itsaky.androidide.idetooltips.TooltipTag' <file>` and add the import if absent.

- [ ] **Step 3: Verify the constant is now referenced**

```bash
grep -rn 'EDITOR_CODE_ACTIONS_OVERRIDE_SUPER_DIALOG' --include=*.kt .
```

Expected: three hits — the declaration plus the two call sites.

- [ ] **Step 4: Compile the module**

```bash
flox activate -d flox/local -- ./gradlew :lsp:java:compileV8DebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add lsp/java/src/main/java/com/itsaky/androidide/lsp/java/actions/generators/OverrideSuperclassMethodsAction.kt
git commit -m "fix(ADFA-4510): use the dialog tooltip tag in the override dialog

The method-selection dialog passed the menu item's tag, so it showed the menu
tooltip instead of its own. EDITOR_CODE_ACTIONS_OVERRIDE_SUPER_DIALOG was
declared but referenced nowhere."
```

---

### Task 6: Build and verify on the emulator

Static analysis and unit tests cannot prove a popup renders. This task confirms the fix end to end.

**Files:** none modified.

**Interfaces:**
- Consumes: all previous tasks.
- Produces: the evidence needed to close the ticket.

- [ ] **Step 1: Copy in the gitignored Firebase config if absent**

Fresh worktrees lack `app/google-services.json`, and `:app:processV8DebugGoogleServices` fails without it. It should already be present from worktree setup; confirm.

```bash
repo_root="$(git rev-parse --show-toplevel)"

# Already there? Nothing to do.
if [ ! -f "$repo_root/app/google-services.json" ]; then
  # Name the donor checkout explicitly -- never guess a sibling path, or you can
  # copy Firebase config from an unrelated project into this build.
  : "${GOOGLE_SERVICES_SRC:?set GOOGLE_SERVICES_SRC to an existing app/google-services.json}"
  [ -f "$GOOGLE_SERVICES_SRC" ] || {
    echo "not a file: $GOOGLE_SERVICES_SRC" >&2
    exit 1
  }
  cp "$GOOGLE_SERVICES_SRC" "$repo_root/app/google-services.json"
fi

ls -la "$repo_root/app/google-services.json"
```

- [ ] **Step 2: Run the full unit test sweep for the touched modules**

```bash
flox activate -d flox/local -- ./gradlew :actions:testV8DebugUnitTest \
  :lsp:java:testV8DebugUnitTest :lsp:kotlin:testV8DebugUnitTest :editor:testV8DebugUnitTest
```

Expected: BUILD SUCCESSFUL. Record the test counts.

- [ ] **Step 3: Verify formatting is clean**

```bash
flox activate -d flox/local -- ./gradlew spotlessCheck
```

Expected: BUILD SUCCESSFUL. If it fails, run `spotlessApply` and amend the relevant commit.

- [ ] **Step 4: Build the debug APK**

```bash
flox activate -d flox/local -- ./gradlew :app:assembleV8Debug --parallel --max-workers=6
```

Expected: BUILD SUCCESSFUL. This takes several minutes.

- [ ] **Step 4b: Build and side-load the assets payload**

`:app:assembleV8Debug` does NOT bundle the large assets. A debug install reads them from a
side-loaded zip, and without it the app comes up with no project templates, no Termux bootstrap, no
Android SDK, and no `documentation.db` — so no project can be opened and no tooltip can ever
resolve. `SplitAssetsInstaller` reads `Environment.SPLIT_ASSETS_ZIP`
(`common/.../Environment.java:143`), which is `/sdcard/Download/assets-<arch>.zip`.

```bash
flox activate -d flox/local -- ./gradlew :app:assembleV8Assets
adb -s emulator-5554 push app/build/outputs/assets/assets-arm64-v8a.zip \
  /sdcard/Download/assets-arm64-v8a.zip
```

The payload is ~1.1GB and the on-device install runs at next launch. Confirm afterwards:
`adb -s emulator-5554 shell run-as com.itsaky.androidide ls files/home/.cg/templates` must be
non-empty, and `databases/documentation.db` must exist.

- [ ] **Step 5: Confirm the emulator is up and install**

```bash
adb devices -l | grep -v offline
```

Expected: `emulator-5554` listed. The app is arm-only (`v7`/`v8`), so this must be an arm or arm-translation device. Then install the APK produced in Step 4:

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/v8/debug/*.apk
```

- [ ] **Step 6: Verify tooltips render**

Open a Java file in the IDE, select some text to raise the editor actions row, tap the Code actions item, then long-press menu entries. Note that the emulator's bottom gesture-exclusion zone swallows coordinate taps — drive the UI with `ACTION_CLICK` via accessibility (`mcp__android__tap_element`) rather than raw coordinates.

Check:
- Long-pressing a tagged entry (for example **Comment line**) shows a tooltip popup.
- Long-pressing an untagged entry (for example **Remove class**) shows nothing and logs `No tooltip tag for action` — confirm with `adb -s emulator-5554 logcat -d | grep "No tooltip tag"`.
- Open the **Override superclass methods** dialog and long-press it; the text should describe selecting methods to override, not the menu item's description.

Take a screenshot of a rendered tooltip as evidence for the ticket.

- [ ] **Step 7: Post progress to Jira**

```bash
jira issue comment add ADFA-4510 "Fixed in bugfix/ADFA-4510-missing-tooltips-code-actions. Root cause was the render path, not missing tags: code actions are children of CodeActionsMenu and were never resolvable through the registry, and the bind site read retrieveTooltipTag() while every action overrides the tooltipTag property. All 11 menu tags and all 4 dialog tags now resolve. Added unit tests in the actions and lsp/java modules."
```

Also confirm the ticket's assignee and status are correct while you are there.

---

## Self-Review

**Spec coverage.** Every design section maps to a task: unify members (Task 2, Step 5), `ActionMenu.findAction(itemId)` (Task 2, Step 4), submenu adapter parent (Task 4, Steps 3 and 6), drop the `contentDescription` fallback (Task 4, Step 4), tag and category resolution (Task 4, Steps 4-5), the two tag corrections (Task 3), the dialog tag (Task 5), both test files (Tasks 2 and 3), manual verification (Task 6). The spec's "out of scope" items are correctly absent.

**Type consistency.** `findAction(itemId: Int): ActionItem?` is defined in Task 2 Step 4 and consumed in Task 4 Step 4 with the same name and signature. `retrieveTooltipTag(isReadOnlyContext: Boolean): String` keeps its existing signature throughout. `actionMenu` is the constructor parameter name in Task 4 Steps 3, 4 and 6. `TooltipManager.showTooltip(context, anchorView, category, tag)` matches the signature at `ToolTipManager.kt:187`.

**Known gap.** Task 4's changes have no automated coverage; `ActionsListAdapter` is a private nested class requiring an `IDEEditor`. Its two ingredients are unit-tested in Task 2, and the wiring is verified manually in Task 6. Chosen deliberately over a brittle Robolectric test that would need heavy sora-editor mocking.
