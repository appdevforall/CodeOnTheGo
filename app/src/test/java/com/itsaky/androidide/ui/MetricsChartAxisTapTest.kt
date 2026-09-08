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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.NetworkUsageWatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins where the sampling-rate chooser is reached from (ADFA-5486).
 *
 * The x axis is drawn by the chart rather than being a view of its own, so the tap is recognised by
 * comparing coordinates against the plot area. That test and the axis's position have to agree:
 * they disagreed once -- the axis at the bottom, the tap band at the top -- which left the only way
 * to change the sampling rate in an empty strip at the far end of the chart from the labels the
 * gesture is named for.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsChartAxisTapTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private var taps = 0

	/** Set by [laidOutChart], for the tests that need to ask the renderer something. */
	private lateinit var attachedRenderer: NetworkUsageChartRenderer

	private fun laidOutChart(): SafeLineChart {
		val chart = SafeLineChart(context)
		// Any concrete renderer will do -- the tap band is decided by the base class, and every
		// page positions its x axis the same way.
		val renderer =
			NetworkUsageChartRenderer(
				usageProvider = {
					NetworkUsageWatcher.NetworkUsage(
						LongArray(SAMPLES) { 1_000L },
						LongArray(SAMPLES) { 500L },
					)
				},
			)
		renderer.attach(chart)
		renderer.onXAxisTap = { taps++ }
		attachedRenderer = renderer

		chart.layOutAndDraw()
		return chart
	}

	private fun tapAt(
		chart: SafeLineChart,
		y: Float,
	) {
		val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 10f, y, 0)
		chart.onChartGestureListener.onChartSingleTapped(event)
		event.recycle()
	}

	@Test
	fun `a panned viewport is what the renderer reads, not the newest window`() {
		val chart = laidOutChart()
		drawOnce(chart)

		// Zoom first: an unzoomed chart shows everything, so there is nothing a pan could move.
		chart.setVisibleXRangeMaximum(VISIBLE_WINDOW.toFloat())
		chart.moveViewToXNow(0f)
		drawOnce(chart)
		assertThat(chart.lowestVisibleX).isLessThan(10f)
		assertThat(chart.highestVisibleX).isLessThan(SAMPLES / 2f)

		// Until the user drives the viewport, the renderer says what showNewestWindow put there
		// rather than asking the chart -- so it reports the newest samples even though the chart
		// is showing the oldest.
		assertThat(attachedRenderer.visibleSampleRange(chart, SAMPLES).last).isEqualTo(SAMPLES - 1)

		val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 10f, 10f, 0)
		chart.onChartGestureListener.onChartTranslate(event, -50f, 0f)
		event.recycle()

		// A pan is the user driving the viewport just as much as a pinch. Only a pinch used to
		// count, so a pan left the renderer ranging and annotating against the wrong samples --
		// and showNewestWindow scrolled the chart back on the next tick.
		assertThat(attachedRenderer.visibleSampleRange(chart, SAMPLES).last)
			.isLessThan(SAMPLES - 1)
	}

	/** MPAndroidChart runs its viewport jobs during a draw, so a pan is not real until one. */
	private fun drawOnce(chart: SafeLineChart) {
		chart.draw(Canvas(Bitmap.createBitmap(CHART_WIDTH, CHART_HEIGHT, Bitmap.Config.ARGB_8888)))
	}

	@Test
	fun `the plot area has room for a tap to fall inside or outside it`() {
		val chart = laidOutChart()

		// Guards the other tests: on an unlaid-out chart they would all tap the same edge.
		assertThat(chart.viewPortHandler.contentBottom()).isGreaterThan(chart.viewPortHandler.contentTop())
		assertThat(chart.viewPortHandler.contentBottom()).isLessThan(CHART_HEIGHT.toFloat())
	}

	@Test
	fun `a tap below the plot, where the axis is drawn, opens the chooser`() {
		val chart = laidOutChart()

		tapAt(chart, chart.viewPortHandler.contentBottom() + 1f)

		assertThat(taps).isEqualTo(1)
	}

	@Test
	fun `a tap on the legend does not open the chooser`() {
		val chart = laidOutChart()

		// MPAndroidChart aligns the legend to the bottom by default, below the axis labels, so
		// "everything under the plot" included it -- and the legend is the one part of a chart a
		// reader expects to be tappable. Opening the rate chooser there is bad enough; picking a
		// rate in it clears every buffer, so a mis-tap costs the history being looked at.
		//
		// Robolectric measures no real text, so the legend here is a few pixels rather than the
		// ~10dp row a device draws. That is enough: the assertion is about which side of the
		// boundary the legend's own rows fall on, and the bottom row is the legend's.
		tapAt(chart, CHART_HEIGHT - 1f)

		assertThat(taps).isEqualTo(0)
	}

	@Test
	fun `the axis labels still open the chooser, with the legend excluded`() {
		val chart = laidOutChart()

		// The other half of the bound: narrowing the band must not put the rate chooser out of
		// reach. One axis label's height below the plot always stays in it.
		tapAt(chart, chart.viewPortHandler.contentBottom() + chart.xAxis.textSize / 2f)

		assertThat(taps).isEqualTo(1)
	}

	@Test
	fun `the gap the legend keeps above itself still opens the chooser`() {
		val chart = laidOutChart()
		val legend = chart.legend

		// Guards the assertion below: with no legend, or no gap, there is no strip to test.
		assertThat(legend.isEnabled).isTrue()
		assertThat(legend.mNeededHeight).isGreaterThan(0f)
		assertThat(legend.yOffset).isGreaterThan(0f)

		// Legend.calculateDimensions ends with `mNeededHeight += mYOffset`, so the offset is
		// already inside the measured height. Reserving `mNeededHeight + yOffset` counted it twice
		// and handed the legend a strip yOffset tall that nothing draws in -- taken off the bottom
		// of the one target that opens the sampling-rate chooser.
		tapAt(chart, CHART_HEIGHT - legend.mNeededHeight - legend.yOffset / 2f)

		assertThat(taps).isEqualTo(1)
	}

	@Test
	fun `a tap above the plot does not open the chooser`() {
		val chart = laidOutChart()

		// Nothing is drawn up there. Answering taps here is what made the gesture unreachable.
		tapAt(chart, chart.viewPortHandler.contentTop() - 1f)

		assertThat(taps).isEqualTo(0)
	}

	@Test
	fun `a tap inside the plot does not open the chooser`() {
		val chart = laidOutChart()

		val handler = chart.viewPortHandler
		tapAt(chart, (handler.contentTop() + handler.contentBottom()) / 2f)

		assertThat(taps).isEqualTo(0)
	}

	private companion object {
		/**
		 * Longer than the chart's visible window.
		 *
		 * It was exactly the window, and showNewestWindow returns early when the newest index is
		 * below it -- so the pan test could not tell the fix from the bug, because nothing was
		 * scrolling the viewport either way.
		 */
		const val SAMPLES = 200

		/** The renderer's own visible window, which is what it scrolls to the newest samples. */
		const val VISIBLE_WINDOW = 60
	}
}
