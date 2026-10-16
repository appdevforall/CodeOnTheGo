package com.itsaky.androidide.actions

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.actions.editor.CopyAction
import com.itsaky.androidide.actions.editor.CutAction
import com.itsaky.androidide.actions.editor.ExpandSelectionAction
import com.itsaky.androidide.actions.editor.LongSelectAction
import com.itsaky.androidide.actions.editor.PasteAction
import com.itsaky.androidide.actions.editor.SelectAllAction
import com.itsaky.androidide.actions.file.FormatCodeAction
import com.itsaky.androidide.actions.file.ShowTooltipAction
import com.itsaky.androidide.actions.locations.CodeActionsMenu
import org.junit.Test
import java.lang.reflect.Field

/**
 * `ActionsRegistry` is a process-lifetime singleton and EDITOR_TEXT_ACTIONS is deliberately never
 * cleared, so language-server actions survive - see `EditorActivityActions.clear`. An action in that
 * bucket that stores its `Context` therefore keeps one editor activity alive for the whole process,
 * which is what `ShowTooltipAction` did.
 *
 * Reflection rather than a heap assertion: it names the mistake directly and costs nothing.
 *
 * What it does not cover, so nobody reads more into a green run than is there:
 * - the list is hand-maintained, so a newly registered action is uncovered until it is added here;
 * - the LSP actions that `LSPEditorActions` nests under [CodeActionsMenu.children] at runtime;
 * - a `Context` reached indirectly - held by a companion object, or captured by a lambda stored in
 *   a field - since those live on synthetic classes, not on a field of the action typed `Context`.
 */
class EditorTextActionContextTest {
	private val editorTextActions =
		listOf(
			ExpandSelectionAction::class.java,
			SelectAllAction::class.java,
			LongSelectAction::class.java,
			CutAction::class.java,
			CopyAction::class.java,
			PasteAction::class.java,
			FormatCodeAction::class.java,
			ShowTooltipAction::class.java,
			// A process-lifetime `object`, so the most dangerous entry in the bucket rather than one
			// to leave out.
			CodeActionsMenu::class.java,
		)

	@Test
	fun givenTheEditorTextActions_whenInspected_thenNoneDeclaresAContextField() {
		val offenders =
			editorTextActions
				.flatMap { action ->
					action
						.fieldsIncludingInherited()
						.filter { Context::class.java.isAssignableFrom(it.type) }
						.map { "${action.simpleName} (from ${it.declaringClass.simpleName}).${it.name}" }
				}

		assertThat(offenders).isEmpty()
	}

	@Test
	fun givenAContextFieldOnASuperclass_whenInspected_thenItIsStillFound() {
		// Pins the reason this walks the hierarchy: with declaredFields alone this finds nothing, so
		// an action inheriting a Context would have passed the test above.
		val found =
			ActionInheritingAContext::class.java
				.fieldsIncludingInherited()
				.filter { Context::class.java.isAssignableFrom(it.type) }

		assertThat(found).isNotEmpty()
	}

	private open class BaseHoldingAContext {
		@Suppress("unused")
		private val context: Context? = null
	}

	private class ActionInheritingAContext : BaseHoldingAContext()

	/**
	 * Declared fields of this class and of every superclass. [Class.getDeclaredFields] stops at the
	 * class itself, so a Context held by a shared base class - the more likely place for one to hide
	 * than in each action - would pass unnoticed.
	 */
	private fun Class<*>.fieldsIncludingInherited(): List<Field> =
		generateSequence(this) { it.superclass }
			.flatMap { it.declaredFields.asSequence() }
			.toList()
}
