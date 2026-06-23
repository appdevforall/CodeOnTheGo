

package com.itsaky.androidide.floating.window

import android.content.Context
import kotlin.math.roundToInt

/** Computes sensible initial bounds for a newly undocked window, cascading multiple windows. */
object InitialBounds {
	private const val WIDTH_FRACTION = 0.72f
	private const val HEIGHT_FRACTION = 0.6f
	private const val CASCADE_STEP_DP = 28f
	private const val CASCADE_WRAP = 6

	fun cascaded(
		context: Context,
		index: Int,
	): WindowBounds {
		val metrics = context.resources.displayMetrics
		val width = (metrics.widthPixels * WIDTH_FRACTION).roundToInt()
		val height = (metrics.heightPixels * HEIGHT_FRACTION).roundToInt()
		val step = (CASCADE_STEP_DP * metrics.density).roundToInt() * (index % CASCADE_WRAP)
		val baseX = (metrics.widthPixels - width) / 2
		val baseY = (metrics.heightPixels - height) / 2
		return WindowBounds(
			x = (baseX + step).coerceAtLeast(0),
			y = (baseY + step).coerceAtLeast(0),
			width = width,
			height = height,
		)
	}
}
