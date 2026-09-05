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
import androidx.viewpager2.widget.ViewPager2
import com.itsaky.androidide.databinding.LayoutMemUsageBinding
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.NetworkUsageWatcher

/**
 * Drives one metrics carousel: its pages, its renderers, and the title that names the current page.
 *
 * Split out of the editor activity so the carousel can be hosted somewhere else -- specifically a
 * floating window, once ADFA-5486's undocking lands. The host supplies a binding to bind to and the
 * watchers to read from; everything else about running a carousel lives here.
 *
 * Only one controller may be live at a time. [MemoryUsageWatcher] and [NetworkUsageWatcher] each
 * hold a single listener, so a second carousel would silently take the updates from the first --
 * which is why undocking has to move the carousel out of the editor rather than copy it there.
 *
 * @param lineColorFor Supplies the plot colour for a watched process. Passed in because the process
 * names it keys on belong to the editor activity.
 */
class MetricsCarouselController(
	private val memoryUsageWatcher: MemoryUsageWatcher,
	private val networkUsageWatcher: NetworkUsageWatcher,
	lineColorFor: (MemoryUsageWatcher.ProcessMemoryInfo) -> Int,
) {
	private val memoryRenderer =
		MemoryUsageChartRenderer(
			usagesProvider = { memoryUsageWatcher.getMemoryUsages() },
			lineColorFor = lineColorFor,
		)

	private val networkRenderer =
		NetworkUsageChartRenderer(usageProvider = { networkUsageWatcher.getUsage() })

	private val pages =
		listOf(
			// The memory chart is the default page (ADFA-5487); network traffic is the second
			// (ADFA-5489), replacing the brand-mark placeholder that ADFA-5487 shipped.
			MetricsPage.MemoryChart(title = string.metrics_title_memory),
			MetricsPage.NetworkChart(title = string.metrics_title_network),
		)

	private val memoryListener =
		MemoryUsageWatcher.MemoryUsageListener { memoryUsage ->
			memoryRenderer.onUsagesChanged(memoryUsage)
		}

	private val networkListener =
		NetworkUsageWatcher.NetworkUsageListener { usage ->
			networkRenderer.onUsageChanged(usage)
		}

	private var binding: LayoutMemUsageBinding? = null
	private var pageCallback: ViewPager2.OnPageChangeCallback? = null

	/**
	 * The pager of the bound carousel, or `null` when nothing is bound. Exposed so a host can apply
	 * layout that is its own concern, such as the editor's status-bar inset.
	 */
	val pager: ViewPager2?
		get() = binding?.metricsPager

	/**
	 * Binds the carousel to [binding] and starts feeding it samples.
	 */
	@UiThread
	fun bind(binding: LayoutMemUsageBinding) {
		this.binding = binding

		binding.metricsPager.adapter = MetricsCarouselAdapter(pages, memoryRenderer, networkRenderer)

		val showTitleFor = { position: Int ->
			pages.getOrNull(position)?.let { page ->
				binding.metricsTitle.setText(page.title)
			}
		}

		pageCallback =
			object : ViewPager2.OnPageChangeCallback() {
				override fun onPageSelected(position: Int) {
					showTitleFor(position)
				}
			}.also { binding.metricsPager.registerOnPageChangeCallback(it) }

		// onPageSelected does not fire for the page the carousel opens on.
		showTitleFor(binding.metricsPager.currentItem)

		memoryUsageWatcher.listener = memoryListener
		networkUsageWatcher.listener = networkListener
	}

	/**
	 * Stops feeding the carousel and releases the bound views. Sampling is unaffected -- the
	 * watchers keep their history, so re-binding shows it in full.
	 */
	@UiThread
	fun unbind() {
		if (memoryUsageWatcher.listener === memoryListener) {
			memoryUsageWatcher.listener = null
		}
		if (networkUsageWatcher.listener === networkListener) {
			networkUsageWatcher.listener = null
		}

		pageCallback?.let { binding?.metricsPager?.unregisterOnPageChangeCallback(it) }
		pageCallback = null

		binding?.metricsPager?.adapter = null
		memoryRenderer.detach()
		networkRenderer.detach()
		binding = null
	}

	/**
	 * Redraws both charts from the full history, for a host coming back to the foreground with
	 * samples gathered while it was away.
	 */
	@UiThread
	fun refresh() {
		memoryRenderer.rebuild()
		networkRenderer.rebuild()
	}

	/**
	 * Rebuilds the memory chart for a changed set of watched processes.
	 */
	@UiThread
	fun onWatchedProcessesChanged() {
		memoryRenderer.rebuild()
	}
}
