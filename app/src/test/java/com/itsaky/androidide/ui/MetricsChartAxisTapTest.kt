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

		// Without a layout pass the plot area has no extent, so every coordinate is on its edge.
		chart.measure(
			View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
		)
		chart.layout(0, 0, WIDTH, HEIGHT)
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
	fun `the plot area has room for a tap to fall inside or outside it`() {
		val chart = laidOutChart()

		// Guards the other tests: on an unlaid-out chart they would all tap the same edge.
		assertThat(chart.viewPortHandler.contentBottom()).isGreaterThan(chart.viewPortHandler.contentTop())
		assertThat(chart.viewPortHandler.contentBottom()).isLessThan(HEIGHT.toFloat())
	}

	@Test
	fun `a tap below the plot, where the axis is drawn, opens the chooser`() {
		val chart = laidOutChart()

		tapAt(chart, chart.viewPortHandler.contentBottom() + 1f)

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
		const val WIDTH = 720
		const val HEIGHT = 400
		const val SAMPLES = 60
	}
}
