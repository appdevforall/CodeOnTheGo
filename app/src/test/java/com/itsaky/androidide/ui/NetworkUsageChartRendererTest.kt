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
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.LineDataSet
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.NetworkUsageWatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.log10

/**
 * Pins the two axis decisions ADFA-5489 was scoped around: values are log10, and zero is floored
 * via `log10(bytes + 1)` so an idle IDE plots a continuous line at 0 instead of negative infinity.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkUsageChartRendererTest {
	private companion object {
		/** Longer than the visible window, so the start of the history scrolls off screen. */
		const val SAMPLE_COUNT = 200
	}

	private val context = ApplicationProvider.getApplicationContext<Context>()

	// No sample times: these tests are about what the chart draws, and the chart asks only how long
	// ago a sample was. Stated rather than defaulted, because the same emptiness in production
	// silently blanks the CSV's network columns.
	private fun usage(
		received: LongArray,
		transmitted: LongArray = received,
	) = NetworkUsageWatcher.NetworkUsage(received, transmitted, LongArray(received.size))

	private fun rendererFor(usage: NetworkUsageWatcher.NetworkUsage): Pair<NetworkUsageChartRenderer, SafeLineChart> {
		val chart = SafeLineChart(context)
		val renderer = NetworkUsageChartRenderer(usageProvider = { usage })
		renderer.attach(chart)
		return renderer to chart
	}

	private fun dataset(
		chart: SafeLineChart,
		index: Int,
	) = chart.data.getDataSetByIndex(index) as LineDataSet

	@Test
	fun `plots log10 of the byte count`() {
		val (_, chart) = rendererFor(usage(longArrayOf(0L, 9L, 99L, 999L)))

		val ys = dataset(chart, 0).entries.map { it.y }

		// log10(n + 1): 0 -> 0, 9 -> 1, 99 -> 2, 999 -> 3. Exact decades, so the floor is visible.
		assertThat(ys).containsExactly(0f, 1f, 2f, 3f).inOrder()
	}

	@Test
	fun `zero bytes plots at zero rather than negative infinity`() {
		val (_, chart) = rendererFor(usage(LongArray(30) { 0L }))

		val ys = dataset(chart, 0).entries.map { it.y }

		assertThat(ys.none { it.isInfinite() || it.isNaN() }).isTrue()
		assertThat(ys.toSet()).containsExactly(0f)
	}

	@Test
	fun `a megabyte burst stays on scale with surrounding chatter`() {
		val bytes = longArrayOf(0L, 512L, 2L * 1024 * 1024, 256L)
		val (_, chart) = rendererFor(usage(bytes))

		val ys = dataset(chart, 0).entries.map { it.y }

		// The point of the log axis: a 2MB burst is ~6.3 while 512B is ~2.7, so the small values
		// stay legible instead of being flattened onto the baseline.
		assertThat(ys[2]).isWithin(0.01f).of(log10(2.0 * 1024 * 1024 + 1).toFloat())
		assertThat(ys[1]).isGreaterThan(2f)
		assertThat(ys[2] - ys[1]).isLessThan(4f)
	}

	private fun laidOut(chart: SafeLineChart) = chart.layOutAndDraw()

	@Test
	fun `a rebuild after layout leaves the bounds and the transform in step`() {
		val chart = SafeLineChart(context)
		var samples = LongArray(SAMPLE_COUNT) { 500L }
		val renderer = NetworkUsageChartRenderer(usageProvider = { usage(samples) })
		renderer.attach(chart)
		laidOut(chart)

		// Rebuild after the layout, and assert without drawing again: a draw recomputes the
		// transform on its own, which is what made the first version of this test pass with the
		// bug still in place.
		samples = LongArray(SAMPLE_COUNT) { 900_000L }
		renderer.rebuild()

		// Setting axisMinimum and axisMaximum only stores them; notifyDataSetChanged is what turns
		// them into a value-to-pixel transform.
		val ceiling = chart.axisRight.axisMaximum
		val pixel = chart.getPixelForValues(0f, ceiling, YAxis.AxisDependency.RIGHT)

		assertThat(pixel.y.toFloat()).isWithin(1f).of(chart.viewPortHandler.contentTop())
	}

	@Test
	fun `a tick keeps the bounds and the transform in step`() {
		val chart = SafeLineChart(context)
		var samples = LongArray(SAMPLE_COUNT) { 500L }
		val renderer = NetworkUsageChartRenderer(usageProvider = { usage(samples) })
		renderer.attach(chart)
		laidOut(chart)

		// A burst raises the ceiling. The per-tick path had the same ordering bug as the rebuild,
		// and it is the one that runs once a second.
		samples = LongArray(SAMPLE_COUNT) { 900_000L }
		renderer.onUsageChanged(usage(samples))

		val ceiling = chart.axisRight.axisMaximum
		val pixel = chart.getPixelForValues(0f, ceiling, YAxis.AxisDependency.RIGHT)

		assertThat(pixel.y.toFloat()).isWithin(1f).of(chart.viewPortHandler.contentTop())
	}

	@Test
	fun `the axis is scaled to what is on screen, not to the whole buffer`() {
		// A one-off gigabyte burst near the start of a long history, then quiet chatter.
		val samples = LongArray(SAMPLE_COUNT) { 500L }
		samples[0] = 1_000_000_000L

		val chart = SafeLineChart(context)
		val renderer = NetworkUsageChartRenderer(usageProvider = { usage(samples) })
		renderer.attach(chart)
		laidOut(chart)
		// A second pass, now that the chart has a viewport to report.
		renderer.rebuild()

		// Scaled to the burst the axis would reach 9 decades and flatten the 500 B chatter onto the
		// baseline for the rest of the session -- the opposite of what the log axis is for.
		assertThat(chart.axisRight.axisMaximum).isLessThan(4f)
	}

	@Test
	fun `a burst still on screen does raise the axis`() {
		// Guards the test above: it must not pass by ignoring bursts altogether.
		val samples = LongArray(SAMPLE_COUNT) { 500L }
		samples[SAMPLE_COUNT - 1] = 1_000_000_000L

		val chart = SafeLineChart(context)
		val renderer = NetworkUsageChartRenderer(usageProvider = { usage(samples) })
		renderer.attach(chart)
		laidOut(chart)
		renderer.rebuild()

		assertThat(chart.axisRight.axisMaximum).isAtLeast(9f)
	}

	@Test
	fun `received and transmitted are separate series`() {
		val (_, chart) =
			rendererFor(
				usage(
					received = longArrayOf(0L, 999L),
					transmitted = longArrayOf(0L, 9L),
				),
			)

		assertThat(chart.data.dataSetCount).isEqualTo(2)
		assertThat(dataset(chart, 0).entries.last().y).isEqualTo(3f)
		assertThat(dataset(chart, 1).entries.last().y).isEqualTo(1f)
	}

	@Test
	fun `the legend reports a rate, so a slower sampling rate does not overstate it`() {
		val chart = SafeLineChart(context)
		// 10 kB in a five-second interval is 2 kB/s, not 10 kB/s.
		val renderer =
			NetworkUsageChartRenderer(
				usageProvider = { usage(longArrayOf(0L, 10_000L)) },
				sampleInterval = { 5_000L },
			)
		renderer.attach(chart)

		// Undivided, choosing "Every 5s" in the rate chooser overstated throughput fivefold.
		assertThat(dataset(chart, 0).label).endsWith("2.0 kB/s")
	}

	@Test
	fun `the legend reports the latest sample in byte units`() {
		val (_, chart) = rendererFor(usage(longArrayOf(0L, 2_000L)))

		// Rendered from the raw byte count, not from the logarithm, and in decimal units so that
		// the log10 axis labels come out as clean decades.
		assertThat(dataset(chart, 0).label).endsWith("2.0 kB/s")
	}

	@Test
	fun `onUsageChanged updates entries in place without replacing the datasets`() {
		val chart = SafeLineChart(context)
		var current = usage(longArrayOf(0L, 9L))
		val renderer = NetworkUsageChartRenderer(usageProvider = { current })
		renderer.attach(chart)

		val datasetBefore = dataset(chart, 0)
		val entryBefore = datasetBefore.entries.last()

		current = usage(longArrayOf(0L, 999L))
		renderer.onUsageChanged(current)

		assertThat(dataset(chart, 0)).isSameInstanceAs(datasetBefore)
		assertThat(datasetBefore.entries.last()).isSameInstanceAs(entryBefore)
		assertThat(entryBefore.y).isEqualTo(3f)
	}

	@Test
	fun `onUsageChanged rebuilds when the sample count changes`() {
		val chart = SafeLineChart(context)
		var current = usage(longArrayOf(0L, 9L))
		val renderer = NetworkUsageChartRenderer(usageProvider = { current })
		renderer.attach(chart)

		assertThat(dataset(chart, 0).entryCount).isEqualTo(2)

		current = usage(longArrayOf(0L, 9L, 99L))
		renderer.onUsageChanged(current)

		assertThat(dataset(chart, 0).entryCount).isEqualTo(3)
	}

	@Test
	fun `attach after detach renders the history into the new chart`() {
		val current = usage(longArrayOf(0L, 99L))
		val (renderer, _) = rendererFor(current)
		renderer.detach()

		val rebound = SafeLineChart(context)
		renderer.attach(rebound)

		assertThat(dataset(rebound, 0).entries.last().y).isEqualTo(2f)
	}

	@Test
	fun `axis labels are whole units with no decimal place`() {
		val (_, chart) = rendererFor(usage(longArrayOf(0L, 10_000_000L)))
		val formatter = chart.axisRight.valueFormatter

		// Gridlines sit on whole decades, so the mantissa is exact.
		assertThat(formatter.getFormattedValue(0f, chart.axisRight)).isEqualTo("0 B")
		assertThat(formatter.getFormattedValue(1f, chart.axisRight)).isEqualTo("10 B")
		assertThat(formatter.getFormattedValue(3f, chart.axisRight)).isEqualTo("1 kB")
		assertThat(formatter.getFormattedValue(4f, chart.axisRight)).isEqualTo("10 kB")
		assertThat(formatter.getFormattedValue(6f, chart.axisRight)).isEqualTo("1 MB")
	}

	@Test
	fun `the series are scaled against the labelled axis`() {
		val (_, chart) = rendererFor(usage(longArrayOf(0L, 100L)))

		// The right axis is the one carrying the labels and the pinned range. A dataset left on the
		// default LEFT dependency is drawn against the auto-ranged left axis, so the line lands
		// somewhere the labels do not describe -- which is invisible to an assertion on the axis
		// alone, and was only caught on a device.
		assertThat(dataset(chart, 0).axisDependency).isEqualTo(YAxis.AxisDependency.RIGHT)
		assertThat(dataset(chart, 1).axisDependency).isEqualTo(YAxis.AxisDependency.RIGHT)
	}

	@Test
	fun `an idle chart keeps zero on the baseline`() {
		// Every sample zero. Left to itself the chart pads around a degenerate range and floats the
		// flat line up the middle of the plot instead of resting it on the axis minimum.
		val (_, chart) = rendererFor(usage(LongArray(30) { 0L }))

		assertThat(chart.axisRight.axisMinimum).isEqualTo(0f)
		assertThat(chart.axisRight.axisMaximum).isEqualTo(3f)
	}

	@Test
	fun `the axis grows to whole decades around the peak`() {
		// 2 MB peak -> log10 is ~6.3, so the axis tops out at the 10 MB decade.
		val (_, chart) = rendererFor(usage(longArrayOf(0L, 2_000_000L)))

		assertThat(chart.axisRight.axisMinimum).isEqualTo(0f)
		assertThat(chart.axisRight.axisMaximum).isEqualTo(7f)
	}

	@Test
	fun `the axis follows the peak across both series`() {
		val chart = SafeLineChart(context)
		var current = usage(longArrayOf(0L, 100L))
		val renderer = NetworkUsageChartRenderer(usageProvider = { current })
		renderer.attach(chart)

		assertThat(chart.axisRight.axisMaximum).isEqualTo(3f)

		// A burst on the transmitted series alone must still lift the axis.
		current = usage(received = longArrayOf(0L, 100L), transmitted = longArrayOf(0L, 500_000L))
		renderer.onUsageChanged(current)

		assertThat(chart.axisRight.axisMaximum).isEqualTo(6f)
	}

	@Test
	fun `onUsageChanged after detach is a no-op`() {
		val current = usage(longArrayOf(0L, 99L))
		val (renderer, _) = rendererFor(current)
		renderer.detach()

		// A recycled carousel page must not keep the renderer writing into a dead view.
		renderer.onUsageChanged(current)
	}
}
