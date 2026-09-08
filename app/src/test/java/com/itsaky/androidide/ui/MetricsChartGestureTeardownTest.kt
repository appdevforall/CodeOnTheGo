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
					NetworkUsageWatcher.NetworkUsage(LongArray(SAMPLES) { 1_000L }, LongArray(SAMPLES) { 500L })
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
	fun `a second finger gives up the gesture, even without a move`() {
		val chart = laidOutChart()

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
