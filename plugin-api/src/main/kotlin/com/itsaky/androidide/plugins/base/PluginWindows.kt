package com.itsaky.androidide.plugins.base

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast

/**
 * Shows plugin dialogs
 *
 * A plugin fragment undocked into a floating window runs against a window context created for
 * [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]. The platform requires every window added
 * through such a context to carry that same type and rejects anything else, so both
 * `AlertDialog.Builder(requireContext()).show()` (a `TYPE_APPLICATION` window) and
 * `Toast.makeText(requireContext(), ...)` (a `TYPE_TOAST` window) throw `IllegalArgumentException`
 * as soon as the plugin is undocked. Neither can be corrected by the IDE on the plugin's behalf --
 * `Window` exposes no theme attribute for its type, and a toast is posted by the system against
 * whatever context built it -- so route both through here.
 *
 * Both entry points keep the ordinary activity-backed behaviour for a docked plugin, whose context
 * is the IDE activity, so a single call site is correct in either state.
 */
object PluginWindows {
	/** Applies the window type [dialog]'s context requires, then shows it. */
	@JvmStatic
	fun showDialog(dialog: Dialog) {
		prepareDialog(dialog)
		dialog.show()
	}

	/**
	 * Retypes [dialog]'s window as an overlay when its context is not activity-backed, so that it
	 * can be shown from a floating window. Build the dialog with `create()` rather than showing it
	 * from its builder, then pass it here.
	 */
	@JvmStatic
	fun prepareDialog(dialog: Dialog) {
		val context = dialog.context
		if (context.findActivity() != null || !Settings.canDrawOverlays(context)) {
			return
		}
		dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
	}

	/**
	 *A toast's window is added by the system
	 * against the context that built it, and its type is fixed, so this builds it against the
	 * application context -- which imposes no window type of its own.
	 */
	@JvmStatic
	@JvmOverloads
	fun showToast(
		context: Context,
		text: CharSequence,
		duration: Int = Toast.LENGTH_SHORT,
	) {
		Toast.makeText(context.applicationContext, text, duration).show()
	}

	private fun Context.findActivity(): Activity? {
		var current: Context? = this
		while (current is ContextWrapper) {
			if (current is Activity) {
				return current
			}
			current = current.baseContext
		}
		return null
	}
}
