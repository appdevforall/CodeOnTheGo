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

import androidx.annotation.UiThread
import androidx.collection.IntObjectMap
import androidx.collection.MutableIntIntMap
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.MemoryUsageWatcher.ProcessMemoryInfo
import com.itsaky.androidide.utils.MetricsAnnotationStore
import com.itsaky.androidide.utils.ShiftedLongArray
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Renders [MemoryUsageWatcher] samples into a [SafeLineChart].
 *
 * The chart view is attached and detached independently of the data: [MemoryUsageWatcher] owns the
 * per-process [ProcessMemoryInfo.usageHistory] ring buffers, so this renderer holds no sample state
 * of its own and can rebuild a complete chart from [usagesProvider] at any time. That is what makes
 * the chart safe to host in a recycling container (ADFA-5487's metrics carousel): a chart view that
 * is created long after watching began still shows the full history, and one that is recycled away
 * loses nothing.
 *
 * All methods must be called on the UI thread. MPAndroidChart is not thread-safe; see [SafeLineChart].
 *
 * @param usagesProvider Supplies the currently watched processes, newest state each call.
 * @param lineColorFor Supplies the plot line color for a process.
 */
class MemoryUsageChartRenderer(
	private val usagesProvider: () -> Array<ProcessMemoryInfo>,
	private val lineColorFor: (ProcessMemoryInfo) -> Int,
	annotations: MetricsAnnotationStore? = null,
	sampleIntervalMillis: () -> Long = { MemoryUsageWatcher.DEFAULT_UPDATE_INTERVAL },
) : MetricsChartRenderer(
		sampleIntervalMillis = sampleIntervalMillis,
		annotations = annotations,
	) {
	/**
	 * Maps a watched pid to its dataset index in the attached chart's [LineData]. Empty whenever no
	 * chart is attached.
	 */
	private val pidToDatasetIdx = MutableIntIntMap(initialCapacity = 3)

	@UiThread
	override fun detach() {
		super.detach()
		pidToDatasetIdx.clear()
	}

	override val helpTag: String = TooltipTag.CAROUSEL_CHART_MEMORY

	/**
	 * Rebuilds the chart's datasets from scratch for the currently watched processes, rendering each
	 * process's complete [ProcessMemoryInfo.usageHistory]. Call when the set of watched processes
	 * changes; [onUsagesChanged] calls it on its own when it detects such a change.
	 */
	@UiThread
	override fun rebuild() {
		val chart = this.chart ?: return
		val processes = usagesProvider()

		pidToDatasetIdx.clear()

		val datasets =
			Array(processes.size) { index ->
				val proc = processes[index]
				pidToDatasetIdx[proc.pid] = index

				LineDataSet(
					List(proc.usageHistory.size) { entryIdx ->
						Entry(entryIdx.toFloat(), proc.usageHistory.megabytesAt(entryIdx))
					},
					proc.pname,
				).apply {
					color = lineColorFor(proc)
					setDrawIcons(false)
					setDrawCircles(false)
					setDrawCircleHole(false)
					setDrawValues(false)
					formLineWidth = 1f
					formSize = 15f
					isHighlightEnabled = false
					label = labelFor(proc.pname, entries.lastOrNull()?.y ?: 0f)
				}
			}

		applyAxisRange(chart, processes)
		setData(chart, datasets)
	}

	/**
	 * Scales the value axis to the samples on screen (ADFA-5486).
	 *
	 * Left to itself MPAndroidChart ranges over every entry in the data, which is the whole
	 * retained buffer -- ten thousand samples, hours of it -- while sixty are visible. One early
	 * Gradle daemon peak then flattened every later reading into the bottom of the plot and nothing
	 * ever brought the ceiling back down. The network chart was fixed first; this is the sibling.
	 */
	private fun applyAxisRange(
		chart: SafeLineChart,
		processes: Array<ProcessMemoryInfo>,
	) = applyAxisRangeFor(chart) { visit -> processes.forEach(visit) }

	/**
	 * Sets the axis from whatever [forEachProcess] offers, so a caller that already holds the
	 * samples does not have to ask the watcher for another copy of them.
	 */
	private fun applyAxisRangeFor(
		chart: SafeLineChart,
		forEachProcess: ((ProcessMemoryInfo) -> Unit) -> Unit,
	) {
		var peak = 0f
		forEachProcess { proc ->
			for (index in visibleSampleRange(chart, proc.usageHistory.size)) {
				peak = max(peak, proc.usageHistory.megabytesAt(index))
			}
		}

		chart.axisRight.axisMinimum = 0f
		// A little headroom so the tallest line is not drawn on the frame, and a floor so an idle
		// chart does not collapse onto a zero-height axis before the first samples land.
		chart.axisRight.axisMaximum = max(peak * AXIS_HEADROOM, MIN_AXIS_MEGABYTES)
	}

	/**
	 * Renders a fresh set of samples into the attached chart, mutating the existing entries in place.
	 *
	 * Falls back to [rebuild] when [memoryUsage] no longer matches the datasets the chart was built
	 * with -- a process started or stopped being watched, or the chart was attached before this pid
	 * existed. The in-place path is the common one: it mutates the existing entries rather than
	 * rebuilding the datasets, which is what matters because this runs once a second for the
	 * lifetime of the editor. It is not allocation-free -- each series reformats its legend label
	 * every tick -- so do not add work here on the assumption that it is.
	 */
	@UiThread
	fun onUsagesChanged(memoryUsage: IntObjectMap<ProcessMemoryInfo>) {
		val chart = this.chart ?: return

		if (memoryUsage.size != pidToDatasetIdx.size) {
			rebuild()
			return
		}

		var dataChanged = false
		memoryUsage.forEachValue { proc ->
			val datasetIdx = pidToDatasetIdx.getOrDefault(proc.pid, -1)
			val dataset = chart.data?.getDataSetByIndex(datasetIdx) as LineDataSet?
			if (dataset == null) {
				// The chart's datasets no longer describe the watched processes. Rebuild rather than
				// dropping this process's samples on the floor, as the previous code did.
				rebuild()
				return
			}

			for (index in dataset.entries.indices) {
				dataset.entries[index].y = proc.usageHistory.megabytesAt(index)
			}

			dataset.label = labelFor(proc.pname, dataset.entries.lastOrNull()?.y ?: 0f)
			dataset.notifyDataSetChanged()
			dataChanged = true
		}

		if (dataChanged) {
			// From the samples already in hand: usagesProvider() copies every history, so calling
			// it again here would snapshot the whole buffer a second time per tick.
			applyAxisRangeFor(chart) { visit ->
				memoryUsage.forEachValue { visit(it) }
			}
			redraw(chart)
		}
	}

	override fun configure(chart: SafeLineChart) {
		super.configure(chart)
		chart.axisRight.valueFormatter =
			object : IAxisValueFormatter {
				override fun getFormattedValue(
					value: Float,
					axis: AxisBase?,
				): String = "%dMB".format(value.roundToLong())
			}
	}

	private companion object {
		/** Keeps the tallest line off the top frame of the plot. */
		const val AXIS_HEADROOM = 1.1f

		/** Floor for the axis, so an idle chart has a readable scale rather than a flat zero. */
		const val MIN_AXIS_MEGABYTES = 64f
	}

	private fun labelFor(
		pname: String,
		megabytes: Float,
	): String = "%s - %.2fMB".format(pname, megabytes)
}

internal const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0

/**
 * The sample at [index] in megabytes. [MemoryUsageWatcher] stores bytes.
 */
private fun ShiftedLongArray.megabytesAt(index: Int): Float = (this[index] / BYTES_PER_MEGABYTE).toFloat()
