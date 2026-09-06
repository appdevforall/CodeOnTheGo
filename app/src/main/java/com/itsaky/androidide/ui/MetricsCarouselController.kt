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

import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider
import com.itsaky.androidide.databinding.LayoutMemUsageBinding
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.IntentUtils
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.MetricsAnnotationStore
import com.itsaky.androidide.utils.MetricsSamplingRates
import com.itsaky.androidide.utils.MetricsSnapshot
import com.itsaky.androidide.utils.NetworkUsageWatcher
import com.itsaky.androidide.utils.PowerUsageWatcher
import com.itsaky.androidide.utils.displayTooltipOnLongPress
import com.itsaky.androidide.utils.showIdeCategoryTooltipIfPresent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
	private val powerUsageWatcher: PowerUsageWatcher,
	lineColorFor: (MemoryUsageWatcher.ProcessMemoryInfo) -> Int,
	annotations: MetricsAnnotationStore? = null,
) {
	private val memoryRenderer =
		MemoryUsageChartRenderer(
			usagesProvider = { memoryUsageWatcher.getMemoryUsages() },
			lineColorFor = lineColorFor,
			annotations = annotations,
			sampleIntervalMillis = { memoryUsageWatcher.updateInterval },
		)

	private val networkRenderer =
		NetworkUsageChartRenderer(
			usageProvider = { networkUsageWatcher.getUsage() },
			annotations = annotations,
			sampleIntervalMillis = { networkUsageWatcher.updateInterval },
		)

	private val pages =
		listOf(
			// The memory chart is the default page (ADFA-5487); network traffic is the second
			// (ADFA-5489), replacing the brand-mark placeholder that ADFA-5487 shipped.
			MetricsPage.MemoryChart(title = string.metrics_title_memory),
			MetricsPage.NetworkChart(title = string.metrics_title_network),
			MetricsPage.PowerChart(title = string.metrics_title_power),
		)

	private val powerRenderer =
		PowerUsageChartRenderer(
			usageProvider = { powerUsageWatcher.getUsage() },
			batteryProvider = { powerUsageWatcher.latestBattery },
			annotations = annotations,
			sampleIntervalMillis = { powerUsageWatcher.updateInterval },
		)

	private val powerListener =
		PowerUsageWatcher.PowerUsageListener { usage ->
			powerRenderer.onUsageChanged(usage)
			updateBatteryReadout()
		}

	private val memoryListener =
		MemoryUsageWatcher.MemoryUsageListener { memoryUsage ->
			memoryRenderer.onUsagesChanged(memoryUsage)
		}

	private val networkListener =
		NetworkUsageWatcher.NetworkUsageListener { usage ->
			networkRenderer.onUsageChanged(usage)
		}

	/**
	 * Runs the snapshot write. Main-dispatched so its result lands back on the UI thread, with the
	 * disk work pushed to [Dispatchers.IO] inside; a SupervisorJob so one failed export does not
	 * stop the next.
	 */
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
		// A carousel can be re-bound without an intervening unbind -- docking, undocking and an
		// activity recreation all route through here. Releasing first keeps one page callback and
		// one set of listeners alive rather than accumulating them on views that are already gone.
		if (this.binding != null) {
			unbind()
		}

		this.binding = binding

		binding.metricsPager.adapter = MetricsCarouselAdapter(pages, memoryRenderer, networkRenderer, powerRenderer)

		val showTitleFor = { position: Int ->
			pages.getOrNull(position)?.let { page ->
				binding.metricsTitle.setText(page.title)
			}
		}

		pageCallback =
			object : ViewPager2.OnPageChangeCallback() {
				override fun onPageSelected(position: Int) {
					showTitleFor(position)
					updateArrows(position)
					updateBatteryReadout()
					// A page left zoomed would keep claiming horizontal drags when swiped back to.
					memoryRenderer.resetZoom()
					networkRenderer.resetZoom()
					powerRenderer.resetZoom()
				}
			}.also { binding.metricsPager.registerOnPageChangeCallback(it) }

		// onPageSelected does not fire for the page the carousel opens on.
		showTitleFor(binding.metricsPager.currentItem)

		// A tap on the x axis opens the sampling-rate chooser (ADFA-5486). The axis is drawn by the
		// chart, not a view of its own, so the strip of the pager it occupies is the target.
		// Paging is by the arrows only. A swipe in the plot competes with panning a zoomed chart
		// and with the editor's drawer gesture, and losing that race intermittently made the
		// carousel feel broken; with touch paging off, a horizontal drag is unambiguously a pan.
		binding.metricsPager.isUserInputEnabled = false

		memoryRenderer.onXAxisTap = { showSamplingRateDialog() }
		networkRenderer.onXAxisTap = { showSamplingRateDialog() }
		powerRenderer.onXAxisTap = { showSamplingRateDialog() }

		updateBatteryReadout()

		// A camera button in the graph's bottom-right corner exports the chart. The gestures over
		// the chart are all spoken for, so this is a control rather than another gesture.
		binding.metricsSnapshot.setOnClickListener { exportSnapshot() }

		// Arrows are the dependable way to move between pages: a swipe has to share the gesture
		// with panning a zoomed chart and with the editor's drawer, and loses often enough to be
		// annoying.
		binding.metricsPrevious.setOnClickListener { step(-1) }
		binding.metricsNext.setOnClickListener { step(1) }
		updateArrows(binding.metricsPager.currentItem)

		wireHelp(binding)

		memoryUsageWatcher.listener = memoryListener
		networkUsageWatcher.listener = networkListener
		powerUsageWatcher.listener = powerListener
	}

	/**
	 * Gives every control in the strip its long-press help (ADFA-5510).
	 *
	 * Here rather than at each host, because this runs for the docked strip and for the floating
	 * window alike -- the window's own chrome already carries the `window-*` tags, and the carousel
	 * inside it is this same controller.
	 *
	 * The charts are absent from this list on purpose: MPAndroidChart swallows the touch events a
	 * view-level long press would need, so each renderer answers through the chart's gesture
	 * listener instead.
	 */
	@UiThread
	private fun wireHelp(binding: LayoutMemUsageBinding) {
		val context = binding.root.context
		binding.root.displayTooltipOnLongPress(context, TooltipTag.CAROUSEL_PANEL)
		binding.metricsTitle.displayTooltipOnLongPress(context, TooltipTag.CAROUSEL_TITLE)
		binding.metricsPrevious.displayTooltipOnLongPress(context, TooltipTag.CAROUSEL_PREVIOUS)
		binding.metricsNext.displayTooltipOnLongPress(context, TooltipTag.CAROUSEL_NEXT)
		binding.metricsSnapshot.displayTooltipOnLongPress(context, TooltipTag.CAROUSEL_SNAPSHOT)
		binding.metricsBattery.displayTooltipOnLongPress(context, TooltipTag.CAROUSEL_BATTERY)
		binding.metricsUndockedMessage.displayTooltipOnLongPress(context, TooltipTag.CAROUSEL_UNDOCKED)
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
		if (powerUsageWatcher.listener === powerListener) {
			powerUsageWatcher.listener = null
		}

		memoryRenderer.onXAxisTap = null
		networkRenderer.onXAxisTap = null
		powerRenderer.onXAxisTap = null
		binding?.metricsSnapshot?.setOnClickListener(null)
		binding?.let { bound ->
			listOf(
				bound.root,
				bound.metricsTitle,
				bound.metricsPrevious,
				bound.metricsNext,
				bound.metricsSnapshot,
				bound.metricsBattery,
				bound.metricsUndockedMessage,
			).forEach { control ->
				control.setOnLongClickListener(null)
				// setOnLongClickListener(null) leaves isLongClickable set, so the view would still
				// claim a long press it no longer answers.
				control.isLongClickable = false
			}
		}
		binding?.metricsPrevious?.setOnClickListener(null)
		binding?.metricsNext?.setOnClickListener(null)
		pageCallback?.let { binding?.metricsPager?.unregisterOnPageChangeCallback(it) }
		pageCallback = null

		binding?.metricsPager?.adapter = null
		memoryRenderer.detach()
		networkRenderer.detach()
		powerRenderer.detach()
		binding = null
	}

	/**
	 * Moves the carousel by [delta] pages, stopping at either end.
	 */
	@UiThread
	private fun step(delta: Int) {
		val pager = binding?.metricsPager ?: return
		val target = (pager.currentItem + delta).coerceIn(0, pages.lastIndex)
		if (target != pager.currentItem) {
			pager.setCurrentItem(target, true)
		}
	}

	/**
	 * Dims the arrow that has nowhere to go, so the ends of the carousel are visible.
	 */
	@UiThread
	private fun updateArrows(position: Int) {
		val binding = this.binding ?: return
		binding.metricsPrevious.isEnabled = position > 0
		binding.metricsNext.isEnabled = position < pages.lastIndex
		binding.metricsPrevious.alpha = if (position > 0) 1f else DISABLED_ARROW_ALPHA
		binding.metricsNext.alpha = if (position < pages.lastIndex) 1f else DISABLED_ARROW_ALPHA
	}

	/**
	 * Shows the battery level beside the power chart, and nowhere else (ADFA-5499).
	 *
	 * It is a readout rather than a plotted series because the level moves about a percent every few
	 * minutes: over the chart's window a line would be flat, spending an axis on a constant.
	 */
	@UiThread
	private fun updateBatteryReadout() {
		val binding = this.binding ?: return
		val onPowerPage = pages.getOrNull(binding.metricsPager.currentItem) is MetricsPage.PowerChart
		val readout = if (onPowerPage) powerRenderer.batteryReadout() else null

		binding.metricsBattery.text = readout.orEmpty()
		binding.metricsBattery.isVisible = readout != null
	}

	/**
	 * The renderer behind the page currently on screen, or `null` when nothing is bound.
	 */
	private fun currentRenderer(): MetricsChartRenderer? {
		val binding = this.binding ?: return null
		return when (pages.getOrNull(binding.metricsPager.currentItem)) {
			is MetricsPage.MemoryChart -> memoryRenderer
			is MetricsPage.NetworkChart -> networkRenderer
			is MetricsPage.PowerChart -> powerRenderer
			null -> null
		}
	}

	/**
	 * Offers the sampling rates this device supports, and shows the ones it does not so the reason
	 * is visible rather than the faster rates simply being absent (ADFA-5486).
	 */
	@UiThread
	fun showSamplingRateDialog() {
		val context = binding?.root?.context ?: return
		val rates = MetricsSamplingRates.ratesFor(IDEBuildConfigProvider.getInstance().deviceArch)
		val current = memoryUsageWatcher.updateInterval

		val labels =
			rates
				.map { rate ->
					val label = context.getString(string.metrics_sampling_rate_entry, formatInterval(rate.intervalMillis))
					if (rate.isAvailable) label else context.getString(string.metrics_sampling_rate_unavailable, label)
				}.toTypedArray<CharSequence>()

		val checked = rates.indexOfFirst { it.intervalMillis == current }

		// A choice adapter that knows which rows are selectable, rather than reaching into the
		// list's laid-out children afterwards: getChildAt only sees rows that already exist, and a
		// recycled row comes back enabled, so an unavailable rate could look selectable and then
		// silently do nothing.
		val adapter =
			object : ArrayAdapter<CharSequence>(
				context,
				android.R.layout.simple_list_item_single_choice,
				android.R.id.text1,
				labels,
			) {
				override fun areAllItemsEnabled(): Boolean = false

				override fun isEnabled(position: Int): Boolean = rates.getOrNull(position)?.isAvailable ?: false

				override fun getView(
					position: Int,
					convertView: View?,
					parent: ViewGroup,
				): View =
					super.getView(position, convertView, parent).apply {
						isEnabled = isEnabled(position)
						alpha = if (isEnabled) 1f else UNAVAILABLE_RATE_ALPHA
					}
			}

		val dialog =
			DialogUtils
				.newMaterialDialogBuilder(context)
				.setTitle(string.metrics_sampling_rate_title)
				.setSingleChoiceItems(adapter, checked) { dismissable, which ->
					val rate = rates[which]
					if (rate.isAvailable) {
						setSamplingInterval(rate.intervalMillis)
						dismissable.dismiss()
					}
					// An unavailable rate stays listed and does nothing; the message below says why.
				}
				// No setMessage: an AlertDialog shows either a message or a list, never both, and
				// the message silently wins. The unavailable entries carry the explanation instead.
				.setNegativeButton(string.cancel) { dismissable, _ -> dismissable.dismiss() }
				// A dialog has no free surface to long-press, so help is a button here rather than a
				// gesture. It does not dismiss: the point is to read it and then choose a rate.
				.setNeutralButton(string.help, null)
				.show()

		dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { helpAnchor ->
			showIdeCategoryTooltipIfPresent(context, helpAnchor, TooltipTag.CAROUSEL_RATE)
		}
	}

	/**
	 * Applies a new sampling interval to both watchers. Their histories are discarded, because a
	 * buffer holding samples taken at two rates would misdate the older ones.
	 */
	@UiThread
	private fun setSamplingInterval(intervalMillis: Long) {
		memoryUsageWatcher.updateInterval = intervalMillis
		networkUsageWatcher.updateInterval = intervalMillis
		powerUsageWatcher.updateInterval = intervalMillis
		refresh()
	}

	private fun formatInterval(intervalMillis: Long): String =
		if (intervalMillis < 1_000L) {
			"%.1fs".format(intervalMillis / 1000.0)
		} else {
			"%ds".format(intervalMillis / 1_000L)
		}

	/**
	 * Writes the visible chart to an image and offers it to another app (ADFA-5486).
	 *
	 * The bitmap has to be taken on the UI thread -- it is a copy of what the chart drew -- but
	 * encoding and writing the PNG must not be. That is a directory listing, a delete and a file
	 * write behind a full-chart encode, all of which used to run inside the click listener.
	 *
	 * @return whether a snapshot could be started. The write itself completes later.
	 */
	@UiThread
	fun exportSnapshot(): Boolean {
		val binding = this.binding ?: return false
		val context = binding.root.context
		val position = binding.metricsPager.currentItem
		val page = pages.getOrNull(position) ?: return false

		val renderer =
			when (page) {
				is MetricsPage.MemoryChart -> memoryRenderer
				is MetricsPage.NetworkChart -> networkRenderer
				is MetricsPage.PowerChart -> powerRenderer
			}

		val label = context.getString(page.title)
		val bitmap = renderer.snapshot()
		if (bitmap == null) {
			Toast.makeText(context, string.msg_metrics_snapshot_failed, Toast.LENGTH_SHORT).show()
			return false
		}

		// The write takes the application context because it outlives the click. The share does not:
		// it ends in startActivity, which throws from a context with no task of its own unless it is
		// given FLAG_ACTIVITY_NEW_TASK, so it keeps the context the carousel is hosted in.
		val appContext = context.applicationContext
		scope.launch {
			val file = withContext(Dispatchers.IO) { MetricsSnapshot.write(appContext, bitmap, label) }
			if (file == null) {
				Toast.makeText(appContext, string.msg_metrics_snapshot_failed, Toast.LENGTH_SHORT).show()
				return@launch
			}
			// Re-read the host rather than capturing it: the export is no longer instantaneous, and
			// the carousel can be unbound (docked, undocked, recreated) while the file is written.
			val host = binding?.root?.context
			if (host == null) {
				Toast.makeText(appContext, string.msg_metrics_snapshot_failed, Toast.LENGTH_SHORT).show()
				return@launch
			}
			IntentUtils.shareFile(host, file, MetricsSnapshot.MIME_TYPE)
		}
		return true
	}

	/**
	 * Releases the controller for good. Distinct from [unbind], which runs on every dock, undock
	 * and recreation; this is the terminal teardown and cancels any snapshot still being written.
	 */
	@UiThread
	fun close() {
		unbind()
		scope.cancel()
	}

	/**
	 * Redraws both charts from the full history, for a host coming back to the foreground with
	 * samples gathered while it was away.
	 */
	@UiThread
	fun refresh() {
		memoryRenderer.rebuild()
		networkRenderer.rebuild()
		powerRenderer.rebuild()
	}

	/**
	 * Rebuilds the memory chart for a changed set of watched processes.
	 */
	@UiThread
	fun onWatchedProcessesChanged() {
		memoryRenderer.rebuild()
	}

	private companion object {
		const val DISABLED_ARROW_ALPHA = 0.35f

		/** Dims a rate this device cannot offer, so the list shows what the hardware costs. */
		const val UNAVAILABLE_RATE_ALPHA = 0.4f
	}
}
