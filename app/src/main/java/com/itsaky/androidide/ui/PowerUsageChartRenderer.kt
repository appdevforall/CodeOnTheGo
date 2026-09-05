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

import android.graphics.Color
import androidx.annotation.UiThread
import androidx.core.graphics.ColorUtils
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.itsaky.androidide.R
import com.itsaky.androidide.utils.MetricsAnnotationStore
import com.itsaky.androidide.utils.PowerUsageWatcher
import com.itsaky.androidide.utils.PowerUsageWatcher.PowerUsage
import com.itsaky.androidide.utils.resolveAttr
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Renders [PowerUsageWatcher] samples: battery temperature against power draw (ADFA-5499).
 *
 * The only page with two value axes. Degrees and milliwatts differ in unit and by orders of
 * magnitude, so temperature takes the left axis and power the right. Both series therefore have to
 * declare which axis they belong to -- a dataset left on the default would be drawn against an axis
 * whose labels do not describe it, which is a bug this codebase has already shipped once.
 *
 * Thermal throttling is shown as background shading rather than as a line: the platform reports an
 * ordinal level, not a temperature, so plotting it against degrees would invent a scale. The level
 * is sampled alongside the readings, so a shaded band is simply a run of equal levels.
 */
class PowerUsageChartRenderer(
	private val usageProvider: () -> PowerUsage,
	private val batteryProvider: () -> PowerUsageWatcher.BatteryState,
	annotations: MetricsAnnotationStore? = null,
	sampleIntervalMillis: () -> Long = { PowerUsageWatcher.DEFAULT_UPDATE_INTERVAL },
) : MetricsChartRenderer(
		sampleIntervalMillis = sampleIntervalMillis,
		annotations = annotations,
	) {
	@UiThread
	override fun rebuild() {
		val chart = this.chart ?: return
		val usage = usageProvider()
		val context = chart.context

		val datasets =
			arrayOf(
				series(
					values = usage.temperatureMilliCelsius,
					label = context.getString(R.string.metrics_power_temperature),
					lineColor = TEMPERATURE_COLOR,
					axis = YAxis.AxisDependency.LEFT,
					transform = ::milliCelsiusToCelsius,
				),
				series(
					values = usage.powerMicroWatts,
					label = context.getString(R.string.metrics_power_draw),
					lineColor = POWER_COLOR,
					axis = YAxis.AxisDependency.RIGHT,
					transform = ::microWattsToMilliWatts,
				),
			)

		setData(chart, datasets)
		applyThermalShading(chart, usage)
	}

	/**
	 * Redraws from a fresh sample. Rebuilds rather than mutating in place: this chart samples
	 * relatively slowly and has two short series, so the saving is not worth a second code path
	 * that can disagree with the first.
	 */
	@UiThread
	fun onUsageChanged(usage: PowerUsage) {
		chart ?: return
		rebuild()
	}

	/**
	 * Paints a band behind the chart for each stretch of throttling, deepening with the level.
	 *
	 * Unthrottled and unknown stretches are left unpainted: shading everything would say nothing.
	 */
	private fun applyThermalShading(
		chart: SafeLineChart,
		usage: PowerUsage,
	) {
		val levels = usage.thermalStatus
		val spans = mutableListOf<SafeLineChart.Span>()

		var index = 0
		while (index < levels.size) {
			val level = levels[index].toInt()
			var end = index
			while (end + 1 < levels.size && levels[end + 1].toInt() == level) {
				end++
			}

			shadeFor(chart, level)?.let { color ->
				// Half a sample either side, so each sample covers its own cell: a single-sample
				// spike would otherwise have zero width and never be drawn, and two adjacent runs
				// would leave a sample-wide gap between them.
				spans += SafeLineChart.Span(index - HALF_SAMPLE, end + HALF_SAMPLE, color)
			}
			index = end + 1
		}

		chart.backgroundSpans = spans
	}

	/**
	 * The shade for a throttling level, or `null` where there is nothing to say.
	 *
	 * Alpha rises with severity so the bands read as a gradient of concern rather than as separate
	 * categories, and stays low enough throughout that the plotted lines remain the foreground.
	 */
	private fun shadeFor(
		chart: SafeLineChart,
		level: Int,
	): Int? {
		val alpha =
			when (level) {
				THERMAL_LIGHT -> 24
				THERMAL_MODERATE -> 40
				THERMAL_SEVERE -> 64
				THERMAL_CRITICAL -> 88
				THERMAL_EMERGENCY, THERMAL_SHUTDOWN -> 112
				else -> return null
			}

		val base = chart.context.resolveAttr(R.attr.colorError)
		return ColorUtils.setAlphaComponent(base, alpha)
	}

	private fun series(
		values: LongArray,
		label: String,
		lineColor: Int,
		axis: YAxis.AxisDependency,
		transform: (Long) -> Float,
	): LineDataSet =
		LineDataSet(
			values.mapIndexed { index, value -> Entry(index.toFloat(), transform(value)) },
			label,
		).apply {
			axisDependency = axis
			color = lineColor
			setDrawIcons(false)
			setDrawCircles(false)
			setDrawCircleHole(false)
			setDrawValues(false)
			formLineWidth = 1f
			formSize = 15f
			isHighlightEnabled = false
			this.label = labelFor(label, values.lastOrNull(), axis)
		}

	private fun labelFor(
		label: String,
		latest: Long?,
		axis: YAxis.AxisDependency,
	): String {
		val value = latest ?: PowerUsageWatcher.UNAVAILABLE
		if (value == PowerUsageWatcher.UNAVAILABLE) {
			return "%s - n/a".format(label)
		}

		return if (axis == YAxis.AxisDependency.LEFT) {
			"%s - %.1fC".format(label, milliCelsiusToCelsius(value))
		} else {
			"%s - %.0fmW".format(label, milliWattsMagnitude(value))
		}
	}

	override fun configure(chart: SafeLineChart) {
		super.configure(chart)

		// Two units, two axes: the base class disables the left one because every other page has a
		// single series family.
		chart.axisLeft.isEnabled = true
		chart.axisLeft.valueFormatter =
			object : IAxisValueFormatter {
				override fun getFormattedValue(
					value: Float,
					axis: AxisBase?,
				): String = "%dC".format(value.roundToLong())
			}

		chart.axisRight.valueFormatter =
			object : IAxisValueFormatter {
				override fun getFormattedValue(
					value: Float,
					axis: AxisBase?,
				): String = "%dmW".format(value.roundToLong())
			}
	}

	/**
	 * The battery line for the legend, or `null` while charging.
	 *
	 * Level is a readout rather than a series because it moves about a percent every few minutes:
	 * over the chart's window a plotted line would be flat, spending an axis on a constant. It is
	 * hidden while charging, when a rising level would contradict a chart about power being spent.
	 */
	@UiThread
	fun batteryReadout(): String? {
		val battery = batteryProvider()
		if (battery.isCharging || battery.levelPercent < 0) {
			return null
		}
		return "%d%%".format(battery.levelPercent)
	}

	private companion object {
		val TEMPERATURE_COLOR = Color.rgb(255, 138, 101)
		val POWER_COLOR = Color.rgb(129, 212, 250)

		/** Half the x-axis width of one sample, which is 1 because x values are sample indices. */
		const val HALF_SAMPLE = 0.5f

		const val THERMAL_LIGHT = 1
		const val THERMAL_MODERATE = 2
		const val THERMAL_SEVERE = 3
		const val THERMAL_CRITICAL = 4
		const val THERMAL_EMERGENCY = 5
		const val THERMAL_SHUTDOWN = 6
	}
}

/**
 * An unavailable reading plots at zero rather than breaking the line.
 */
private fun milliCelsiusToCelsius(milliCelsius: Long): Float =
	if (milliCelsius == PowerUsageWatcher.UNAVAILABLE) 0f else milliCelsius / 1000f

/**
 * Power is plotted as a magnitude. The battery current reverses while charging, and a line that
 * dips below zero would read as the device spending negative power.
 */
private fun microWattsToMilliWatts(microWatts: Long): Float =
	if (microWatts == PowerUsageWatcher.UNAVAILABLE) 0f else abs(microWatts) / 1000f

private fun milliWattsMagnitude(microWatts: Long): Float = abs(microWatts) / 1000f
