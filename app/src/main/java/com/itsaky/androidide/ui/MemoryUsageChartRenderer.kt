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

import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.collection.IntObjectMap
import androidx.collection.MutableIntIntMap
import androidx.core.view.updateLayoutParams
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.itsaky.androidide.R
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.MemoryUsageWatcher.ProcessMemoryInfo
import com.itsaky.androidide.utils.ShiftedLongArray
import com.itsaky.androidide.utils.resolveAttr
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
) {
	private var chart: SafeLineChart? = null

	/**
	 * Maps a watched pid to its dataset index in the attached chart's [LineData]. Empty whenever no
	 * chart is attached.
	 */
	private val pidToDatasetIdx = MutableIntIntMap(initialCapacity = 3)

	/**
	 * Attaches [chart], applies the static chart configuration, and renders the full current
	 * history. Replaces any previously attached chart.
	 */
	@UiThread
	fun attach(chart: SafeLineChart) {
		this.chart = chart
		configure(chart)
		rebuild()
	}

	/**
	 * Detaches the current chart. Sample history is unaffected; a later [attach] renders it in full.
	 */
	@UiThread
	fun detach() {
		chart = null
		pidToDatasetIdx.clear()
	}

	/**
	 * Applies a top margin to the attached chart. No-op when no chart is attached.
	 */
	@UiThread
	fun setTopMargin(margin: Int) {
		chart?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			topMargin = margin
		}
	}

	/**
	 * Rebuilds the chart's datasets from scratch for the currently watched processes, rendering each
	 * process's complete [ProcessMemoryInfo.usageHistory]. Call when the set of watched processes
	 * changes; [onUsagesChanged] calls it on its own when it detects such a change.
	 */
	@UiThread
	fun rebuild() {
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

		val bgColor = chart.context.resolveAttr(R.attr.colorSurfaceDim)
		val textColor = chart.context.resolveAttr(R.attr.colorOnSurface)

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
	 * Renders a fresh set of samples into the attached chart, mutating the existing entries in place.
	 *
	 * Falls back to [rebuild] when [memoryUsage] no longer matches the datasets the chart was built
	 * with -- a process started or stopped being watched, or the chart was attached before this pid
	 * existed. The in-place path is the common one and allocates nothing, which matters because this
	 * runs once a second for the lifetime of the editor.
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
			chart.apply {
				data.notifyDataChanged()
				notifyDataSetChanged()
				invalidate()
			}
		}
	}

	/**
	 * Applies the configuration that does not depend on the samples. Idempotent.
	 */
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
			axisRight.valueFormatter =
				object : IAxisValueFormatter {
					override fun getFormattedValue(
						value: Float,
						axis: AxisBase?,
					): String = "%dMB".format(value.roundToLong())
				}
		}
	}

	private fun labelFor(
		pname: String,
		megabytes: Float,
	): String = "%s - %.2fMB".format(pname, megabytes)
}

private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0

/**
 * The sample at [index] in megabytes. [MemoryUsageWatcher] stores bytes.
 */
private fun ShiftedLongArray.megabytesAt(index: Int): Float = (this[index] / BYTES_PER_MEGABYTE).toFloat()
