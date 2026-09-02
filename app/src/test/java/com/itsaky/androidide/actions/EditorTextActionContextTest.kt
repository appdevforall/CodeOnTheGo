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
import org.junit.Test

/**
 * `ActionsRegistry` is a process-lifetime singleton and EDITOR_TEXT_ACTIONS is deliberately never
 * cleared, so language-server actions survive - see `EditorActivityActions.clear`. An action in that
 * bucket that stores its `Context` therefore keeps one editor activity alive for the whole process,
 * which is what `ShowTooltipAction` did.
 *
 * Reflection rather than a heap assertion: it names the mistake directly, costs nothing, and covers
 * every action in the bucket instead of the one that happened to leak. Add new EDITOR_TEXT_ACTIONS
 * entries here.
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
		)

	@Test
	fun givenTheEditorTextActions_whenInspected_thenNoneDeclaresAContextField() {
		val offenders =
			editorTextActions
				.flatMap { action ->
					action.declaredFields
						.filter { Context::class.java.isAssignableFrom(it.type) }
						.map { "${action.simpleName}.${it.name}" }
				}

		assertThat(offenders).isEmpty()
	}
}
