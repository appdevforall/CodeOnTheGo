
package com.itsaky.androidide.editor.floating

import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.floating.model.DockingEvent
import com.itsaky.androidide.floating.model.DockingManager
import com.itsaky.androidide.floating.permission.OverlayPermission
import com.itsaky.androidide.floating.service.FloatingTabService
import com.itsaky.androidide.floating.window.InitialBounds
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.launch

/**
 * Bridges the editor activity to the floating-window system: turns an editor file tab into a
 * floating window and back.
 *
 * - Undock: persist the docked panel, close its docked tab, then float a fresh panel built against
 *   the service's window context (so it survives this activity being destroyed).
 * - Redock/Close ([DockingManager.events]): persist and release the floating panel, and for redock,
 *   re-open the file as a docked tab.
 */
class IdeFloatingTabController(
	private val activity: EditorHandlerActivity,
) {
	private var undockCounter = 0

	fun start() {
		activity.lifecycleScope.launch {
			DockingManager.events.collect(::onEvent)
		}
	}

	fun undock(fileIndex: Int) {
		if (!OverlayPermission.canDrawOverlays(activity)) {
			activity.startActivity(OverlayPermission.requestIntent(activity))
			return
		}

		val panel = activity.getEditorAtIndex(fileIndex) ?: return
		val file = panel.file ?: return

		activity.lifecycleScope.launch {
			val wasModified = panel.isModified
			val saved = panel.save()
			if (wasModified && !saved) {
				Toast.makeText(activity, activity.getString(R.string.msg_undock_save_failed, file.name), Toast.LENGTH_LONG).show()
				return@launch
			}
			panel.markAsSaved()
			activity.closeFile(fileIndex) {}
			DockingManager.undock(
				EditorPanelDockableContent(file),
				InitialBounds.cascaded(activity, undockCounter++),
			)
			FloatingTabService.ensureRunning(activity.applicationContext)
		}
	}

	fun floatPluginTab(
		tabId: String,
		title: String,
		remove: () -> Unit,
	) {
		if (!OverlayPermission.canDrawOverlays(activity)) {
			activity.startActivity(OverlayPermission.requestIntent(activity))
			return
		}
		remove()
		DockingManager.undock(
			PluginTabDockableContent(tabId, title),
			InitialBounds.cascaded(activity, undockCounter++),
		)
		FloatingTabService.ensureRunning(activity.applicationContext)
	}

	private fun onEvent(event: DockingEvent) {
		when (val content = event.content) {
			is EditorPanelDockableContent ->
				activity.lifecycleScope.launch {
					content.save()
					content.release()
					if (event is DockingEvent.Redock) {
						bringIdeToFront()
						activity.openFile(content.file, null)
					}
				}

			is PluginTabDockableContent ->
				if (event is DockingEvent.Redock) {
					bringIdeToFront()
					activity.selectPluginTabById(content.tabId)
				}
		}
	}

	private fun bringIdeToFront() {
		if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
			return
		}
		activity.startActivity(
			Intent(activity, activity.javaClass)
				.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
		)
	}
}
