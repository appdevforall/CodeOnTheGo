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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Renders [PowerUsageWatcher] samples: battery temperature against power draw (ADFA-5499).
 *
 * The only page with two value axes. Degrees and watts differ in unit and by orders of magnitude,
 * so temperature takes the left axis and power the right. Both series therefore have to declare
 * which axis they belong to -- a dataset left on the default would be drawn against an axis whose
 * labels do not describe it, which is a bug this codebase has already shipped once. Each axis's
 * labels are drawn in its series' colour, so which axis reads which line needs no explaining.
 *
 * Thermal throttling is shown as background shading rather than as a line: the platform reports an
 * ordinal level, not a temperature, so plotting it against degrees would invent a scale. The level
 * is sampled alongside the readings, so a shaded band is simply a run of equal levels.
 *
 * Severity is carried by hue, green through red, at one fixed alpha. Ranking seven ordinals by
 * depth of a single colour asks the eye to compare shades that are never side by side; distinct
 * hues stay tellable apart wherever on the chart they fall.
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
	override fun rebuild() = rebuild(usageProvider())

	/**
	 * Replaces both series from [usage].
	 *
	 * Takes the sample rather than fetching one so [onUsageChanged] can fall back to it without
	 * asking the watcher for a second, later copy of the buffers it was just handed.
	 */
	@UiThread
	private fun rebuild(usage: PowerUsage) {
		val chart = this.chart ?: return
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
					transform = ::microWattsToWatts,
				),
			)

		setData(chart, datasets) { applyAxisRanges(it, usage) }
		applyThermalShading(chart, usage)
	}

	/**
	 * Redraws from the sample just taken, mutating the existing entries in place.
	 *
	 * It used to discard [usage] and call [rebuild], which asked the watcher for another copy of
	 * all three series and allocated two datasets and twenty thousand entries -- every tick, on
	 * the UI thread. The KDoc justified that with "two short series"; they are MAX_USAGE_ENTRIES
	 * long. Falls back to a full rebuild only when the chart's shape no longer matches.
	 */
	@UiThread
	fun onUsageChanged(usage: PowerUsage) {
		val chart = this.chart ?: return
		val data = chart.data
		val temperature = data?.getDataSetByIndex(TEMPERATURE_INDEX) as LineDataSet?
		val power = data?.getDataSetByIndex(POWER_INDEX) as LineDataSet?

		if (temperature == null || power == null ||
			temperature.entryCount != usage.temperatureMilliCelsius.size ||
			power.entryCount != usage.powerMicroWatts.size
		) {
			rebuild(usage)
			return
		}

		val context = chart.context
		update(
			dataset = temperature,
			values = usage.temperatureMilliCelsius,
			label = context.getString(R.string.metrics_power_temperature),
			axis = YAxis.AxisDependency.LEFT,
			transform = ::milliCelsiusToCelsius,
		)
		update(
			dataset = power,
			values = usage.powerMicroWatts,
			label = context.getString(R.string.metrics_power_draw),
			axis = YAxis.AxisDependency.RIGHT,
			transform = ::microWattsToWatts,
		)

		applyThermalShading(chart, usage)
		redraw(chart) { applyAxisRanges(it, usage) }
	}

	/** Rewrites one series' values in place and refreshes its legend entry. */
	private fun update(
		dataset: LineDataSet,
		values: LongArray,
		label: String,
		axis: YAxis.AxisDependency,
		transform: (Long) -> Float,
	) {
		for (index in values.indices) {
			dataset.entries[index].y = transform(values[index])
		}
		dataset.label = labelFor(label, values.lastOrNull(), axis)
		dataset.notifyDataSetChanged()
	}

	/**
	 * Ranges both axes over the samples on screen.
	 *
	 * Two problems, one cause. Left to itself MPAndroidChart ranges over every entry, which
	 * includes the buffer's unsampled prefix -- ten thousand slots that plot as zero -- so the
	 * 29-33C band this page exists to show was pressed into the top tenth of the plot with a
	 * negative gridline beneath it. And the right axis, unpinned, picked up MPAndroidChart's 10%
	 * bottom padding: a negative watt label under a series deliberately plotted as a magnitude
	 * precisely so it could never read as negative power spent.
	 */
	private fun applyAxisRanges(
		chart: SafeLineChart,
		usage: PowerUsage,
	) {
		val visible = visibleSampleRange(chart, usage.temperatureMilliCelsius.size)

		var hottest = Float.NEGATIVE_INFINITY
		var coldest = Float.POSITIVE_INFINITY
		var peakWatts = 0f
		for (index in visible) {
			val milliCelsius = usage.temperatureMilliCelsius[index]
			// Skip the unsampled prefix and anything the device does not report: both plot at
			// zero, and letting zero into the range is what flattened the real readings.
			if (milliCelsius != PowerUsageWatcher.UNAVAILABLE) {
				val celsius = milliCelsiusToCelsius(milliCelsius)
				hottest = max(hottest, celsius)
				coldest = min(coldest, celsius)
			}
			peakWatts = max(peakWatts, microWattsToWatts(usage.powerMicroWatts[index]))
		}

		// Power always starts at zero: it is a magnitude, so there is nothing below it.
		chart.axisRight.axisMinimum = 0f
		chart.axisRight.axisMaximum = max(peakWatts * AXIS_HEADROOM, MIN_AXIS_WATTS)

		if (hottest.isFinite() && coldest.isFinite()) {
			chart.axisLeft.axisMinimum = floor(coldest) - TEMPERATURE_MARGIN_CELSIUS
			chart.axisLeft.axisMaximum = ceil(hottest) + TEMPERATURE_MARGIN_CELSIUS
		} else {
			// Nothing readable yet; a plausible room-to-warm span beats a range built from zeros.
			chart.axisLeft.axisMinimum = DEFAULT_MIN_CELSIUS
			chart.axisLeft.axisMaximum = DEFAULT_MAX_CELSIUS
		}
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

			shadeFor(level)?.let { color ->
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
	 * Level 0 is unthrottled and level -1 is a device that reports no level at all; neither is
	 * shaded, because shading everything would say nothing. The alpha is the same for every level,
	 * so hue alone ranks them, and low enough throughout that the plotted lines stay the foreground.
	 */
	private fun shadeFor(level: Int): Int? {
		val hue =
			when (level) {
				THERMAL_LIGHT -> SHADE_LIGHT
				THERMAL_MODERATE -> SHADE_MODERATE
				THERMAL_SEVERE -> SHADE_SEVERE
				THERMAL_CRITICAL -> SHADE_CRITICAL
				THERMAL_EMERGENCY -> SHADE_EMERGENCY
				THERMAL_SHUTDOWN -> SHADE_SHUTDOWN
				else -> return null
			}

		return ColorUtils.setAlphaComponent(hue, SHADE_ALPHA)
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
			"%s - %s".format(label, formatPower(value))
		}
	}

	/**
	 * The latest draw, for the legend. Below a watt it is given in milliwatts: an idle device would
	 * otherwise read "0.0W", losing the very value the legend exists to show.
	 */
	private fun formatPower(microWatts: Long): String {
		val watts = wattsMagnitude(microWatts)
		return if (watts < 1f) {
			"%.0fmW".format(abs(microWatts) / MICROWATTS_PER_MILLIWATT)
		} else {
			"%.1fW".format(watts)
		}
	}

	override fun configure(chart: SafeLineChart) {
		super.configure(chart)

		// Two units, two axes: the base class disables the left one because every other page has a
		// single series family.
		chart.axisLeft.isEnabled = true

		// Integer labels need integer grid lines, exactly as the watt axis below does. Now that
		// the range is tight -- 29 to 33 rather than 0 to 36 -- the axis would otherwise place
		// lines half a degree apart and "%dC" would print 29C, 30C, 30C, 31C, 31C.
		chart.axisLeft.granularity = 1f
		chart.axisLeft.isGranularityEnabled = true

		chart.axisLeft.valueFormatter =
			object : IAxisValueFormatter {
				override fun getFormattedValue(
					value: Float,
					axis: AxisBase?,
				): String = "%dC".format(value.roundToLong())
			}

		// Watts, not milliwatts: a build peaks in single digit watts, so mW labels spent three
		// characters on trailing zeros. Whole watts, so the labels carry no decimal point either.
		chart.axisRight.valueFormatter =
			object : IAxisValueFormatter {
				override fun getFormattedValue(
					value: Float,
					axis: AxisBase?,
				): String = "%dW".format(value.roundToLong())
			}

		// Integer labels need integer gridlines to match. Left to pick its own spacing the axis
		// will place lines a fraction of a watt apart on an idle device, and rounding those to
		// whole watts prints the same label several times over.
		chart.axisRight.granularity = 1f
		chart.axisRight.isGranularityEnabled = true
	}

	/**
	 * Each axis's labels take the colour of the line they describe. With two axes carrying
	 * unrelated units, colour is what says which reads which; one shared text colour cannot.
	 */
	override fun styleValueAxes(
		chart: SafeLineChart,
		defaultTextColor: Int,
	) {
		chart.axisLeft.textColor = TEMPERATURE_COLOR
		chart.axisRight.textColor = POWER_COLOR
	}

	/**
	 * The battery line for the legend, or `null` while charging.
	 *
	 * Level is a readout rather than a series because it moves about a percent every few minutes:
	 * over the chart's window a plotted line would be flat, spending an axis on a constant. It is
	 * hidden while charging, when a rising level would contradict a chart about power being spent.
	 */
	@UiThread
	override fun readout(): String? {
		val battery = batteryProvider()
		if (battery.isCharging || battery.levelPercent < 0) {
			return null
		}
		return "%d%%".format(battery.levelPercent)
	}

	private companion object {
		val TEMPERATURE_COLOR = Color.rgb(255, 138, 101)
		val POWER_COLOR = Color.rgb(129, 212, 250)

		/** Keeps the tallest line off the top frame of the plot. */
		const val AXIS_HEADROOM = 1.1f

		/** Floor for the power axis, so an idle device still has a readable scale. */
		const val MIN_AXIS_WATTS = 2f

		/** Air above and below the temperature range, so the line is not drawn on the frame. */
		const val TEMPERATURE_MARGIN_CELSIUS = 1f

		/** Shown until the first readable temperature arrives. */
		const val DEFAULT_MIN_CELSIUS = 20f
		const val DEFAULT_MAX_CELSIUS = 40f

		/** Half the x-axis width of one sample, which is 1 because x values are sample indices. */
		const val HALF_SAMPLE = 0.5f

		/**
		 * Throttling shades, green through red. Deliberately six distinct hues rather than one
		 * colour at six depths: the bands are separated in time, so shades of one colour would have
		 * to be compared across the width of the chart.
		 */
		val SHADE_LIGHT = Color.rgb(76, 175, 80)
		val SHADE_MODERATE = Color.rgb(0, 188, 212)
		val SHADE_SEVERE = Color.rgb(253, 216, 53)
		val SHADE_CRITICAL = Color.rgb(251, 140, 0)
		val SHADE_EMERGENCY = Color.rgb(183, 65, 14)
		val SHADE_SHUTDOWN = Color.rgb(229, 57, 53)

		/** Visible against the plot surface without drowning the lines drawn over it. */
		const val SHADE_ALPHA = 96

		const val TEMPERATURE_INDEX = 0
		const val POWER_INDEX = 1

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
private fun microWattsToWatts(microWatts: Long): Float =
	if (microWatts == PowerUsageWatcher.UNAVAILABLE) 0f else abs(microWatts) / MICROWATTS_PER_WATT

private fun wattsMagnitude(microWatts: Long): Float = abs(microWatts) / MICROWATTS_PER_WATT

private const val MICROWATTS_PER_WATT = 1_000_000f
private const val MICROWATTS_PER_MILLIWATT = 1_000f
