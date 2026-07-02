

package com.itsaky.androidide.floating.window

/** Position and size of a floating window, in absolute screen pixels. */
data class WindowBounds(
	val x: Int,
	val y: Int,
	val width: Int,
	val height: Int,
)

/** The visual mode of a floating window. */
enum class WindowMode {
	NORMAL,
	MINIMIZED,
	MAXIMIZED,
}

/**
 * Immutable state of a single floating window, owned by
 * [com.itsaky.androidide.floating.model.DockingManager] and reconciled into a live overlay by the
 * floating window service.
 *
 * [restoreBounds] remembers the [NORMAL]-mode bounds to return to after leaving [MAXIMIZED] or
 * [MINIMIZED].
 */
data class FloatingWindowState(
	val bounds: WindowBounds,
	val mode: WindowMode = WindowMode.NORMAL,
	val restoreBounds: WindowBounds = bounds,
)
