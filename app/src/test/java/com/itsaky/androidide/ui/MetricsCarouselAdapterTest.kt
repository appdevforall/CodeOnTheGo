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

import android.content.Context
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineDataSet
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R.string
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins what the carousel adapter promises now that it no longer names any metric.
 *
 * It used to branch on the page's type in four places, with a view type, a view-holder subclass
 * and a layout per metric; the layouts differed from each other by one attribute. These are the
 * behaviours that branching was providing, asserted directly so the page-agnostic version cannot
 * quietly drop one.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsCarouselAdapterTest {
	private val context: Context =
		ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_AndroidIDE)

	private val parent = FrameLayout(context)

	/** A renderer that records what it was attached to, and draws just enough to be attachable. */
	private class TestRenderer(
		private val readout: String? = null,
	) : MetricsChartRenderer(sampleIntervalMillis = { 1_000L }) {
		override val helpTag: String = TooltipTag.CAROUSEL_CHART_MEMORY

		val attached = mutableListOf<SafeLineChart>()

		override fun rebuild() {
			val chart = this.chart ?: return
			setData(chart, arrayOf(LineDataSet(listOf(Entry(0f, 0f)), "test")))
			attached += chart
		}

		override fun readout(): String? = readout

		/** detachIfAttached is final, so detachment is observed through what it leaves behind. */
		val isAttached: Boolean
			get() = chart != null
	}

	private fun pageOf(
		renderer: MetricsChartRenderer,
		description: Int = string.metrics_carousel_memory_chart,
	) = ChartPage(title = string.metrics_title_memory, contentDescription = description, renderer = renderer)

	private fun bind(
		adapter: MetricsCarouselAdapter,
		position: Int,
	): MetricsCarouselAdapter.PageViewHolder {
		val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
		adapter.onBindViewHolder(holder, position)
		return holder
	}

	@Test
	fun `each page gets its own chart, never one recycled from another page`() {
		val pages = List(3) { pageOf(TestRenderer()) }
		val adapter = MetricsCarouselAdapter(pages)

		// One view type per position. Sharing a chart between pages would carry over whatever the
		// previous renderer had put on it -- the power page's thermal shading is written through
		// SafeLineChart.backgroundSpans, which no other renderer clears.
		val types = pages.indices.map(adapter::getItemViewType)
		assertThat(types.toSet()).hasSize(pages.size)
	}

	@Test
	fun `binding attaches that page's own renderer`() {
		val first = TestRenderer()
		val second = TestRenderer()
		val adapter = MetricsCarouselAdapter(listOf(pageOf(first), pageOf(second)))

		val holder = bind(adapter, 1)

		assertThat(second.attached).containsExactly(holder.chart)
		assertThat(first.attached).isEmpty()
	}

	@Test
	fun `binding describes the plot for a screen reader`() {
		val adapter =
			MetricsCarouselAdapter(
				listOf(pageOf(TestRenderer(), description = string.metrics_power_chart)),
			)

		val holder = bind(adapter, 0)

		// This was the only thing the three per-metric layouts differed in, so it is the one
		// thing collapsing them to one could have lost.
		assertThat(holder.chart.contentDescription)
			.isEqualTo(context.getString(string.metrics_power_chart))
	}

	@Test
	fun `recycling detaches the renderer that was bound`() {
		val first = TestRenderer()
		val second = TestRenderer()
		val adapter = MetricsCarouselAdapter(listOf(pageOf(first), pageOf(second)))
		val holder = bind(adapter, 1)
		assertThat(second.isAttached).isTrue()

		adapter.onViewRecycled(holder)

		// The holder no longer carries its page's type, so it has to remember its renderer:
		// onViewRecycled is not told the position, and may be given NO_POSITION.
		assertThat(second.isAttached).isFalse()
		assertThat(holder.boundRenderer).isNull()
	}

	@Test
	fun `a rebind before the old view is recycled keeps the new chart attached`() {
		val renderer = TestRenderer()
		val adapter = MetricsCarouselAdapter(listOf(pageOf(renderer)))
		val old = bind(adapter, 0)
		val new = bind(adapter, 0)

		// RecyclerView can create the replacement before recycling what it replaced. Detaching
		// unconditionally here would drop the new chart instead of the old one.
		adapter.onViewRecycled(old)

		assertThat(renderer.isAttached).isTrue()
		assertThat(new.boundRenderer).isSameInstanceAs(renderer)
	}

	@Test
	fun `a page with nothing to read out says so, without being asked what kind it is`() {
		// The battery readout used to be reached by testing the page's type. Only the power page
		// has one; every other renderer answers null from the base class.
		assertThat(TestRenderer().readout()).isNull()
		assertThat(TestRenderer(readout = "62%").readout()).isEqualTo("62%")
	}
}
