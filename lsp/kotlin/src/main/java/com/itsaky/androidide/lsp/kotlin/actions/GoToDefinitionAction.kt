package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.hasRequiredData
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.editor.api.ILspEditor
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * Navigates from the reference at the caret to the declaration it resolves to.
 *
 * Mirrors the Java action: the real work is the editor's own cancellable request, so this only has
 * to start it.
 */
class GoToDefinitionAction : BaseKotlinCodeAction() {
	override var titleTextRes: Int = R.string.action_goto_definition
	override val id: String = ID
	override var label: String = ""
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_KT_GOTO_DEF

	// execAction only starts the editor's own background request, so it must not be moved off the
	// UI thread. Nothing here or in prepare() touches the project lock, the index or the disk.
	override var requiresUIThread: Boolean = true

	override fun prepare(data: ActionData) {
		super.prepare(data)

		if (!visible || !data.hasRequiredData(CodeEditor::class.java)) {
			markInvisible()
			return
		}
	}

	override suspend fun execAction(data: ActionData): Any {
		val editor = data[CodeEditor::class.java] ?: return false
		return (editor as? ILspEditor)?.findDefinition() ?: false
	}

	companion object {
		const val ID = "ide.editor.lsp.kt.gotoDefinition"
	}
}
