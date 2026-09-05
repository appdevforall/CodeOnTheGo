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
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R

/**
 * A page of the editor's metrics carousel.
 *
 * @property title Names the page. Shown below the carousel, and the only cue to which page is
 * showing, so every page needs one.
 */
sealed interface MetricsPage {
	@get:StringRes val title: Int

	/** The live memory-usage chart, rendered by [MemoryUsageChartRenderer]. */
	data class MemoryChart(
		@StringRes override val title: Int,
	) : MetricsPage

	/** The live network-traffic chart, rendered by [NetworkUsageChartRenderer]. */
	data class NetworkChart(
		@StringRes override val title: Int,
	) : MetricsPage
}

/**
 * Backs the editor's horizontally swipeable carousel of metric displays.
 *
 * [pages] is a constructor argument rather than a hardcoded list so that new displays -- a network
 * traffic chart, or pages contributed by plugins -- can be added without touching this class.
 *
 * The chart page holds no sample state of its own: [chartRenderer] is attached when the page binds
 * and detached when it is recycled, and rebuilds the full history from [MemoryUsageChartRenderer]'s
 * watcher each time. Swiping away from the chart and back therefore loses nothing.
 */
class MetricsCarouselAdapter(
	private val pages: List<MetricsPage>,
	private val memoryChartRenderer: MemoryUsageChartRenderer,
	private val networkChartRenderer: NetworkUsageChartRenderer,
) : RecyclerView.Adapter<MetricsCarouselAdapter.PageViewHolder>() {
	sealed class PageViewHolder(
		view: View,
	) : RecyclerView.ViewHolder(view) {
		class MemoryChart(
			val chart: SafeLineChart,
		) : PageViewHolder(chart)

		class NetworkChart(
			val chart: SafeLineChart,
		) : PageViewHolder(chart)
	}

	override fun getItemCount(): Int = pages.size

	override fun getItemViewType(position: Int): Int =
		when (pages[position]) {
			is MetricsPage.MemoryChart -> VIEW_TYPE_MEMORY_CHART
			is MetricsPage.NetworkChart -> VIEW_TYPE_NETWORK_CHART
		}

	override fun onCreateViewHolder(
		parent: ViewGroup,
		viewType: Int,
	): PageViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		return when (viewType) {
			VIEW_TYPE_MEMORY_CHART -> {
				PageViewHolder.MemoryChart(
					inflater.inflate(R.layout.item_metrics_memory_chart, parent, false) as SafeLineChart,
				)
			}

			VIEW_TYPE_NETWORK_CHART -> {
				PageViewHolder.NetworkChart(
					inflater.inflate(R.layout.item_metrics_network_chart, parent, false) as SafeLineChart,
				)
			}

			else -> {
				throw IllegalArgumentException("Unknown metrics page view type: $viewType")
			}
		}
	}

	override fun onBindViewHolder(
		holder: PageViewHolder,
		position: Int,
	) {
		when (pages[position]) {
			is MetricsPage.MemoryChart -> {
				memoryChartRenderer.attach((holder as PageViewHolder.MemoryChart).chart)
			}

			is MetricsPage.NetworkChart -> {
				networkChartRenderer.attach((holder as PageViewHolder.NetworkChart).chart)
			}
		}
	}

	override fun onViewRecycled(holder: PageViewHolder) {
		// Only if this holder's chart is still the attached one: a rebind can create the replacement
		// before RecyclerView recycles the view it replaced, and detaching then would drop the new
		// chart instead of the old.
		when (holder) {
			is PageViewHolder.MemoryChart -> memoryChartRenderer.detachIfAttached(holder.chart)
			is PageViewHolder.NetworkChart -> networkChartRenderer.detachIfAttached(holder.chart)
		}
	}

	private companion object {
		const val VIEW_TYPE_MEMORY_CHART = 0
		const val VIEW_TYPE_NETWORK_CHART = 1
	}
}
