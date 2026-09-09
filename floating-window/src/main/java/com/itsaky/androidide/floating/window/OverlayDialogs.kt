package com.itsaky.androidide.floating.window

import android.app.Dialog
import com.itsaky.androidide.floating.model.DockingManager
import com.itsaky.androidide.floating.permission.OverlayPermission

/**
 * Shows a host [Dialog] above any floating overlay windows.
 *
 * Floating windows are [android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY], which
 * the platform always stacks above an activity's own windows. An activity dialog therefore renders
 * *behind* them, and because an overlay is a separate window the dialog's modality does not extend
 * to it: tapping the overlay never surfaces the dialog. Raising the dialog to the same window type
 * puts it back on top, where its modality is visible.
 *
 * The platform attaches no app token to a system-type window, so a raised dialog outlives the
 * activity that created it. Callers must dismiss it themselves when the activity goes away.
 */
object OverlayDialogs {
	/**
	 * Shows [dialog], raising it above the floating windows when any are open. Leaves an ordinary
	 * activity dialog untouched when nothing is floating.
	 *
	 * @return `true` when the dialog was raised, and so carries no app token and outlives the
	 *   activity. Only a raised dialog needs the caller to dismiss it; an untouched one is an
	 *   ordinary activity dialog the platform tears down.
	 */
	fun show(dialog: Dialog): Boolean {
		val raise =
			DockingManager.windows.value.isNotEmpty() &&
				OverlayPermission.canDrawOverlays(dialog.context)
		if (raise) {
			dialog.window?.setType(OverlayLayoutParams.overlayType)
		}
		dialog.show()
		return raise
	}
}
