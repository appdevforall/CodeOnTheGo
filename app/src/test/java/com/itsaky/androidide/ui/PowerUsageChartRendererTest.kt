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
	fun `the power axis is labelled in whole watts`() {
		val (_, chart) = rendererFor(usage(temperature = longArrayOf(30_000L), power = longArrayOf(8_400_000L)))
		val axis = chart.axisRight

		assertThat(axis.valueFormatter.getFormattedValue(8.4f, axis)).isEqualTo("8W")
		assertThat(axis.valueFormatter.getFormattedValue(0f, axis)).isEqualTo("0W")
		// Without this the axis puts gridlines a fraction of a watt apart on an idle device, and
		// rounding them to whole watts prints the same label several times over.
		assertThat(axis.isGranularityEnabled).isTrue()
		assertThat(axis.granularity).isEqualTo(1f)
	}

	@Test
	fun `each axis takes the colour of the line it describes`() {
		val (_, chart) = rendererFor(usage(temperature = longArrayOf(30_000L), power = longArrayOf(1_000_000L)))

		// Two axes with unrelated units; colour is what pairs each with its series.
		assertThat(chart.axisLeft.textColor).isEqualTo(dataset(chart, 0).color)
		assertThat(chart.axisRight.textColor).isEqualTo(dataset(chart, 1).color)
		assertThat(chart.axisLeft.textColor).isNotEqualTo(chart.axisRight.textColor)
	}

	@Test
	fun `temperature is plotted in degrees and power in watts`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = longArrayOf(29_700L),
					power = longArrayOf(6_358_064L),
				),
			)

		assertThat(dataset(chart, 0).entries.last().y).isWithin(0.01f).of(29.7f)
		assertThat(dataset(chart, 1).entries.last().y).isWithin(0.001f).of(6.358064f)
	}

	@Test
	fun `power is plotted as a magnitude, whichever way the current is signed`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = longArrayOf(30_000L, 30_000L),
					// The platform signs the battery current by direction, and not every OEM signs it
					// the same way round, so both signs have to plot as spent power.
					power = longArrayOf(2_000_000L, -3_000_000L),
				),
			)

		val ys = dataset(chart, 1).entries.map { it.y }

		assertThat(ys).containsExactly(2f, 3f).inOrder()
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
	fun `each throttling level gets its own hue, green through red`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = LongArray(6) { 30_000L },
					thermal = longArrayOf(1L, 2L, 3L, 4L, 5L, 6L),
				),
			)

		assertThat(chart.backgroundSpans).hasSize(6)
		assertThat(chart.backgroundSpans.map { it.color or OPAQUE }).isEqualTo(EXPECTED_HUES)
	}

	@Test
	fun `no two levels share a colour, and the alpha does not vary`() {
		val (_, chart) =
			rendererFor(
				usage(
					temperature = LongArray(6) { 30_000L },
					thermal = longArrayOf(1L, 2L, 3L, 4L, 5L, 6L),
				),
			)

		// Hue alone ranks the levels, so a repeat would make two of them indistinguishable...
		assertThat(chart.backgroundSpans.map { it.color }.toSet()).hasSize(6)
		// ...and a varying alpha would add a second, weaker ranking that disagrees with it.
		assertThat(chart.backgroundSpans.map { it.color ushr 24 }.toSet()).hasSize(1)
	}

	@Test
	fun `the legend reports power in watts, and in milliwatts below a watt`() {
		val (_, loaded) = rendererFor(usage(temperature = longArrayOf(30_000L), power = longArrayOf(6_358_064L)))
		assertThat(dataset(loaded, 1).label).endsWith("6.4W")

		// An idle device reads 0.0W in watts, losing the value the legend exists to show.
		val (_, idle) = rendererFor(usage(temperature = longArrayOf(30_000L), power = longArrayOf(6_000L)))
		assertThat(dataset(idle, 1).label).endsWith("6mW")
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
		assertThat(charging.readout()).isNull()
	}

	@Test
	fun `the battery readout shows the level on battery power`() {
		val (renderer, _) =
			rendererFor(
				usage(temperature = longArrayOf(30_000L)),
				battery = BatteryState(levelPercent = 62, isCharging = false),
			)

		assertThat(renderer.readout()).isEqualTo("62%")
	}

	@Test
	fun `an unknown battery level shows nothing rather than a negative percentage`() {
		val (renderer, _) =
			rendererFor(
				usage(temperature = longArrayOf(30_000L)),
				battery = BatteryState.UNKNOWN,
			)

		assertThat(renderer.readout()).isNull()
	}

	private fun laidOut(chart: SafeLineChart) {
		chart.measure(
			View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
		)
		chart.layout(0, 0, WIDTH, HEIGHT)
		// The scroll to the newest samples is a job that only runs during a draw pass.
		chart.draw(Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)))
	}

	@Test
	fun `the power axis starts at zero, never below it`() {
		val (_, chart) =
			rendererFor(usage(temperature = LongArray(SAMPLES) { 30_000L }, power = LongArray(SAMPLES) { 7_000_000L }))
		laidOut(chart)

		// Unpinned, the chart's own 10% bottom padding prints a negative watt label under a series
		// plotted as a magnitude precisely so it could never read as negative power.
		assertThat(chart.axisRight.axisMinimum).isEqualTo(0f)
	}

	@Test
	fun `the temperature axis ignores the buffer's unsampled slots`() {
		// A real reading only in the newest slots; the rest of the buffer has never been written.
		// Unsampled now means UNAVAILABLE rather than zero -- the watcher fills its buffers with
		// it, because a zero-filled prefix plotted a flat 0 C line and presented it as a reading.
		val temperature = LongArray(SAMPLES) { PowerUsageWatcher.UNAVAILABLE }
		for (index in SAMPLES - 10 until SAMPLES) {
			temperature[index] = 30_000L
		}
		val (_, chart) = rendererFor(usage(temperature = temperature))
		laidOut(chart)

		// Ranged over the unsampled slots the 30C band is squeezed into a corner of the plot.
		assertThat(chart.axisLeft.axisMinimum).isGreaterThan(20f)
		assertThat(chart.axisLeft.axisMaximum).isLessThan(40f)
	}

	@Test
	fun `a genuine zero degrees is a reading and is ranged over`() {
		// The half the old workaround got wrong. Ignoring the unsampled prefix used to be done by
		// discarding every zero, which also discarded a real freezing-battery sample -- so a phone
		// left in a car overnight charted its own temperature as absent.
		val temperature = LongArray(SAMPLES) { PowerUsageWatcher.UNAVAILABLE }
		for (index in SAMPLES - 10 until SAMPLES) {
			temperature[index] = 0L
		}
		val (_, chart) = rendererFor(usage(temperature = temperature))
		laidOut(chart)

		// The axis has to include it rather than falling back to its default band.
		assertThat(chart.axisLeft.axisMinimum).isAtMost(0f)
	}

	@Test
	fun `the battery readout gets room, and gives it back`() {
		val (renderer, chart) = rendererFor(usage(temperature = LongArray(SAMPLES) { 30_000L }))
		laidOut(chart)
		val unreserved = chart.viewPortHandler.contentTop()

		// The readout is anchored over the chart's top-right corner, where the right axis prints
		// its topmost label; at a 2.0 font scale it grew down into the plot and hid that label.
		renderer.reserveTopSpace(READOUT_HEIGHT_PX)
		laidOut(chart)
		assertThat(chart.viewPortHandler.contentTop()).isGreaterThan(unreserved)

		// Off the power page the readout is hidden, and the plot should have the room back.
		renderer.reserveTopSpace(0f)
		laidOut(chart)
		assertThat(chart.viewPortHandler.contentTop()).isEqualTo(unreserved)
	}

	@Test
	fun `only one axis rules the plot`() {
		val (_, chart) = rendererFor(usage(temperature = LongArray(SAMPLES) { 30_000L }))
		laidOut(chart)

		// Both axes drew grid lines at their own pitch, so the plot carried two interleaved sets
		// of horizontal rules -- nine of them, including a pair eight pixels apart. Only the
		// labelled axis should rule the plot; the left axis is enabled for its labels alone.
		assertThat(chart.axisLeft.isDrawGridLinesEnabled).isFalse()
		assertThat(chart.axisRight.isDrawGridLinesEnabled).isTrue()
	}

	@Test
	fun `the temperature axis does not repeat a label`() {
		val (_, chart) = rendererFor(usage(temperature = LongArray(SAMPLES) { 30_000L }))
		laidOut(chart)

		// Ranged over a few degrees and formatted without decimals, a finer pitch prints
		// "29C, 30C, 30C, 31C".
		assertThat(chart.axisLeft.granularity).isEqualTo(1f)
		assertThat(chart.axisLeft.isGranularityEnabled).isTrue()
	}

	@Test
	fun `a new sample updates the existing series rather than replacing them`() {
		val (renderer, chart) = rendererFor(usage(temperature = LongArray(SAMPLES) { 30_000L }))
		val before = dataset(chart, 0)

		renderer.onUsageChanged(usage(temperature = LongArray(SAMPLES) { 31_000L }))

		// Rebuilding allocated two datasets and 2 * MAX_USAGE_ENTRIES entries every tick, on the
		// UI thread, and threw away the sample it had just been handed.
		assertThat(dataset(chart, 0)).isSameInstanceAs(before)
		assertThat(before.entries.last().y).isEqualTo(31f)
	}

	@Test
	fun `a series that no longer matches the sample is rebuilt`() {
		val (renderer, chart) = rendererFor(usage(temperature = LongArray(SAMPLES) { 30_000L }))

		// The buffer grows to its full length over the first minutes of a session, so an
		// in-place update has to notice when the shape it is writing into is the wrong one.
		renderer.onUsageChanged(usage(temperature = LongArray(SAMPLES + 1) { 31_000L }))

		assertThat(dataset(chart, 0).entryCount).isEqualTo(SAMPLES + 1)
	}

	@Test
	fun `an unreadable temperature falls back to a plausible span`() {
		val (_, chart) =
			rendererFor(usage(temperature = LongArray(SAMPLES) { PowerUsageWatcher.UNAVAILABLE }))
		laidOut(chart)

		// Nothing readable, so a sensible range beats one computed from placeholder zeros.
		assertThat(chart.axisLeft.axisMinimum).isLessThan(chart.axisLeft.axisMaximum)
		assertThat(chart.axisLeft.axisMaximum).isAtMost(40f)
	}

	private companion object {
		const val WIDTH = 720
		const val HEIGHT = 400
		const val SAMPLES = 200

		/** A readout two lines tall, which is roughly what a 2.0 font scale gives. */
		const val READOUT_HEIGHT_PX = 80f

		const val OPAQUE = 0xFF000000.toInt()

		/** The palette ADFA-5499 specifies: green, cyan, yellow, orange, rust, red. */
		val EXPECTED_HUES =
			listOf(0xFF4CAF50, 0xFF00BCD4, 0xFFFDD835, 0xFFFB8C00, 0xFFB7410E, 0xFFE53935)
				.map { it.toInt() }
	}
}
