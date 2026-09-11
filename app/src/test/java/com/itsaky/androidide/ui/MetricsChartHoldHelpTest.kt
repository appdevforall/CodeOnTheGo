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

import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import com.github.mikephil.charting.listener.ChartTouchListener
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.longPressHelpTimeoutMillis
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
	private val harness = ChartGestureHarness(ApplicationProvider.getApplicationContext())

	private val taps get() = harness.taps

	private val helps get() = harness.helps

	private val renderer get() = harness.renderer

	private fun laidOutChart() = harness.laidOutChart()

	private fun longPressAt(
		chart: SafeLineChart,
		y: Float,
		sincePressMillis: Long = ViewConfiguration.getLongPressTimeout().toLong(),
	) = harness.longPressAt(chart, y, sincePressMillis)

	private fun panBy(
		chart: SafeLineChart,
		dx: Float,
	) = harness.panBy(chart, dx)

	private fun scaleBy(
		chart: SafeLineChart,
		factor: Float,
	) = harness.scaleBy(chart, factor)

	private fun endGesture(
		chart: SafeLineChart,
		gesture: ChartTouchListener.ChartGesture,
	) = harness.endGesture(chart, gesture)

	private fun insidePlot(chart: SafeLineChart) = harness.insidePlot(chart)

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
		scaleBy(chart, 1.2f)
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
