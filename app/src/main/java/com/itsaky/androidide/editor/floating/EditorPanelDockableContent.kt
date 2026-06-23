package com.itsaky.androidide.editor.floating

import android.content.Context
import android.view.View
import com.itsaky.androidide.R
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.floating.model.DockAction
import com.itsaky.androidide.floating.model.DockableContent
import com.itsaky.androidide.floating.window.FloatingWindowHost
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.ui.CodeEditorView
import java.io.File
import com.itsaky.androidide.resources.R as ResR

/**
 * Adapts an editor file tab to [DockableContent] for a floating window. Builds a [CodeEditorView]
 * against the window's long-lived themed context so it outlives the editor activity. Content
 * continuity across dock/undock is preserved by saving to disk on each transition; this view simply
 * re-opens the saved file.
 */
class EditorPanelDockableContent(
	val file: File,
) : DockableContent {
	override val id: String = file.absolutePath
	override val title: String = file.name

	private var editorView: CodeEditorView? = null
	private var dockActions: List<DockAction> = emptyList()

	override val actions: List<DockAction>
		get() = dockActions

	override fun onCreateView(
		context: Context,
		host: FloatingWindowHost,
	): View {
		val view = CodeEditorView(context, file, Range(Position(0, 0), Position(0, 0)))
		editorView = view
		dockActions =
			listOf(
				DockAction(
					id = "ide.floating.save",
					label = "Save",
					iconRes = ResR.drawable.ic_save,
					confirmIconRes = R.drawable.ic_check,
					onLongPress = { anchor ->
						TooltipManager.showTooltip(context, anchor, TooltipCategory.CATEGORY_IDE, TooltipTag.EDITOR_TOOLBAR_QUICK_SAVE)
					},
				) {
					view.save()
					true
				},
				DockAction(
					id = "ide.floating.run",
					label = "Run",
					iconRes = ResR.drawable.ic_run,
					onLongPress = { anchor ->
						TooltipManager.showTooltip(context, anchor, TooltipCategory.CATEGORY_IDE, TooltipTag.EDITOR_TOOLBAR_QUICK_RUN)
					},
				) {
					IDEApiFacade.runApp()
					false
				},
			)
		view.onEditorSelected()
		return view
	}

	suspend fun save(): Boolean = editorView?.save() ?: false

	fun release() {
		editorView?.close()
		editorView = null
	}
}
