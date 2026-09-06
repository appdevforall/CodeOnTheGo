/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.YAxis
import org.slf4j.LoggerFactory

/**
 * A [LineChart] that guards its [onDraw] against the MPAndroidChart axis-rendering race.
 *
 * MPAndroidChart is not thread-safe: [com.github.mikephil.charting.renderer.AxisRenderer.computeAxisValues]
 * writes `mEntryCount` and reallocates the `mEntries` array in two separate statements. When the view is
 * drawn from more than one thread at once, a reader can observe the new `mEntryCount` while `mEntries` is
 * still the old (shorter) array, throwing an [IndexOutOfBoundsException] from the label renderer.
 *
 * This happens in AndroidIDE because Sentry Session Replay (the SDK feature we use to report to
 * GlitchTip) records the screen by drawing the view
 * hierarchy on a background thread, which races the main-thread updates of the memory-usage chart. The
 * chart is a non-critical diagnostic view, so dropping the occasional frame is preferable to crashing the
 * whole IDE. The next `invalidate()` recovers cleanly.
 */
class SafeLineChart : LineChart {
	constructor(context: Context) : super(context)
	constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
	constructor(
		context: Context,
		attrs: AttributeSet?,
		defStyleAttr: Int,
	) : super(context, attrs, defStyleAttr)

	companion object {
		private val log = LoggerFactory.getLogger(SafeLineChart::class.java)
	}

	private var skippedFrames = 0L

	/**
	 * Bands painted behind the data, in x-value coordinates (ADFA-5499's thermal shading).
	 *
	 * Drawn here rather than by the caller because the chart owns the transformer that maps an
	 * x value to a pixel, and that mapping changes with every zoom, pan and layout.
	 */
	var backgroundSpans: List<Span> = emptyList()
		set(value) {
			field = value
			invalidate()
		}

	/**
	 * A shaded range of the x axis.
	 *
	 * @property startX First x value covered, inclusive.
	 * @property endX Last x value covered, inclusive.
	 * @property color Fill colour, expected to carry its own alpha.
	 */
	data class Span(
		val startX: Float,
		val endX: Float,
		val color: Int,
	)

	private val spanPaint = Paint(Paint.ANTI_ALIAS_FLAG)

	/** Reused by [drawBackgroundSpans]: two (x, y) pairs, transformed in place. */
	private val spanPoints = FloatArray(4)

	/**
	 * Draws the spans immediately after the grid background, which is an opaque fill of the plot: a
	 * span painted before [onDraw] delegates upwards is covered by it and never reaches the screen.
	 * Landing here also puts the shading under the grid lines and the data, where it belongs.
	 */
	override fun drawGridBackground(canvas: Canvas) {
		super.drawGridBackground(canvas)
		drawBackgroundSpans(canvas)
	}

	private fun drawBackgroundSpans(canvas: Canvas) {
		if (backgroundSpans.isEmpty()) {
			return
		}

		val content = viewPortHandler.contentRect
		val transformer = getTransformer(YAxis.AxisDependency.LEFT) ?: return

		backgroundSpans.forEach { span ->
			// A reused buffer through pointValuesToPixel, not two getPixelForValues calls: those
			// hand back pooled MPPointD instances that have to be recycled, and this runs inside
			// onDraw for every span on every frame of every pan and zoom.
			spanPoints[0] = span.startX
			spanPoints[1] = 0f
			spanPoints[2] = span.endX
			spanPoints[3] = 0f
			transformer.pointValuesToPixel(spanPoints)
			val left = spanPoints[0]
			val right = spanPoints[2]
			// A span scrolled out of view still maps to a pixel, so clip to the plot.
			val clippedLeft = left.coerceAtLeast(content.left)
			val clippedRight = right.coerceAtMost(content.right)
			if (clippedRight <= clippedLeft) {
				return@forEach
			}

			spanPaint.color = span.color
			canvas.drawRect(clippedLeft, content.top, clippedRight, content.bottom, spanPaint)
		}
	}

	override fun onDraw(canvas: Canvas) {
		try {
			super.onDraw(canvas)
		} catch (e: IndexOutOfBoundsException) {
			// Transient race in MPAndroidChart's axis renderer (see class doc). Skip this frame.
			// Only log occasionally to avoid flooding logcat, since onDraw runs every frame.
			if (skippedFrames++ % 60L == 0L) {
				log.warn("Skipped {} chart frame(s) due to a transient axis-rendering race", skippedFrames, e)
			}
		}
	}
}
