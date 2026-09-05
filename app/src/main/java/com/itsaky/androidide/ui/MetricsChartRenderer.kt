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

import androidx.annotation.CallSuper
import androidx.annotation.UiThread
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.itsaky.androidide.R
import com.itsaky.androidide.utils.resolveAttr

/**
 * Shared behaviour for the charts on the editor's metrics carousel.
 *
 * A renderer holds no sample state -- the watchers own the history -- so a chart view is attached
 * when its carousel page binds and detached when the page is recycled, and [rebuild] can redraw the
 * whole series from scratch at any time. That is what makes a chart safe as a recycled page.
 *
 * Subclasses supply the data and whatever axis configuration is specific to them; everything the
 * charts have in common lives here, so a change to how metrics charts look or behave is made once.
 *
 * All methods must be called on the UI thread. MPAndroidChart is not thread-safe; see
 * [SafeLineChart].
 */
abstract class MetricsChartRenderer {
	/**
	 * The attached chart, or `null` when no carousel page is bound to this renderer.
	 */
	protected var chart: SafeLineChart? = null
		private set

	/**
	 * Attaches [chart], applies configuration, and renders the full current history.
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
	@CallSuper
	open fun detach() {
		chart = null
	}

	/**
	 * Detaches [chart] only if it is the currently attached one.
	 *
	 * A recycling container needs this: RecyclerView can bind a replacement view before recycling
	 * the one it replaced, and an unconditional detach would then drop the new chart.
	 */
	@UiThread
	fun detachIfAttached(chart: SafeLineChart) {
		if (this.chart === chart) {
			detach()
		}
	}

	/**
	 * Rebuilds the chart's series from the full current history.
	 */
	@UiThread
	abstract fun rebuild()

	/**
	 * Applies the configuration every metrics chart shares. Subclasses override to add their own --
	 * a value formatter, axis range -- and must call through.
	 */
	@CallSuper
	protected open fun configure(chart: SafeLineChart) {
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

			// The right axis carries the labels; the left is unused.
			axisLeft.isEnabled = false
		}
	}

	/**
	 * Installs [datasets] on [chart] and applies the theme colours, then redraws.
	 */
	protected fun setData(
		chart: SafeLineChart,
		datasets: Array<LineDataSet>,
	) {
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
	 * Redraws after the attached series have been mutated in place.
	 */
	protected fun redraw(chart: SafeLineChart) {
		chart.apply {
			data.notifyDataChanged()
			notifyDataSetChanged()
			invalidate()
		}
	}
}
