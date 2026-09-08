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
import androidx.test.core.app.ApplicationProvider
import com.github.mikephil.charting.listener.ChartTouchListener
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.NetworkUsageWatcher
import com.itsaky.androidide.utils.longPressHelpTimeoutMillis
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * When the chart answers a hold with help, and when it gives that help up (ADFA-5554).
 *
 * The platform reports its long press at 400ms, which is a brisk tap, so the chart waits out the
 * rest of the hold before showing anything. Two things have to be true of that wait: it happens,
 * and it is abandoned when the gesture turns into something a hold is not -- a pan, a pinch, or a
 * page being unbound underneath it.
 *
 * The help itself is a seam rather than a real tooltip. `TooltipManager` reads the docs database
 * from device storage in its static initialiser and cannot be loaded off-device, which is the same
 * reason the renderer separates deciding a help tag from showing one.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsChartHoldHelpTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private var taps = 0

	private var helps = 0

	private lateinit var renderer: NetworkUsageChartRenderer

	private fun laidOutChart(): SafeLineChart {
		val chart = SafeLineChart(context)
		// Any concrete renderer will do -- the hold is the base class's, and every page wires it
		// the same way.
		renderer =
			NetworkUsageChartRenderer(
				usageProvider = {
					NetworkUsageWatcher.NetworkUsage(
					LongArray(SAMPLES) { 1_000L },
					LongArray(SAMPLES) { 500L },
					LongArray(SAMPLES),
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
	private fun eventAt(
		y: Float,
		sincePressMillis: Long = ViewConfiguration.getLongPressTimeout().toLong(),
	): MotionEvent {
		val now = SystemClock.uptimeMillis()
		return MotionEvent.obtain(now - sincePressMillis, now, MotionEvent.ACTION_MOVE, 10f, y, 0)
	}

	/** The platform's own long press, which is where the chart's hold started counting from. */
	private fun longPressAt(
		chart: SafeLineChart,
		y: Float,
		sincePressMillis: Long = ViewConfiguration.getLongPressTimeout().toLong(),
	) {
		val event = eventAt(y, sincePressMillis)
		chart.onChartGestureListener.onChartLongPressed(event)
		event.recycle()
	}

	private fun panBy(
		chart: SafeLineChart,
		dx: Float,
	) {
		val event = eventAt(0f)
		chart.onChartGestureListener.onChartTranslate(event, dx, 0f)
		event.recycle()
	}

	private fun endGesture(
		chart: SafeLineChart,
		gesture: ChartTouchListener.ChartGesture,
	) {
		val event = eventAt(0f)
		chart.onChartGestureListener.onChartGestureEnd(event, gesture)
		event.recycle()
	}

	/** Runs the main looper forward by [millis] of virtual time. */
	private fun elapse(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)

	/**
	 * Runs what is already due on the main looper without advancing the clock.
	 *
	 * The stand-in tap is posted rather than invoked inside the chart's touch dispatch, so nothing
	 * has been tapped until the looper turns.
	 */
	private fun drain() = shadowOf(Looper.getMainLooper()).idle()

	/** The rest of the hold, after a long press reported at the platform's own timeout. */
	private fun remainderOfHold() = longPressHelpTimeoutMillis() - ViewConfiguration.getLongPressTimeout() + 50L

	/** A y inside the plot, where a hold means help for the page rather than for the axis. */
	private fun insidePlot(chart: SafeLineChart) = (chart.viewPortHandler.contentTop() + chart.viewPortHandler.contentBottom()) / 2f

	@Test
	fun `a press held past the hold shows help`() {
		val chart = laidOutChart()

		longPressAt(chart, insidePlot(chart))
		elapse(remainderOfHold())

		// The deferral is the point of ADFA-5554: the platform reports its long press at 400ms,
		// which is a brisk tap, and help at that speed is what the ticket is about.
		assertThat(helps).isEqualTo(1)
	}

	@Test
	fun `a press lifted before the hold completes shows no help`() {
		val chart = laidOutChart()

		longPressAt(chart, insidePlot(chart))
		endGesture(chart, ChartTouchListener.ChartGesture.LONG_PRESS)
		elapse(remainderOfHold())

		assertThat(helps).isEqualTo(0)
	}

	@Test
	fun `a press on the axis lifted before the hold still opens the chooser`() {
		val chart = laidOutChart()

		// The detector has already called this a long press, so it will not report the tap. The
		// stand-in is what keeps a brisk press on the axis doing what it always did.
		longPressAt(chart, chart.viewPortHandler.contentBottom() + 1f)
		endGesture(chart, ChartTouchListener.ChartGesture.LONG_PRESS)

		// Nothing has been tapped inside the dispatch itself: the tap opens a dialog, and doing
		// that mid-gesture leaves the chart's touch state part-way through one.
		assertThat(taps).isEqualTo(0)
		drain()

		assertThat(taps).isEqualTo(1)
		assertThat(helps).isEqualTo(0)
	}

	@Test
	fun `a press on the axis that becomes a pan does not open the chooser`() {
		val chart = laidOutChart()

		// A drag begins from a press the detector has already called a long press, so the
		// stand-in fired for it: panning the chart opened the sampling-rate chooser, and picking
		// a rate there clears every buffer -- the history loss the band's lower bound exists to
		// prevent, reached by another route.
		longPressAt(chart, chart.viewPortHandler.contentBottom() + 1f)
		panBy(chart, -50f)
		endGesture(chart, ChartTouchListener.ChartGesture.DRAG)
		drain()

		assertThat(taps).isEqualTo(0)
	}

	@Test
	fun `a press that becomes a pan shows no help either`() {
		val chart = laidOutChart()

		// The finger is still down and still dragging when the hold would come due, so the
		// tooltip opened over a chart the user was in the middle of panning.
		longPressAt(chart, insidePlot(chart))
		panBy(chart, -50f)
		elapse(remainderOfHold())

		assertThat(helps).isEqualTo(0)
	}

	@Test
	fun `a press that becomes a pinch shows no help`() {
		val chart = laidOutChart()

		longPressAt(chart, insidePlot(chart))
		val event = eventAt(0f)
		chart.onChartGestureListener.onChartScale(event, 1.2f, 1.2f)
		event.recycle()
		elapse(remainderOfHold())

		assertThat(helps).isEqualTo(0)
	}

	@Test
	fun `the hold is measured from the finger landing, not from when the press was reported`() {
		val chart = laidOutChart()

		// GestureDetector does not report a long press exactly getLongPressTimeout() after the
		// finger lands: below Q it adds TAP_TIMEOUT, and it caches the timeout in a static read at
		// class-load, so a lengthened accessibility touch-and-hold delay moves the buttons' hold
		// and not the detector's. Subtracting the platform timeout from the total assumed
		// otherwise, and stretched the chart's hold by however far the detector was late.
		longPressAt(chart, insidePlot(chart), sincePressMillis = LATE_REPORT_MILLIS)
		elapse(longPressHelpTimeoutMillis() - LATE_REPORT_MILLIS + 50L)

		assertThat(helps).isEqualTo(1)
	}

	private companion object {
		/** Longer than the chart's visible window, matching the axis-tap tests' fixture. */
		const val SAMPLES = 200

		/**
		 * A long press reported well after the finger landed.
		 *
		 * Comfortably past the platform timeout, so the two ways of computing the remaining hold
		 * give different answers and the test can tell them apart.
		 */
		const val LATE_REPORT_MILLIS = 700L
	}
}
