package com.itsaky.androidide.editor.floating

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Toast
import com.itsaky.androidide.R
import com.itsaky.androidide.api.ActionContextProvider
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.floating.model.ChromeControl
import com.itsaky.androidide.floating.model.DockAction
import com.itsaky.androidide.floating.model.DockableContent
import com.itsaky.androidide.floating.window.FloatingWindowHost
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.ui.CodeEditorView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
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
	private val running = MutableStateFlow(false)

	override val actions: List<DockAction>
		get() = dockActions

	override val onChromeControlLongPress: (ChromeControl, View) -> Unit =
		ChromeControlTooltips.handler

	override val busy: StateFlow<Boolean> = running

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
					activeIconRes = ResR.drawable.ic_stop_daemons,
					active = running,
					onLongPress = { anchor ->
						TooltipManager.showTooltip(context, anchor, TooltipCategory.CATEGORY_IDE, TooltipTag.EDITOR_TOOLBAR_QUICK_RUN)
					},
				) {
					if (running.value) {
						cancelBuild()
						false
					} else {
						running.value = true
						try {
							val result = IDEApiFacade.runApp()
							withContext(Dispatchers.Main) {
								bringIdeToFront()
								if (!result.success) {
									Toast.makeText(context.applicationContext, result.message, Toast.LENGTH_LONG).show()
								}
							}
							result.success
						} finally {
							running.value = false
						}
					}
				},
			)
		view.onEditorSelected()
		return view
	}

	private fun bringIdeToFront() {
		val activity = ActionContextProvider.getActivity() ?: return
		activity.startActivity(
			Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
		)
	}

	private fun cancelBuild() {
		val builder = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
		if (builder?.isToolingServerStarted() == true) {
			builder.cancelCurrentBuild()
		}
	}

	suspend fun save(): Boolean = editorView?.save() ?: false

	fun release() {
		editorView?.close()
		editorView = null
	}
}
