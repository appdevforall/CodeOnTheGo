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
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.itsaky.androidide.R
import com.itsaky.androidide.utils.NetworkUsageWatcher
import com.itsaky.androidide.utils.NetworkUsageWatcher.NetworkUsage
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Renders [NetworkUsageWatcher] samples into a [SafeLineChart] on a logarithmic scale (ADFA-5489).
 *
 * Traffic spans orders of magnitude -- a few hundred bytes of chatter next to a multi-megabyte
 * Gradle download -- so a linear axis flattens everything but the largest burst into the baseline.
 * MPAndroidChart has no logarithmic axis, so the plotted value is [log10] of the byte count and
 * [BytesAxisFormatter] turns the axis labels back into byte units.
 *
 * Zero is the common sample, not an edge case: an idle IDE transfers nothing, and `log10(0)` is
 * negative infinity. Values are therefore `log10(bytes + 1)`, which puts a zero sample at exactly
 * `0.0` and keeps the line continuous.
 *
 * Like [MemoryUsageChartRenderer] this holds no sample state -- [NetworkUsageWatcher] owns the
 * history -- so a chart can be attached, detached and recycled by the metrics carousel without
 * losing anything.
 *
 * All methods must be called on the UI thread; MPAndroidChart is not thread-safe (see
 * [SafeLineChart]).
 *
 * @param usageProvider Supplies the current sample history.
 */
class NetworkUsageChartRenderer(
	private val usageProvider: () -> NetworkUsage,
) : MetricsChartRenderer() {
	/**
	 * Rebuilds both series from the full sample history.
	 */
	@UiThread
	override fun rebuild() {
		val chart = this.chart ?: return
		val usage = usageProvider()

		val datasets =
			arrayOf(
				dataset(usage.received, chart.context.getString(R.string.metrics_network_received), RECEIVED_COLOR),
				dataset(usage.transmitted, chart.context.getString(R.string.metrics_network_transmitted), TRANSMITTED_COLOR),
			)

		applyAxisRange(chart, usage)
		setData(chart, datasets)
	}

	/**
	 * Updates both series in place from a fresh sample, rebuilding if the chart's shape no longer
	 * matches. Allocates nothing on the common path, which runs once a second.
	 */
	@UiThread
	fun onUsageChanged(usage: NetworkUsage) {
		val chart = this.chart ?: return
		val data = chart.data

		if (data == null || data.dataSetCount != SERIES_COUNT) {
			rebuild()
			return
		}

		val received = data.getDataSetByIndex(RECEIVED_INDEX) as LineDataSet?
		val transmitted = data.getDataSetByIndex(TRANSMITTED_INDEX) as LineDataSet?
		if (received == null || transmitted == null ||
			received.entryCount != usage.received.size ||
			transmitted.entryCount != usage.transmitted.size
		) {
			rebuild()
			return
		}

		update(received, usage.received, chart.context.getString(R.string.metrics_network_received))
		update(transmitted, usage.transmitted, chart.context.getString(R.string.metrics_network_transmitted))

		applyAxisRange(chart, usage)
		redraw(chart)
	}

	private fun dataset(
		samples: LongArray,
		label: String,
		lineColor: Int,
	): LineDataSet =
		LineDataSet(
			List(samples.size) { index -> Entry(index.toFloat(), samples[index].toLogBytes()) },
			label,
		).apply {
			// The labelled axis is the right one, and applyAxisRange pins its range. Without this the
			// series is scaled against the (disabled, auto-ranged) left axis instead, so the line is
			// drawn at a position the labels do not describe -- an idle chart plots its zero line
			// halfway up a plot whose baseline is labelled 0 B.
			axisDependency = YAxis.AxisDependency.RIGHT
			color = lineColor
			setDrawIcons(false)
			setDrawCircles(false)
			setDrawCircleHole(false)
			setDrawValues(false)
			formLineWidth = 1f
			formSize = 15f
			isHighlightEnabled = false
			this.label = labelFor(label, samples.lastOrNull() ?: 0L)
		}

	private fun update(
		dataset: LineDataSet,
		samples: LongArray,
		label: String,
	) {
		for (index in samples.indices) {
			dataset.entries[index].y = samples[index].toLogBytes()
		}
		dataset.label = labelFor(label, samples.lastOrNull() ?: 0L)
		dataset.notifyDataSetChanged()
	}

	private fun labelFor(
		label: String,
		bytes: Long,
	): String = "%s - %s/s".format(label, formatBytes(bytes.toDouble(), decimals = 1))

	/**
	 * Pins the axis to whole decades, from zero up to at least [MIN_AXIS_DECADES].
	 *
	 * Two things depend on this. Zero has to sit on the baseline: when every sample is zero -- an
	 * idle IDE -- the data range is degenerate, and left to itself the chart pads around it and
	 * floats the flat line up the middle of the plot. And the maximum has to be a whole number, so
	 * the gridlines (granularity 1) land on exact powers of ten and can be labelled as whole units.
	 */
	private fun applyAxisRange(
		chart: SafeLineChart,
		usage: NetworkUsage,
	) {
		val peak = max(usage.received.maxOrNull() ?: 0L, usage.transmitted.maxOrNull() ?: 0L)
		chart.axisRight.axisMinimum = 0f
		chart.axisRight.axisMaximum = ceil(peak.toLogBytes()).coerceAtLeast(MIN_AXIS_DECADES)
	}

	override fun configure(chart: SafeLineChart) {
		super.configure(chart)
		chart.axisRight.apply {
			valueFormatter = BytesAxisFormatter
			// One label per decade, so the gridlines read as 1 kB / 1 MB rather than arbitrary
			// fractions of a logarithm. The range itself is set per sample by applyAxisRange.
			granularity = 1f
			isGranularityEnabled = true
		}
	}

	/**
	 * Labels a logarithmic axis value in byte units.
	 *
	 * Gridlines land on integer values (granularity 1), so each is a power of ten and is labelled as
	 * one: 10B, 100B, 1.0kB. The exact inverse of [toLogBytes] would be `10^value - 1`, which labels
	 * those same lines 9B, 99B, 999B -- correct to the byte but unreadable as a scale. The one byte
	 * is not worth the confusion; the legend carries the exact current figure.
	 *
	 * Zero is the exception and is labelled exactly: `log10(0 + 1)` is 0, so the baseline really is
	 * no traffic, not one byte.
	 */
	private object BytesAxisFormatter : IAxisValueFormatter {
		override fun getFormattedValue(
			value: Float,
			axis: AxisBase?,
		): String =
			if (value < 0.5f) {
				formatBytes(0.0, decimals = 0)
			} else {
				// Gridlines are whole decades, so the mantissa is exact and needs no decimal place.
				formatBytes(10.0.pow(value.toDouble()), decimals = 0)
			}
	}

	private companion object {
		/**
		 * The axis always spans at least this many decades (0 B to 1 kB), so an idle chart keeps a
		 * sensible scale instead of collapsing onto a single value.
		 */
		const val MIN_AXIS_DECADES = 3f

		const val SERIES_COUNT = 2
		const val RECEIVED_INDEX = 0
		const val TRANSMITTED_INDEX = 1

		val RECEIVED_COLOR = Color.CYAN
		val TRANSMITTED_COLOR = Color.MAGENTA
	}
}

/**
 * The plotted value for a byte count: `log10(bytes + 1)`.
 *
 * The `+ 1` is what makes zero plottable -- it maps to `0.0` rather than negative infinity -- and
 * zero is the usual sample for an idle IDE.
 */
private fun Long.toLogBytes(): Float = log10(this.coerceAtLeast(0L).toDouble() + 1.0).toFloat()

/**
 * Formats a byte count for an axis label or legend, to at most one decimal place.
 *
 * Units are decimal (1 kB = 1000 B), not binary. On a log10 axis the gridlines are powers of ten,
 * and dividing those by 1024 would label them 9.8KB, 977KB, 954MB -- the decades stop looking like
 * decades. Decimal units are also the convention for network throughput.
 */
private fun formatBytes(
	bytes: Double,
	decimals: Int,
): String {
	val clamped = bytes.coerceAtLeast(0.0)
	return when {
		clamped < 1_000 -> "%d B".format(clamped.roundToLong())
		clamped < 1_000_000 -> "%.${decimals}f kB".format(clamped / 1_000)
		clamped < 1_000_000_000 -> "%.${decimals}f MB".format(clamped / 1_000_000)
		else -> "%.${decimals}f GB".format(clamped / 1_000_000_000)
	}
}
