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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What happens to a hold in progress when the gesture or the chart under it goes away (ADFA-5554).
 *
 * A hold is a timer on the main thread's queue, not state on the view, so it outlives whatever
 * started it: a second finger landing, the page being rebound, the renderer letting the chart go.
 * Each of those has to reach the timer, and none of them can once the listener holding it has been
 * replaced.
 *
 * Split from [MetricsChartHoldHelpTest] only because these three are about teardown rather than
 * timing. An earlier version of this comment blamed a Robolectric interaction: the suite was
 * killing the test JVM as cases were added, and splitting appeared to help. It was heap --
 * Robolectric builds a sandbox per distinct `@Config` and `:app` had outgrown the 1g in the root
 * build file. The split is kept because it reads better, not because it fixes anything.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsChartGestureTeardownTest {
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

	private fun endGesture(
		chart: SafeLineChart,
		gesture: ChartTouchListener.ChartGesture,
	) = harness.endGesture(chart, gesture)

	private fun cancelGesture(
		chart: SafeLineChart,
		gesture: ChartTouchListener.ChartGesture,
	) = harness.cancelGesture(chart, gesture)

	private fun onAxisBand(chart: SafeLineChart) = harness.onAxisBand(chart)

	private fun insidePlot(chart: SafeLineChart) = harness.insidePlot(chart)

	@Test
	fun `a gesture an ancestor cancels does not stand in for a tap`() {
		val chart = laidOutChart()

		longPressAt(chart, onAxisBand(chart))
		cancelGesture(chart, ChartTouchListener.ChartGesture.LONG_PRESS)
		drain()

		// A cancel is not a lift. The chooser this would open clears every sample buffer, so a
		// press the sheet or the pager steals mid-gesture must not be read as a finger lifting
		// early -- which is exactly what it looked like, because endAction reports the same
		// LONG_PRESS for both.
		assertThat(taps).isEqualTo(0)
	}

	@Test
	fun `detaching takes back a stand-in tap that has been posted`() {
		val chart = laidOutChart()

		longPressAt(chart, onAxisBand(chart))
		endGesture(chart, ChartTouchListener.ChartGesture.LONG_PRESS)
		// The tap is on the looper now, not yet run. Letting the chart go in that window used to
		// leave it there: it opened the chooser, and cleared every buffer, for a chart this
		// renderer no longer had.
		renderer.detach()
		drain()

		assertThat(taps).isEqualTo(0)
	}

	@Test
	fun `a second finger gives up the gesture, even without a move`() {
		val chart = laidOutChart()

		// This tests what the renderer does when the second pointer is reported, by calling the
		// callback directly. It does NOT test that the callback fires for the gesture that matters,
		// and it cannot: a ViewGroup rewrites ACTION_POINTER_DOWN to ACTION_MOVE for the child
		// already holding the first pointer, so on the realistic undock -- one finger on an arrow,
		// one on the strip -- SafeLineChart.onTouchEvent never sees a pointer-down at all. Driving
		// this from MetricsCarouselLayout.dispatchTouchEvent, which does see it, is its own change.
		//
		// The carousel undocks on a two-finger tap, and that starts as a press like any other.
		// MPAndroidChart cannot report it -- ACTION_POINTER_DOWN never touches its mLastGesture --
		// so the gesture still ends labelled LONG_PRESS and the stand-in tap fired, opening the
		// sampling-rate chooser. Picking a rate there clears every buffer, so one gesture both
		// undocked the strip and threw away the history it was showing.
		longPressAt(chart, chart.viewPortHandler.contentBottom() + 1f)
		chart.onSecondPointerDown?.invoke()
		endGesture(chart, ChartTouchListener.ChartGesture.LONG_PRESS)
		elapse(remainderOfHold())
		drain()

		assertThat(taps).isEqualTo(0)
		assertThat(helps).isEqualTo(0)
	}

	@Test
	fun `re-attaching the same chart leaves no second listener behind`() {
		val chart = laidOutChart()

		// attach() used to skip the teardown when handed the chart it already had, so configure()
		// installed a second gesture listener while the first stayed queued with a hold nothing
		// could reach. A rebind of a bound holder does exactly that.
		longPressAt(chart, insidePlot(chart))
		renderer.attach(chart)
		elapse(remainderOfHold())

		assertThat(helps).isEqualTo(0)
	}

	@Test
	fun `detaching cancels a hold already counting down`() {
		val chart = laidOutChart()

		// The timer is on the main thread's queue, not on the chart, so unbinding the page does
		// not reach it. Worse, the rebind installs a fresh listener whose own pending hold is
		// null -- so nobody could have cancelled the old one, and it fired the outgoing page's
		// help over whatever replaced it.
		longPressAt(chart, insidePlot(chart))
		renderer.detach()
		elapse(remainderOfHold())

		assertThat(helps).isEqualTo(0)
	}

	private companion object {
		const val SAMPLES = 200
	}
}
