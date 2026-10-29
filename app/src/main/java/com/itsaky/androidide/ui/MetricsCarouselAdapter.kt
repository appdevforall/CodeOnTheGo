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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R

/**
 * A page of the editor's metrics carousel.
 *
 * A page says what it is called, what it is, and what draws it. Nothing else in the carousel needs
 * to know which page it is holding, which is what lets [MetricsCarouselAdapter] be page-agnostic.
 *
 * Deliberately an ordinary interface rather than a sealed one. The adapter has always claimed that
 * a new display -- including one contributed by a plugin -- could be added without touching it;
 * while this was sealed that was impossible, since a plugin is a different module and could not
 * implement it at all.
 *
 * @property title Names the page. Shown below the carousel, and the only cue to which page is
 * showing, so every page needs one.
 * @property contentDescription What the plot is, for a screen reader.
 * @property renderer Draws this page and owns its axes, annotations and shading.
 */
interface MetricsPage {
	@get:StringRes val title: Int

	@get:StringRes val contentDescription: Int

	val renderer: MetricsChartRenderer
}

/**
 * A page showing one line chart.
 *
 * There used to be a type per metric -- `MemoryChart`, `NetworkChart`, `PowerChart` -- each with a
 * layout of its own that differed from its siblings by one attribute, plus a view type, a view
 * holder subclass and a branch in four `when` expressions. They differed in nothing a chart page
 * needs to differ in.
 */
data class ChartPage(
	@StringRes override val title: Int,
	@StringRes override val contentDescription: Int,
	override val renderer: MetricsChartRenderer,
) : MetricsPage

/**
 * Backs the editor's horizontally swipeable carousel of metric displays.
 *
 * [pages] is a constructor argument rather than a hardcoded list so that new displays can be added
 * without touching this class -- and now nothing here names a page or a metric, so that is true
 * rather than aspirational.
 *
 * A chart page holds no sample state of its own: its renderer is attached when the page binds and
 * detached when it is recycled, and rebuilds the full history from its watcher each time. Moving
 * away from a chart and back therefore loses nothing.
 */
class MetricsCarouselAdapter(
	private val pages: List<MetricsPage>,
) : RecyclerView.Adapter<MetricsCarouselAdapter.PageViewHolder>() {
	/**
	 * @property boundRenderer What was attached to [chart] at bind time, so [onViewRecycled] can
	 * detach the right renderer without being told the position -- which it is not.
	 */
	class PageViewHolder(
		val chart: SafeLineChart,
	) : RecyclerView.ViewHolder(chart) {
		var boundRenderer: MetricsChartRenderer? = null
	}

	override fun getItemCount(): Int = pages.size

	/**
	 * One view type per page, so a chart is never recycled from one page onto another.
	 *
	 * Not a saving worth making here: a chart carries the state its renderer put on it, and some of
	 * that is written by one renderer and cleared by none of the others -- the thermal shading on
	 * the power page is set through [SafeLineChart.backgroundSpans], which a memory or network
	 * renderer has no reason to touch. A handful of pages, each keeping its own chart, costs
	 * nothing and cannot leak one page's decoration onto another.
	 */
	override fun getItemViewType(position: Int): Int = position

	override fun onCreateViewHolder(
		parent: ViewGroup,
		viewType: Int,
	): PageViewHolder {
		val chart =
			LayoutInflater
				.from(parent.context)
				.inflate(R.layout.item_metrics_chart, parent, false) as SafeLineChart
		return PageViewHolder(chart)
	}

	override fun onBindViewHolder(
		holder: PageViewHolder,
		position: Int,
	) {
		val page = pages[position]
		holder.chart.contentDescription = holder.chart.context.getString(page.contentDescription)
		holder.boundRenderer = page.renderer
		page.renderer.attach(holder.chart)
	}

	override fun onViewRecycled(holder: PageViewHolder) {
		// Only if this holder's chart is still the attached one: a rebind can create the replacement
		// before RecyclerView recycles the view it replaced, and detaching then would drop the new
		// chart instead of the old.
		holder.boundRenderer?.detachIfAttached(holder.chart)
		holder.boundRenderer = null
	}
}
