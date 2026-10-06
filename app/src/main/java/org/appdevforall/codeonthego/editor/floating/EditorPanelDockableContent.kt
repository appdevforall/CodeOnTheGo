package org.appdevforall.codeonthego.editor.floating

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Toast
import com.itsaky.androidide.R
import org.appdevforall.codeonthego.api.ActionContextProvider
import org.appdevforall.codeonthego.api.IDEApiFacade
import org.appdevforall.codeonthego.floating.model.ChromeControl
import org.appdevforall.codeonthego.floating.model.DockAction
import org.appdevforall.codeonthego.floating.model.DockableContent
import org.appdevforall.codeonthego.floating.window.FloatingWindowHost
import org.appdevforall.codeonthego.idetooltips.TooltipCategory
import org.appdevforall.codeonthego.idetooltips.TooltipManager
import org.appdevforall.codeonthego.idetooltips.TooltipTag
import org.appdevforall.codeonthego.lookup.Lookup
import org.appdevforall.codeonthego.models.Position
import org.appdevforall.codeonthego.models.Range
import org.appdevforall.codeonthego.projects.builder.BuildService
import org.appdevforall.codeonthego.ui.CodeEditorView
import org.appdevforall.codeonthego.utils.requestBuildCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
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
			// Through the shared reporter, because this copy threw the result away entirely: a
			// Stop the server refused here said nothing at all, not even to the log.
			requestBuildCancellation(builder)
		}
	}

	val isModified: Boolean
		get() = editorView?.isModified == true

	suspend fun save(): Boolean = editorView?.save() ?: false

	fun release() {
		editorView?.close()
		editorView = null
	}
}
