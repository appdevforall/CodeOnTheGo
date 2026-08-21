/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.itsaky.androidide.lsp.java.actions

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.idetooltips.TooltipTag
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
				"ide.editor.lsp.java.surroundWithTryCatch" to TooltipTag.EDITOR_CODE_ACTIONS_TRY_CATCH,
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
		assertThat(actualTags).containsExactlyEntriesIn(expected)
	}

	/** Guards a Java action drifting onto a Kotlin tag or some unrelated namespace. */
	@Test
	fun `no java code action borrows a non java code action tag`() {
		actualTags.forEach { (id, tag) ->
			if (tag.isEmpty()) return@forEach
			assertWithMessage("$id uses tag '$tag' outside the java code actions namespace")
				.that(tag.startsWith("editor.codeactions.") && !tag.startsWith("editor.codeactions.kotlin."))
				.isTrue()
		}
	}
}
