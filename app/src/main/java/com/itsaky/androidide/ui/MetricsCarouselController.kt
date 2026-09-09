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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.ColorStateList
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.viewpager2.widget.ViewPager2
import com.itsaky.androidide.R
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider
import com.itsaky.androidide.databinding.LayoutMemUsageBinding
import com.itsaky.androidide.floating.window.OverlayDialogs
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.IntentUtils
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.MetricsAnnotationStore
import com.itsaky.androidide.utils.MetricsCsv
import com.itsaky.androidide.utils.MetricsCsvFile
import com.itsaky.androidide.utils.MetricsSamplingRates
import com.itsaky.androidide.utils.MetricsSnapshot
import com.itsaky.androidide.utils.MetricsSnapshotAssembler
import com.itsaky.androidide.utils.NetworkUsageWatcher
import com.itsaky.androidide.utils.PowerUsageWatcher
import com.itsaky.androidide.utils.clearLongPressHelp
import com.itsaky.androidide.utils.displayTooltipOnLongPress
import com.itsaky.androidide.utils.showIdeCategoryTooltipIfPresent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

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
	private val annotations: MetricsAnnotationStore? = null,
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
			sampleInterval = { networkUsageWatcher.updateInterval },
		)

	private val powerRenderer =
		PowerUsageChartRenderer(
			usageProvider = { powerUsageWatcher.getUsage() },
			batteryProvider = { powerUsageWatcher.latestBattery },
			annotations = annotations,
			sampleIntervalMillis = { powerUsageWatcher.updateInterval },
		)

	/**
	 * The carousel's pages, in order.
	 *
	 * Declared after the renderers, not before: a page holds its own renderer, and Kotlin
	 * initialises properties in declaration order, so listing the pages first read powerRenderer
	 * while it was still null.
	 */
	private val pages: List<MetricsPage> =
		listOf(
			// The memory chart is the default page (ADFA-5487); network traffic is the second
			// (ADFA-5489), replacing the brand-mark placeholder that ADFA-5487 shipped.
			ChartPage(
				title = string.metrics_title_memory,
				contentDescription = string.metrics_carousel_memory_chart,
				renderer = memoryRenderer,
			),
			ChartPage(
				title = string.metrics_title_network,
				contentDescription = string.metrics_network_chart,
				renderer = networkRenderer,
			),
			ChartPage(
				title = string.metrics_title_power,
				contentDescription = string.metrics_power_chart,
				renderer = powerRenderer,
			),
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
	 * The page the user is on, kept across bind and unbind.
	 *
	 * The pager itself cannot hold it: docking and undocking inflate a fresh layout and a fresh
	 * ViewPager2, which starts at zero. Without this, undocking while reading the network chart
	 * put the floating window on the memory chart.
	 */
	private var currentPage = 0

	/**
	 * Whether a PNG snapshot is already being written.
	 *
	 * One at a time. The camera button is not debounced and each tap launched its own coroutine,
	 * so two quick taps raced over the same scratch directory -- and, within the same second, over
	 * the same filename, since the name is the chart label and a whole-second timestamp. Touched
	 * only on the main thread, which is where both the tap and the coroutine's continuations run.
	 *
	 * A rapid second tap of the same button is refused silently: it is the double tap this exists
	 * to swallow, and a message for it would be noise on the gesture a user did not mean to make.
	 */
	private var snapshotInFlight = false

	/**
	 * Whether a CSV export is already being written, tracked apart from [snapshotInFlight].
	 *
	 * The two write different files into different directories and cannot race each other, so one
	 * flag for both only meant that starting a ten-thousand-row export refused the camera button
	 * for as long as it ran -- and refused it silently, which reads as a dead control.
	 */
	private var csvExportInFlight = false

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

		binding.metricsPager.adapter = MetricsCarouselAdapter(pages)

		// The arrows carry their colour from app:tint, which only AppCompat applies -- and only
		// when AppCompat's factory is on the inflater. The floating window inflates from a plain
		// window context, so there it produced an ordinary ImageButton, app:tint was ignored, and
		// the vector's own android:tint="#000000" took over: black arrows on a near-black strip.
		// Setting the tint here works whichever inflater built the view.
		tintArrows(binding)

		// Before the page callback is registered, so restoring does not fire it. Docking and
		// undocking rebind the carousel, and a rebind used to drop the user back on the first
		// page: undocking while reading the network chart showed them the memory chart instead.
		binding.metricsPager.setCurrentItem(currentPage, false)

		val showTitleFor = { position: Int ->
			pages.getOrNull(position)?.let { page ->
				binding.metricsTitle.setText(page.title)
			}
		}

		pageCallback =
			object : ViewPager2.OnPageChangeCallback() {
				override fun onPageSelected(position: Int) {
					currentPage = position
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
		binding.metricsExport.setOnClickListener { exportCsv() }

		// Arrows are the dependable way to move between pages: a swipe has to share the gesture
		// with panning a zoomed chart and with the editor's drawer, and loses often enough to be
		// annoying.
		binding.metricsPrevious.setOnClickListener { step(-1) }
		binding.metricsNext.setOnClickListener { step(1) }
		// After the click listeners, which set isClickable themselves.
		ViewCompat.setAccessibilityDelegate(binding.metricsPrevious, arrowAccessibilityDelegate)
		ViewCompat.setAccessibilityDelegate(binding.metricsNext, arrowAccessibilityDelegate)
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
		helpTargets(binding).forEach { (view, tag) ->
			view.displayTooltipOnLongPress(context, tag)
		}
	}

	/**
	 * Every control that answers a long press, and the tag it answers with.
	 *
	 * One list drives the wiring, the unwiring and the test, because three hand-maintained copies
	 * is how a control added later gets help on binding and keeps a stale listener after unbinding.
	 *
	 * The charts are absent on purpose: MPAndroidChart swallows the touch events a view-level long
	 * press needs, so each renderer answers through the chart's own gesture listener instead.
	 */
	@VisibleForTesting
	internal fun helpTargets(binding: LayoutMemUsageBinding): List<Pair<View, String>> =
		listOf(
			// The strip itself, for the gaps its children do not cover.
			binding.root to TooltipTag.CAROUSEL_PANEL,
			binding.metricsTitle to TooltipTag.CAROUSEL_TITLE,
			binding.metricsPrevious to TooltipTag.CAROUSEL_PREVIOUS,
			binding.metricsNext to TooltipTag.CAROUSEL_NEXT,
			binding.metricsSnapshot to TooltipTag.CAROUSEL_SNAPSHOT,
			binding.metricsExport to TooltipTag.CAROUSEL_EXPORT,
			binding.metricsBattery to TooltipTag.CAROUSEL_BATTERY,
			// Wired even though it is only visible while undocked: the message is the one control
			// that outlives unbind(), so its help must not be torn down with the rest.
			binding.metricsUndockedMessage to TooltipTag.CAROUSEL_UNDOCKED,
		)

	/**
	 * Unbinds only if [binding] is still the bound one.
	 *
	 * The undock and redock paths are two independent collectors of the same DockingManager
	 * emission with nothing ordering them, so the editor can rebind this controller to its own
	 * views before the floating window's onDestroyView runs. An unconditional unbind there stripped
	 * the editor's freshly bound carousel -- adapter null, listeners cleared, renderers detached --
	 * leaving a dead strip until the next onResume. Every sibling teardown here is identity-guarded
	 * for the same reason.
	 */
	@UiThread
	fun unbindIfBoundTo(binding: LayoutMemUsageBinding) {
		if (this.binding === binding) {
			unbind()
		}
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
		binding?.metricsExport?.setOnClickListener(null)
		binding?.let { bound ->
			helpTargets(bound)
				// All but the undocked message: that view becomes visible *because* the carousel
				// unbound, so clearing its listener here left the one control the user can still
				// reach with no help at all.
				.filterNot { (view, _) -> view === bound.metricsUndockedMessage }
				.map { (view, _) -> view }
				.forEach(View::clearLongPressHelp)
		}
		binding?.metricsPrevious?.setOnClickListener(null)
		binding?.metricsNext?.setOnClickListener(null)
		binding?.metricsPrevious?.let { ViewCompat.setAccessibilityDelegate(it, null) }
		binding?.metricsNext?.let { ViewCompat.setAccessibilityDelegate(it, null) }
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
	 * Colours both arrows from the theme, rather than trusting the layout's `app:tint`.
	 *
	 * Falls back to the title's own colour if the attribute does not resolve: a window context
	 * carrying a different theme is exactly the case this is here for, and an unresolved colour
	 * attribute comes back as 0 -- transparent -- rather than as an error.
	 */
	@UiThread
	private fun tintArrows(binding: LayoutMemUsageBinding) {
		val fallback = binding.metricsTitle.currentTextColor
		val value = TypedValue()
		val color =
			if (binding.root.context.theme
					.resolveAttribute(R.attr.colorOnSurface, value, true)
			) {
				value.data
			} else {
				fallback
			}
		ImageViewCompat.setImageTintList(binding.metricsPrevious, ColorStateList.valueOf(color))
		ImageViewCompat.setImageTintList(binding.metricsNext, ColorStateList.valueOf(color))
	}

	/**
	 * Dims the arrow that has nowhere to go, so the ends of the carousel are visible.
	 */
	@UiThread
	private fun updateArrows(position: Int) {
		val binding = this.binding ?: return
		setPagingAvailable(binding.metricsPrevious, available = position > 0)
		setPagingAvailable(binding.metricsNext, available = position < pages.lastIndex)
	}

	/**
	 * Marks an arrow as leading somewhere, or not.
	 *
	 * Deliberately not `isEnabled`. A disabled View still consumes a touch and then drops it
	 * without calling any listener, so a long press on the arrow at either end of the carousel
	 * showed no tooltip -- and that is the arrow whose greying-out a user is likeliest to ask
	 * about. [isClickable] is the narrower statement and the true one: the arrow does not answer a
	 * tap, but it does answer a long press. [step] clamps anyway, so a tap on a dimmed arrow was
	 * already a no-op.
	 *
	 * Alpha alone would have lost the state for anyone who cannot see it, since a screen reader
	 * reads a node's flags rather than its opacity. [arrowAccessibilityDelegate] puts it back.
	 */
	@UiThread
	private fun setPagingAvailable(
		arrow: View,
		available: Boolean,
	) {
		arrow.alpha = if (available) 1f else DIMMED_ARROW_ALPHA
		arrow.isClickable = available
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
		val renderer = currentRenderer()
		val readout = renderer?.readout()

		binding.metricsBattery.text = readout.orEmpty()
		// Not on a page that has no readout, and not while the carousel is undocked: this runs on
		// every page change and every refresh, so without the second test the next battery tick
		// put the readout back over the "tap to bring them back" message.
		binding.metricsBattery.isVisible = readout != null && !binding.root.isUndocked

		// lineHeight rather than the measured height: this runs on bind, before the readout has
		// been laid out, and it is the text's own size that grows with the font scale.
		val reserved =
			if (readout == null) {
				0f
			} else {
				binding.metricsBattery.lineHeight + binding.metricsBattery.paddingTop.toFloat()
			}
		renderer?.reserveTopSpace(reserved)
	}

	/**
	 * The renderer behind the page currently on screen, or `null` when nothing is bound.
	 */
	private fun currentRenderer(): MetricsChartRenderer? {
		val binding = this.binding ?: return null
		return pages.getOrNull(binding.metricsPager.currentItem)?.renderer
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
				.create()

		// Not builder.show(): while the carousel is floating, `context` is the overlay window's
		// context, which carries no activity token -- adding an ordinary application window
		// against it throws BadTokenException. OverlayDialogs raises the dialog to the overlay
		// window type first, which also puts it above the floating windows instead of behind them.
		OverlayDialogs.show(dialog)

		// After show: an AlertDialog has no buttons to reach before then.
		dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { helpAnchor ->
			// No haptic: this is a plain tap, and the default buzz is the platform's long-press
			// feedback, which would mis-signal what the user just did.
			showIdeCategoryTooltipIfPresent(context, helpAnchor, TooltipTag.CAROUSEL_RATE, playHapticFeedback = false)
		}
	}

	/**
	 * Applies a new sampling interval to every watcher. Their histories are discarded, because a
	 * buffer holding samples taken at two rates would misdate the older ones.
	 */
	@UiThread
	private fun setSamplingInterval(intervalMillis: Long) {
		// Clamped to what this device supports, which is decided here rather than in the watchers:
		// the arch comes from IDEBuildConfigProvider, which a plain JVM test cannot resolve, so the
		// watchers keep only an absolute floor to stop delay() spinning. This is the policy.
		val supported =
			MetricsSamplingRates.coerceToSupportedRange(
				intervalMillis,
				IDEBuildConfigProvider.getInstance().deviceArch,
			)
		// Whether anything actually changes, because the clear below must not run when nothing
		// does: each watcher's setter returns early on an unchanged value, so re-picking the rate
		// already in effect -- which the dialog allows, and which a user opening it to read the
		// options does -- cleared every build marker off a chart whose samples were untouched.
		val changed = memoryUsageWatcher.updateInterval != supported
		memoryUsageWatcher.updateInterval = supported
		networkUsageWatcher.updateInterval = supported
		powerUsageWatcher.updateInterval = supported
		if (changed) {
			// The annotations go with the samples they annotate. Left behind, task markers stood
			// over a flat zero line with nothing to mark -- and this is the only route by which the
			// store's throttle window is ever reset.
			annotations?.clear()
		}
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
		if (snapshotInFlight) {
			log.debug("Ignoring a snapshot request while one is already being written")
			return false
		}
		val context = binding.root.context
		val position = binding.metricsPager.currentItem
		val page = pages.getOrNull(position) ?: return false

		val renderer = page.renderer

		val label = context.getString(page.title)
		val bitmap = renderer.snapshot()
		if (bitmap == null) {
			// The application context, not the host: a toast's window is added against whatever
			// context built it, and a floating window's context fixes a window type a toast
			// cannot use.
			Toast.makeText(context.applicationContext, string.msg_metrics_snapshot_failed, Toast.LENGTH_SHORT).show()
			return false
		}

		// The write takes the application context because it outlives the click. The share does not:
		// it ends in startActivity, which throws from a context with no task of its own unless it is
		// given FLAG_ACTIVITY_NEW_TASK, so it keeps the context the carousel is hosted in.
		val appContext = context.applicationContext
		snapshotInFlight = true
		scope.launch {
			// Everything here is guarded: the scope has no exception handler, so anything escaping
			// reaches the global crash reporter and is filed as a crash. MetricsSnapshot.write
			// converts only IOException, and shareFile ends in startActivity, which throws
			// ActivityNotFoundException on a device with nothing able to receive an image.
			runCatching {
				val file =
					withContext(Dispatchers.IO) {
						// Recycled as soon as it has been encoded: getChartBitmap hands back a
						// fresh full-size ARGB_8888 copy of the plot on every tap, which is
						// megabytes that would otherwise sit around until the collector noticed.
						try {
							MetricsSnapshot.write(appContext, bitmap)
						} finally {
							bitmap.recycle()
						}
					}
				// Read through the property, not the local captured above: the export is no longer
				// instantaneous, and the carousel can be unbound or rebound while the file is
				// written, which would leave the share pointed at a dead host.
				val host = this@MetricsCarouselController.binding?.root?.context
				if (file == null || host == null) {
					Toast.makeText(appContext, string.msg_metrics_snapshot_failed, Toast.LENGTH_SHORT).show()
					return@runCatching
				}
				// A floating window's context has no task, so startActivity needs NEW_TASK there.
				// Docked, the host is the activity and the flag would change its task affinity.
				val extraFlags =
					if (host.findActivityOrNull() == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0
				IntentUtils.shareFile(host, file, MetricsSnapshot.MIME_TYPE, extraFlags)
			}.onFailure { failure ->
				if (failure is CancellationException) {
					// Cleared before rethrowing: a cancelled export is finished either way, and
					// leaving the flag set would refuse every later one for the life of the
					// carousel.
					snapshotInFlight = false
					throw failure
				}
				log.error("Could not share the chart snapshot", failure)
				Toast.makeText(appContext, string.msg_metrics_snapshot_failed, Toast.LENGTH_SHORT).show()
			}
			snapshotInFlight = false
		}
		return true
	}

	/**
	 * Writes every retained sample to a CSV file and offers it to another app (ADFA-5531).
	 *
	 * The whole buffer, not the visible window and not the current page: this is the file the
	 * metrics format is defined around, and ADFA-5494, ADFA-5526 and ADFA-5534 all want everything
	 * there is. Assembled on the UI thread because it is snapshots of the watchers' buffers, then
	 * formatted and written off it -- ten thousand rows is not a click listener's work.
	 *
	 * @return whether an export could be started. The write itself completes later.
	 */
	@UiThread
	fun exportCsv(): Boolean {
		val binding = this.binding ?: return false
		if (csvExportInFlight) {
			log.debug("Ignoring an export request while one is already being written")
			return false
		}

		val context = binding.root.context
		val appContext = context.applicationContext
		val snapshot = snapshot(context)
		csvExportInFlight = true
		scope.launch {
			// Guarded for the same reason exportSnapshot is: the scope has no exception handler, so
			// anything escaping here is filed as a crash.
			runCatching {
				val file = withContext(Dispatchers.IO) { MetricsCsvFile.write(appContext, snapshot) }
				val host = this@MetricsCarouselController.binding?.root?.context
				if (file == null || host == null) {
					Toast.makeText(appContext, string.msg_metrics_export_failed, Toast.LENGTH_SHORT).show()
					return@runCatching
				}
				val extraFlags =
					if (host.findActivityOrNull() == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0
				IntentUtils.shareFile(host, file, MetricsCsv.MIME_TYPE, extraFlags)
			}.onFailure { failure ->
				if (failure is CancellationException) {
					csvExportInFlight = false
					throw failure
				}
				log.error("Could not share the metrics export", failure)
				Toast.makeText(appContext, string.msg_metrics_export_failed, Toast.LENGTH_SHORT).show()
			}
			csvExportInFlight = false
		}
		return true
	}

	/**
	 * The watchers' buffers, for the export.
	 *
	 * The assembly itself is [MetricsSnapshotAssembler]: the same file is wanted by things that
	 * have no carousel bound at all (ADFA-5534, ADFA-5526), so it cannot live here.
	 */
	@UiThread
	@VisibleForTesting
	internal fun snapshot(context: Context): MetricsCsv.Snapshot =
		MetricsSnapshotAssembler.assemble(
			context = context,
			memory = memoryUsageWatcher,
			network = networkUsageWatcher,
			power = powerUsageWatcher,
			annotations = annotations,
		)

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
	 * Redraws every chart from the full history, for a host coming back to the foreground with
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
		private val log = LoggerFactory.getLogger(MetricsCarouselController::class.java)

		/** The nearest [Activity] up the context chain, or `null` for a window context. */
		private tailrec fun Context.findActivityOrNull(): Activity? =
			when (this) {
				is Activity -> this
				is ContextWrapper -> baseContext.findActivityOrNull()
				else -> null
			}

		const val DIMMED_ARROW_ALPHA = 0.35f

		/**
		 * Reports an arrow that leads nowhere as disabled, and as offering no tap.
		 *
		 * The views stay touch-enabled so they can still answer a long press with their tooltip
		 * (see [setPagingAvailable]); without this, TalkBack would offer "double-tap to activate"
		 * on an arrow that does nothing, and give no hint that the carousel has an end. Reads
		 * [View.isClickable] rather than holding its own copy, so there is one source of truth.
		 */
		val arrowAccessibilityDelegate =
			object : AccessibilityDelegateCompat() {
				override fun onInitializeAccessibilityNodeInfo(
					host: View,
					info: AccessibilityNodeInfoCompat,
				) {
					super.onInitializeAccessibilityNodeInfo(host, info)
					info.isEnabled = host.isClickable
					info.isClickable = host.isClickable
				}
			}

		/** Dims a rate this device cannot offer, so the list shows what the hardware costs. */
		const val UNAVAILABLE_RATE_ALPHA = 0.4f
	}
}
