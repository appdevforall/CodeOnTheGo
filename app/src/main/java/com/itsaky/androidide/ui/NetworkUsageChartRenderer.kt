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
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.itsaky.androidide.R
import com.itsaky.androidide.utils.NetworkUsageWatcher
import com.itsaky.androidide.utils.NetworkUsageWatcher.NetworkUsage
import com.itsaky.androidide.utils.resolveAttr
import kotlin.math.log10
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
) {
	private var chart: SafeLineChart? = null

	@UiThread
	fun attach(chart: SafeLineChart) {
		this.chart = chart
		configure(chart)
		rebuild()
	}

	@UiThread
	fun detach() {
		chart = null
	}

	/**
	 * Detaches [chart] only if it is the currently attached one. See
	 * [MemoryUsageChartRenderer.detachIfAttached].
	 */
	@UiThread
	fun detachIfAttached(chart: SafeLineChart) {
		if (this.chart === chart) {
			detach()
		}
	}

	/**
	 * Rebuilds both series from the full sample history.
	 */
	@UiThread
	fun rebuild() {
		val chart = this.chart ?: return
		val usage = usageProvider()

		val textColor = chart.context.resolveAttr(R.attr.colorOnSurface)
		val bgColor = chart.context.resolveAttr(R.attr.colorSurfaceDim)

		val datasets =
			arrayOf(
				dataset(usage.received, chart.context.getString(R.string.metrics_network_received), RECEIVED_COLOR),
				dataset(usage.transmitted, chart.context.getString(R.string.metrics_network_transmitted), TRANSMITTED_COLOR),
			)

		chart.apply {
			data = LineData(*datasets)
			axisRight.textColor = textColor
			axisLeft.textColor = textColor
			legend.textColor = textColor

			data.setValueTextColor(textColor)
			setBackgroundColor(bgColor)
			setGridBackgroundColor(bgColor)
			notifyDataSetChanged()
			invalidate()
		}
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

		chart.apply {
			data.notifyDataChanged()
			notifyDataSetChanged()
			invalidate()
		}
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
	): String = "%s - %s/s".format(label, formatBytes(bytes.toDouble()))

	private fun configure(chart: SafeLineChart) {
		chart.apply {
			val colorAccent = context.resolveAttr(R.attr.colorAccent)

			isDragEnabled = false
			description.isEnabled = false
			xAxis.axisLineColor = colorAccent
			axisRight.axisLineColor = colorAccent

			setPinchZoom(false)
			setBackgroundColor(context.resolveAttr(R.attr.colorSurfaceDim))
			setDrawGridBackground(true)
			setScaleEnabled(true)

			axisLeft.isEnabled = false
			axisRight.valueFormatter = BytesAxisFormatter
			// Without a floor the axis auto-scales to the noise around zero when nothing is happening.
			axisRight.axisMinimum = 0f
			// One label per decade, so the gridlines read as 1.0kB / 1.0MB rather than arbitrary
			// fractions of a logarithm.
			axisRight.granularity = 1f
			axisRight.isGranularityEnabled = true
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
				formatBytes(0.0)
			} else {
				formatBytes(10.0.pow(value.toDouble()))
			}
	}

	private companion object {
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
private fun formatBytes(bytes: Double): String {
	val clamped = bytes.coerceAtLeast(0.0)
	return when {
		clamped < 1_000 -> "%dB".format(clamped.roundToLong())
		clamped < 1_000_000 -> "%.1fkB".format(clamped / 1_000)
		clamped < 1_000_000_000 -> "%.1fMB".format(clamped / 1_000_000)
		else -> "%.1fGB".format(clamped / 1_000_000_000)
	}
}
