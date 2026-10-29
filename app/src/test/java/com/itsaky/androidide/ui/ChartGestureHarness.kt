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
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.github.mikephil.charting.listener.ChartTouchListener
import com.itsaky.androidide.utils.NetworkUsageWatcher
import com.itsaky.androidide.utils.longPressHelpTimeoutMillis
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** Samples a harnessed chart is given; more than a window's worth, so a pan has somewhere to go. */
const val HARNESS_SAMPLES = 200

/**
 * A laid-out chart with a renderer attached, and the gestures to drive it.
 *
 * The two classes that test the chart's hold -- when help fires, and what happens to a hold when
 * the gesture or the chart goes away -- had grown byte-identical copies of all of this, 74 lines
 * each, down to the comments. One of the copies had a `panBy` nothing called.
 *
 * The gesture helpers all go through `chart.onChartGestureListener` rather than dispatching real
 * touches, because that is the seam the renderer actually listens on: MPAndroidChart's own
 * detector is what decides a press is a long press, and standing that up would be testing the
 * library rather than the renderer.
 */
class ChartGestureHarness(
	private val context: Context,
) {
	/** Times [MetricsChartRenderer.onXAxisTap] fired -- the sampling-rate chooser opening. */
	var taps = 0
		private set

	/** Times the renderer asked for help to be shown. */
	var helps = 0
		private set

	lateinit var renderer: NetworkUsageChartRenderer
		private set

	fun laidOutChart(): SafeLineChart {
		val chart = SafeLineChart(context)
		// Any concrete renderer will do -- the hold is the base class's, and every page wires it
		// the same way.
		renderer =
			NetworkUsageChartRenderer(
				usageProvider = {
					NetworkUsageWatcher.NetworkUsage(
						LongArray(HARNESS_SAMPLES) { 1_000L },
						LongArray(HARNESS_SAMPLES) { 500L },
						LongArray(HARNESS_SAMPLES),
					)
				},
			)
		renderer.attach(chart)
		renderer.onXAxisTap = { taps++ }
		renderer.showHelp = { _, _, _ -> helps++ }

		chart.layOutAndDraw()
		return chart
	}

	/**
	 * An event whose finger landed [sincePressMillis] ago.
	 *
	 * The down time is what the chart measures its remaining hold from, so it has to be real here.
	 * Defaults to the platform's long-press timeout, which is when a detector on a current device
	 * reports one.
	 */
	fun eventAt(
		y: Float,
		sincePressMillis: Long = ViewConfiguration.getLongPressTimeout().toLong(),
	): MotionEvent {
		val now = SystemClock.uptimeMillis()
		return MotionEvent.obtain(now - sincePressMillis, now, MotionEvent.ACTION_MOVE, 10f, y, 0)
	}

	/** The platform's own long press, which is where the chart's hold started counting from. */
	fun longPressAt(
		chart: SafeLineChart,
		y: Float,
		sincePressMillis: Long = ViewConfiguration.getLongPressTimeout().toLong(),
	) {
		val event = eventAt(y, sincePressMillis)
		chart.onChartGestureListener.onChartLongPressed(event)
		event.recycle()
	}

	fun panBy(
		chart: SafeLineChart,
		dx: Float,
	) {
		val event = eventAt(0f)
		chart.onChartGestureListener.onChartTranslate(event, dx, 0f)
		event.recycle()
	}

	fun scaleBy(
		chart: SafeLineChart,
		factor: Float,
	) {
		val event = eventAt(0f)
		chart.onChartGestureListener.onChartScale(event, factor, factor)
		event.recycle()
	}

	fun endGesture(
		chart: SafeLineChart,
		gesture: ChartTouchListener.ChartGesture,
	) {
		val event = eventAt(0f)
		chart.onChartGestureListener.onChartGestureEnd(event, gesture)
		event.recycle()
	}

	/**
	 * The end of a gesture an ancestor took away.
	 *
	 * ChartTouchListener.endAction is reached from ACTION_CANCEL as well as ACTION_UP, with the
	 * original event and with mLastGesture untouched, so this is what the listener actually sees
	 * when the reveal layout, the bottom sheet or the pager claims the stream mid-press.
	 */
	fun cancelGesture(
		chart: SafeLineChart,
		gesture: ChartTouchListener.ChartGesture,
	) {
		val now = SystemClock.uptimeMillis()
		val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 10f, 0f, 0)
		chart.onChartGestureListener.onChartGestureEnd(event, gesture)
		event.recycle()
	}

	/** A y on the axis band, where a tap opens the sampling-rate chooser. */
	fun onAxisBand(chart: SafeLineChart) = chart.viewPortHandler.contentBottom() + 1f

	/** A y inside the plot, where a hold means help for the page rather than for the axis. */
	fun insidePlot(chart: SafeLineChart) = (chart.viewPortHandler.contentTop() + chart.viewPortHandler.contentBottom()) / 2f
}

/** Runs the main looper forward by [millis] of virtual time. */
fun elapse(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)

/**
 * Runs what is already due on the main looper without advancing the clock.
 *
 * The stand-in tap and the stand-in click are posted rather than run inside the touch dispatch, so
 * nothing has been tapped until the looper turns.
 */
fun drain() = shadowOf(Looper.getMainLooper()).idle()

/** The rest of the hold, after a long press reported at the platform's own timeout. */
fun remainderOfHold() = longPressHelpTimeoutMillis() - ViewConfiguration.getLongPressTimeout() + 50L
