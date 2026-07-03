

package com.itsaky.androidide.floating.window

import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

/**
 * Builds the [WindowManager.LayoutParams] for a floating overlay window.
 *
 * The [focusable] flag is the crux of in-overlay text editing: a soft keyboard can only target a
 * window that does NOT carry [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE]. The window is kept
 * non-focusable (touch passes through to apps behind) until the editor is tapped, then flipped to
 * focusable so the IME can attach.
 */
object OverlayLayoutParams {

	private val overlayType: Int =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
		} else {
			@Suppress("DEPRECATION")
			WindowManager.LayoutParams.TYPE_PHONE
		}

	fun create(state: FloatingWindowState, focusable: Boolean): WindowManager.LayoutParams =
		WindowManager.LayoutParams(
			state.bounds.width,
			state.bounds.height,
			overlayType,
			flagsFor(focusable),
			PixelFormat.TRANSLUCENT,
		).apply {
			gravity = Gravity.TOP or Gravity.START
			x = state.bounds.x
			y = state.bounds.y
			softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
		}

	fun flagsFor(focusable: Boolean): Int {
		val common =
			WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
				WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
				WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
		return if (focusable) {
			common
		} else {
			common or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
		}
	}
}
