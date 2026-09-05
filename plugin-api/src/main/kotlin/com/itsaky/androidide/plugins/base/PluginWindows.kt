package com.itsaky.androidide.plugins.base

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * Shows plugin dialogs and toasts from a context that may belong to a floating overlay window.
 *
 * A plugin fragment undocked into a floating window runs against a window context created for
 * [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]. The platform requires every window added
 * through it to carry that same type and rejects anything else, so both
 * `AlertDialog.Builder(requireContext()).show()` (a `TYPE_APPLICATION` window) and
 * `Toast.makeText(requireContext(), ...)` (a `TYPE_TOAST` window) throw `IllegalArgumentException`
 * as soon as the plugin is undocked. Neither can be corrected by the IDE on the plugin's behalf --
 * `Window` exposes no theme attribute for its type, and a toast is posted by the system against
 * whatever context built it -- so route both through here.
 *
 * Both entry points keep the ordinary activity-backed behaviour for a docked plugin, whose context
 * is the IDE activity, so a single call site is correct in either state.
 *
 * A dialog raised to the overlay type carries no app token, so the platform will not tear it down
 * with the window that opened it. The IDE dismisses what it raised when the floating window goes
 * away, but a plugin that keeps a dialog across other lifecycle events should still dismiss it in
 * `onDestroyView`.
 */
object PluginWindows {
	/** Dialogs retyped as overlays, weakly held so a dismissed one is still collectable. */
	private val overlayDialogs = mutableListOf<WeakReference<Dialog>>()

	/**
	 * Applies the window type [dialog]'s context requires and shows it.
	 *
	 * @return `false` when the dialog cannot be shown: its context is not activity-backed and the
	 *   IDE may not draw overlays, so showing it would throw. Nothing is shown in that case, and
	 *   the caller should degrade to something non-fatal rather than assume the dialog is up.
	 */
	@JvmStatic
	fun showDialog(dialog: Dialog): Boolean {
		if (!prepareDialog(dialog)) {
			return false
		}
		dialog.show()
		return true
	}

	/**
	 * Retypes [dialog]'s window as an overlay when its context is not activity-backed, so that it
	 * can be shown from a floating window. Build the dialog with `create()` rather than showing it
	 * from its builder, then pass it here.
	 *
	 * @return `true` when [dialog] is safe to show, whether or not it needed retyping.
	 */
	@JvmStatic
	fun prepareDialog(dialog: Dialog): Boolean {
		val context = dialog.context
		if (context.findActivity() != null) {
			return true
		}
		if (!Settings.canDrawOverlays(context)) {
			return false
		}
		dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
		track(dialog)
		return true
	}

	/**
	 * Shows a toast that survives a floating window. A toast's window is added by the system
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

	/**
	 * Dismisses the overlay dialogs opened against [context], for the IDE to call when the window
	 * that owns that context is torn down.
	 *
	 * Without this an overlay-typed dialog outlives its floating window: it holds no app token, so
	 * closing or docking the window leaves it drawing over whatever the user does next, with no way
	 * to remove it if the plugin made it uncancellable.
	 */
	@InternalPluginApi
	@JvmStatic
	fun dismissOverlayDialogsFor(context: Context) {
		val doomed = mutableListOf<Dialog>()
		synchronized(overlayDialogs) {
			val entries = overlayDialogs.iterator()
			while (entries.hasNext()) {
				val dialog = entries.next().get()
				if (dialog == null) {
					entries.remove()
				} else if (dialog.context.wraps(context)) {
					entries.remove()
					doomed += dialog
				}
			}
		}
		doomed.forEach { dialog ->
			if (dialog.isShowing) {
				runCatching { dialog.dismiss() }
			}
		}
	}

	private fun track(dialog: Dialog) {
		synchronized(overlayDialogs) {
			overlayDialogs.removeAll { it.get() == null }
			overlayDialogs += WeakReference(dialog)
		}
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

	/** Whether [other] is this context or one this context wraps. */
	private fun Context.wraps(other: Context): Boolean {
		var current: Context? = this
		while (current != null) {
			if (current === other) {
				return true
			}
			current = (current as? ContextWrapper)?.baseContext
		}
		return false
	}
}
