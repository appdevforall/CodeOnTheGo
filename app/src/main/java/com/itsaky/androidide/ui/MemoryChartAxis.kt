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

import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import kotlin.math.roundToLong

/**
 * The memory chart's value axis: whole megabytes, never negative, never repeated (ADFA-5535).
 *
 * Extracted from the chart setup so the labels it produces can be tested. They could not be checked
 * any other way: what MPAndroidChart prints depends on the range it picks for itself, which is only
 * decided during a layout and a draw.
 */
object MemoryChartAxis {
	/**
	 * Formats whole megabytes.
	 *
	 * The rounding is why [configure] has to bound the interval. Left to itself, MPAndroidChart
	 * picks a label interval from the data, and an all-zero chart -- every editor open, before the
	 * first sample lands -- gave it a range of about -1MB to 1MB with six labels. Rounded to whole
	 * megabytes those collapse onto three strings, each printed twice: "-1MB, -1MB, 0MB, 0MB, 1MB,
	 * 1MB".
	 */
	private val FORMATTER =
		object : IAxisValueFormatter {
			override fun getFormattedValue(
				value: Float,
				axis: AxisBase?,
			): String = "%dMB".format(value.roundToLong())
		}

	/**
	 * A megabyte, the smallest interval the label text can tell apart.
	 *
	 * Granularity is a floor on the interval, not the interval itself, so this costs nothing on a
	 * chart with real data -- a memory axis spanning hundreds of megabytes was never going to want
	 * gridlines a megabyte apart. It only bites in the degenerate case it exists for.
	 */
	private const val MINIMUM_INTERVAL_MEGABYTES = 1f

	fun configure(axis: YAxis) {
		axis.valueFormatter = FORMATTER
		// Memory is never negative, so the axis has no business going below zero -- which is also
		// half of what made the duplicates readable as "-1MB" twice.
		axis.axisMinimum = 0f
		axis.granularity = MINIMUM_INTERVAL_MEGABYTES
		axis.isGranularityEnabled = true
	}
}
