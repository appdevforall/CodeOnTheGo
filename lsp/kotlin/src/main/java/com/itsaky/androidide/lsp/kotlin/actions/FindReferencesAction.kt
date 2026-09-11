package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.hasRequiredData
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.editor.api.ILspEditor
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * Lists every usage of the declaration at the caret, or of whatever the reference at the caret names.
 *
 * Mirrors the Java action: the real work is the editor's own cancellable request, so this only has to
 * start it.
 */
class FindReferencesAction : BaseKotlinCodeAction() {
	override var titleTextRes: Int = R.string.action_find_references
	override val id: String = ID
	override var label: String = ""
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_KT_FIND_REFS

	// execAction only starts the editor's own background request, so it must not be moved off the UI
	// thread. Nothing here or in prepare() touches the project lock, the index, or an analysis session -
	// but super.prepare() -> BaseKotlinCodeAction.prepare -> isKotlinFile() does stat the file
	// (Files.exists + Files.isDirectory) on the UI thread. Pre-existing, shared by every Kotlin/Java
	// code action, and out of scope here.
	override var requiresUIThread: Boolean = true

	override fun prepare(data: ActionData) {
		super.prepare(data)

		// Deliberately not conditioned on what the caret sits on: answering that needs PSI and the
		// project read lock, and prepare() runs on the UI thread. A caret that names nothing therefore
		// shows the item and flashes "no references", exactly as go-to-definition does.
		if (!visible || !data.hasRequiredData(CodeEditor::class.java)) {
			markInvisible()
			return
		}
	}

	override suspend fun execAction(data: ActionData): Any {
		val editor = data[CodeEditor::class.java] ?: return false
		return (editor as? ILspEditor)?.findReferences() ?: false
	}

	companion object {
		const val ID = "ide.editor.lsp.kt.findReferences"
	}
}
