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
import androidx.test.core.app.ApplicationProvider
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.LineDataSet
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.PowerUsageWatcher
import com.itsaky.androidide.utils.PowerUsageWatcher.BatteryState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the three decisions ADFA-5499 was scoped around: temperature and power get an axis each
 * because they share no unit, throttling is shaded rather than plotted because the platform reports
 * an ordinal and not a temperature, and the battery level is hidden while charging.
 */
@RunWith(RobolectricTestRunner::class)
class PowerUsageChartRendererTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private fun usage(
		temperature: LongArray,
		power: LongArray = LongArray(temperature.size),
		thermal: LongArray = LongArray(temperature.size),
	) = PowerUsageWatcher.PowerUsage(temperature, power, thermal)

	private fun rendererFor(
		usage: PowerUsageWatcher.PowerUsage,
		battery: BatteryState = BatteryState(levelPercent = 80, isCharging = false),
	): Pair<PowerUsageChartRenderer, SafeLineChart> {
		val chart = SafeLineChart(context)
		val renderer =
			PowerUsageChartRenderer(
				usageProvider = { usage },
				batteryProvider = { battery },
			)
		renderer.attach(chart)
		return renderer to chart
	}

	private fun dataset(
		chart: SafeLineChart,
		index: Int,
	) = chart.data.getDataSetByIndex(index) as LineDataSet

	@Test
	fun `temperature and power are plotted against separate axes`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = longArrayOf(30_000L, 31_000L),
					power = longArrayOf(1_000_000L, 4_000_000L),
				),
			)

		assertThat(chart.data.dataSetCount).isEqualTo(2)
		// Degrees and milliwatts differ by orders of magnitude; a series left on the default axis
		// would be drawn against labels that do not describe it.
		assertThat(dataset(chart, 0).axisDependency).isEqualTo(YAxis.AxisDependency.LEFT)
		assertThat(dataset(chart, 1).axisDependency).isEqualTo(YAxis.AxisDependency.RIGHT)
		assertThat(chart.axisLeft.isEnabled).isTrue()
		assertThat(chart.axisRight.isEnabled).isTrue()
	}

	@Test
	fun `temperature is plotted in degrees and power in milliwatts`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = longArrayOf(29_700L),
					power = longArrayOf(6_358_064L),
				),
			)

		assertThat(dataset(chart, 0).entries.last().y).isWithin(0.01f).of(29.7f)
		assertThat(dataset(chart, 1).entries.last().y).isWithin(0.01f).of(6358.064f)
	}

	@Test
	fun `power is plotted as a magnitude, so charging does not dip below zero`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = longArrayOf(30_000L, 30_000L),
					// The battery current reverses while charging.
					power = longArrayOf(2_000_000L, -3_000_000L),
				),
			)

		val ys = dataset(chart, 1).entries.map { it.y }

		assertThat(ys).containsExactly(2000f, 3000f).inOrder()
		assertThat(ys.none { it < 0f }).isTrue()
	}

	@Test
	fun `an unavailable reading plots at zero rather than at Long MIN_VALUE`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = longArrayOf(PowerUsageWatcher.UNAVAILABLE, 30_000L),
					power = longArrayOf(PowerUsageWatcher.UNAVAILABLE, 1_000_000L),
				),
			)

		// Plotted as MIN_VALUE the point would put the axis range into the billions and flatten
		// every real reading onto one line.
		assertThat(dataset(chart, 0).entries.first().y).isEqualTo(0f)
		assertThat(dataset(chart, 1).entries.first().y).isEqualTo(0f)
	}

	@Test
	fun `the legend says n slash a for a reading the device does not provide`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = longArrayOf(PowerUsageWatcher.UNAVAILABLE),
					power = longArrayOf(PowerUsageWatcher.UNAVAILABLE),
				),
			)

		assertThat(dataset(chart, 0).label).endsWith("n/a")
		assertThat(dataset(chart, 1).label).endsWith("n/a")
	}

	@Test
	fun `a run of one throttling level becomes one shaded span`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = LongArray(6) { 30_000L },
					thermal = longArrayOf(0L, 0L, 2L, 2L, 2L, 0L),
				),
			)

		assertThat(chart.backgroundSpans).hasSize(1)
		val span = chart.backgroundSpans.single()
		// Samples 2..4, each covering its own cell rather than just its centre point.
		assertThat(span.startX).isEqualTo(1.5f)
		assertThat(span.endX).isEqualTo(4.5f)
	}

	@Test
	fun `a single throttled sample still gets a span with width`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = LongArray(3) { 30_000L },
					thermal = longArrayOf(0L, 3L, 0L),
				),
			)

		// Drawn from centre to centre this span would be zero pixels wide and never appear.
		val span = chart.backgroundSpans.single()
		assertThat(span.endX - span.startX).isEqualTo(1f)
	}

	@Test
	fun `adjacent runs leave no unshaded gap between them`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = LongArray(4) { 30_000L },
					thermal = longArrayOf(1L, 1L, 3L, 3L),
				),
			)

		val (first, second) = chart.backgroundSpans
		assertThat(first.endX).isEqualTo(second.startX)
	}

	@Test
	fun `adjacent levels shade separately, and deeper for the worse one`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = LongArray(4) { 30_000L },
					thermal = longArrayOf(1L, 1L, 4L, 4L),
				),
			)

		assertThat(chart.backgroundSpans).hasSize(2)
		val (light, critical) = chart.backgroundSpans
		// The bands read as a gradient of concern rather than as unrelated categories.
		assertThat(critical.color ushr 24).isGreaterThan(light.color ushr 24)
	}

	@Test
	fun `no shading where there is nothing to say`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = LongArray(4) { 30_000L },
					// Not throttled, then a device that reports no level at all.
					thermal = longArrayOf(0L, 0L, -1L, -1L),
				),
			)

		// Shading everything would say nothing.
		assertThat(chart.backgroundSpans).isEmpty()
	}

	@Test
	fun `the battery readout is hidden while charging`() {
		val (charging, _) =
			rendererFor(
				usage(temperature = longArrayOf(30_000L)),
				battery = BatteryState(levelPercent = 62, isCharging = true),
			)

		// A level climbing while the chart is about power being spent reads as a contradiction.
		assertThat(charging.batteryReadout()).isNull()
	}

	@Test
	fun `the battery readout shows the level on battery power`() {
		val (renderer, _) =
			rendererFor(
				usage(temperature = longArrayOf(30_000L)),
				battery = BatteryState(levelPercent = 62, isCharging = false),
			)

		assertThat(renderer.batteryReadout()).isEqualTo("62%")
	}

	@Test
	fun `an unknown battery level shows nothing rather than a negative percentage`() {
		val (renderer, _) =
			rendererFor(
				usage(temperature = longArrayOf(30_000L)),
				battery = BatteryState.UNKNOWN,
			)

		assertThat(renderer.batteryReadout()).isNull()
	}
}
