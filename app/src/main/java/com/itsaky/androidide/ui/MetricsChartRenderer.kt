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
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.annotation.CallSuper
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.utils.MetricsAnnotationStore
import com.itsaky.androidide.utils.resolveAttr
import com.itsaky.androidide.utils.showIdeCategoryTooltipIfPresent
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
abstract class MetricsChartRenderer(
	// A provider, not a value: the sampling rate is user-settable, and a captured interval leaves
	// the axis labelling ages with the old spacing -- reading -54s where the sample is really 295
	// seconds old.
	private val sampleIntervalMillis: () -> Long,
	private val annotations: MetricsAnnotationStore? = null,
	private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) {
	/**
	 * Invoked when the chart's x axis is tapped, which opens the sampling-rate chooser
	 * (ADFA-5486). Set by the host; the axis band is worked out here because only the chart knows
	 * where it drew it.
	 */
	var onXAxisTap: (() -> Unit)? = null

	/**
	 * The help tag for this page's plot, shown on a long press (ADFA-5510).
	 *
	 * Routed through the chart's own gesture listener rather than [android.view.View.setOnLongClickListener]:
	 * MPAndroidChart's `BarLineChartBase.onTouchEvent` hands the event to its touch listener and
	 * never calls `super`, so the framework's long-press detection never runs and a view listener
	 * would be installed, look wired, and never fire.
	 */
	protected abstract val helpTag: String

	/**
	 * The help tag for a long press at [y], or `null` if this page has none.
	 *
	 * Separated from showing the tooltip so it can be tested: TooltipManager reads the docs
	 * database from device storage in its static initialiser and cannot be loaded off-device.
	 */
	@VisibleForTesting
	internal fun helpTagAt(y: Float): String? {
		// The axis band answers for the sampling rate, the plot for the metric itself, matching
		// where a tap goes.
		return if (isOnAxisBand(y)) TooltipTag.CAROUSEL_AXIS_TIME else helpTag
	}

	/**
	 * Whether [y] landed on the x axis band rather than in the plot.
	 *
	 * One predicate, because the tap that opens the sampling-rate chooser and the long press that
	 * explains it have to agree on where that band is: written twice, they can drift apart and the
	 * tooltip then describes a control the tap no longer reaches.
	 *
	 * Bounded below, not just above. Everything under the plot used to count, and the legend lives
	 * there too -- MPAndroidChart aligns it to the bottom by default, under the axis labels. So
	 * tapping the legend, which is the one thing in a chart a reader expects to be tappable, opened
	 * the sampling-rate chooser; picking a rate there clears every buffer, and the user loses the
	 * history they were looking at for an action they did not ask for.
	 *
	 * The band stops at the legend's top edge, and is never narrower than one axis label, so a
	 * legend that measures larger than expected cannot squeeze the rate chooser out of reach.
	 */
	private fun isOnAxisBand(y: Float): Boolean {
		val chart = this.chart ?: return false
		val top = chart.viewPortHandler.contentBottom()
		val legend = chart.legend
		// What the chart reserves for the legend at the bottom: its measured height plus the
		// offset it keeps above itself. Both are pixels, as MPAndroidChart stores them.
		val reservedForLegend = if (legend.isEnabled) legend.mNeededHeight + legend.yOffset else 0f
		val bottom = maxOf(chart.height - reservedForLegend, top + chart.xAxis.textSize)
		return y >= top && y < bottom
	}

	/**
	 * Whether the user has pinched this chart.
	 *
	 * Recorded from the scale gesture rather than read back from the chart. Showing a window of
	 * [VISIBLE_SAMPLES] out of a buffer of thousands *is* a zoom as far as the chart is concerned --
	 * scaleX sits around 166 at rest -- so testing scaleX for "has the user zoomed" is always true,
	 * which silently disabled the auto-follow window and handed every horizontal drag to the chart.
	 */
	private var userHasZoomed = false

	/**
	 * The attached chart, or `null` when no carousel page is bound to this renderer.
	 */
	protected var chart: SafeLineChart? = null
		private set

	/**
	 * A short readout to show beside this page's chart, or `null` if it has none.
	 *
	 * Asked of the renderer rather than decided from the page's type, so the carousel does not
	 * have to know which of its pages happens to have a battery on it.
	 */
	@UiThread
	open fun readout(): String? = null

	/**
	 * Keeps [pixels] of the chart's top clear of the plot and its labels.
	 *
	 * The battery readout is anchored to the pager's top-right corner, over the chart, where the
	 * right axis prints its topmost label. At the default font scale the readout sits above the
	 * plot and the two do not meet; the strip is a fixed height, so at a 2.0 font scale the
	 * readout grows down into the plot and hides that label. Reserving its height moves the plot
	 * instead, which scales with the text rather than against it.
	 */
	@UiThread
	fun reserveTopSpace(pixels: Float) {
		val chart = this.chart ?: return
		chart.setExtraTopOffset(pixels / chart.resources.displayMetrics.density)
		// setExtraTopOffset only stores the value; the viewport is recomputed by calculateOffsets,
		// which is protected and otherwise runs only when the chart's size changes.
		chart.notifyDataSetChanged()
		chart.invalidate()
	}

	/**
	 * Attaches [chart], applies configuration, and renders the full current history.
	 */
	@UiThread
	fun attach(chart: SafeLineChart) {
		// A rebind can attach the replacement before the view it replaced is recycled, so the
		// outgoing chart is let go of here rather than waiting for a [detachIfAttached] that, by
		// then, no longer names it.
		//
		// The whole teardown, not just the listener. [detach] also clears [userHasZoomed], and
		// releasing only the listener leaked it onto the replacement: a user who had panned once
		// got a chart whose follow-window was disabled for good, because showNewestWindow returns
		// early on the flag and every later redraw takes the same early return. That is the
		// oldest-samples symptom this ticket was filed for -- reachable only after a pan, which is
		// why the resume paths reproduce it and a fresh chart never does. Subclasses clear their own
		// per-chart state through the same override.
		this.chart?.let { outgoing -> if (outgoing !== chart) detach() }
		this.chart = chart
		configure(chart)
		chart.addOnLayoutChangeListener(newestWindowOnLayout)
		rebuild()
	}

	/**
	 * Detaches the current chart. Sample history is unaffected; a later [attach] renders it in full.
	 */
	@UiThread
	@CallSuper
	open fun detach() {
		userHasZoomed = false
		chart?.removeOnLayoutChangeListener(newestWindowOnLayout)
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
	 * Returns the chart to its unzoomed state.
	 */
	@UiThread
	fun resetZoom() {
		userHasZoomed = false
		chart?.fitScreen()
		chart?.let { showNewestWindow(it) }
	}

	/**
	 * An image of the chart as it currently looks, or `null` when nothing is attached
	 * (ADFA-5486's snapshot export).
	 */
	@UiThread
	fun snapshot(): Bitmap? = chart?.chartBitmap

	/**
	 * Applies the configuration every metrics chart shares. Subclasses override to add their own --
	 * a value formatter, axis range -- and must call through.
	 */
	@CallSuper
	protected open fun configure(chart: SafeLineChart) {
		chart.apply {
			val colorAccent = context.resolveAttr(R.attr.colorAccent)

			description.isEnabled = false
			xAxis.axisLineColor = colorAccent
			axisRight.axisLineColor = colorAccent

			// Zoom the time axis only. Zooming the value axis on a memory or throughput chart just
			// makes the numbers lie about their own scale; time is the axis worth magnifying.
			setScaleXEnabled(true)
			setScaleYEnabled(false)
			setPinchZoom(false)
			// Panning is what makes zoom usable: without it you magnify and are then stranded.
			// MetricsCarouselLayout decides per gesture whether a horizontal drag pans the chart or
			// pages the carousel.
			isDragEnabled = true
			setDoubleTapToZoomEnabled(false)

			setBackgroundColor(context.resolveAttr(R.attr.colorSurfaceDim))
			setDrawGridBackground(true)

			// Below the plot, which is also where a tap opens the sampling-rate chooser
			// (ADFA-5486). The two have to agree: they disagreed once, and the gesture was
			// unreachable at the labels it is named for.
			xAxis.position = XAxis.XAxisPosition.BOTTOM

			// The right axis carries the labels. The left is unused by every page but the one with
			// two units, which enables it in its own configure().
			axisLeft.isEnabled = false
			// The right axis rules the plot. Harmless while the left one is disabled, and it means
			// a page that enables the left for a second unit gets its labels without a second set
			// of grid lines at unrelated heights -- MPAndroidChart rules the plot once per enabled
			// axis, and AxisBase defaults to drawing them.
			axisLeft.setDrawGridLines(false)

			// A dot, not the 15dp square each renderer used to ask for per dataset (ADFA-5553):
			// the squares crowded the labels beside them and the axis below.
			//
			// On the legend, never on a dataset. LegendRenderer resolves each entry as
			// `isNaN(entry.formSize) ? legend.formSize : entry.formSize`, and takes the legend's
			// form only for an entry left at DEFAULT -- so a dataset that sets either one wins
			// silently. The size itself is set in [applyTextScale], which has to re-apply it.
			legend.form = Legend.LegendForm.CIRCLE
			// Kept at the 1f the renderers used to ask for. Inert while the form is a circle, but
			// leaving it NaN would silently adopt the library's 3f the day anyone chooses LINE.
			legend.formLineWidth = 1f

			onChartGestureListener = XAxisTapListener(this)

			xAxis.valueFormatter = ElapsedTimeFormatter(sampleIntervalMillis)
			// One label per 15 samples keeps the window readable without crowding.
			xAxis.granularity = X_LABEL_GRANULARITY_SAMPLES
			xAxis.isGranularityEnabled = true
		}
	}

	/**
	 * Scrolls the viewport to the newest samples, showing [VISIBLE_SAMPLES] of them.
	 *
	 * The watchers retain thousands of samples (ADFA-5486), far more than is legible at once in a
	 * 200dp strip and more than is cheap to draw -- MPAndroidChart clips drawing to the visible x
	 * range, so a window keeps the cost independent of how much is retained.
	 */
	private fun showNewestWindow(chart: SafeLineChart) {
		// Once the user has zoomed in, the view is theirs. Re-centring on every redraw would drag
		// them back to the newest samples once a second, which makes zooming useless.
		if (userHasZoomed) {
			return
		}

		// xMax is the newest sample's index. entryCount would be the total across every series --
		// 7200 for the network chart's two -- which would scroll the window off the end of the data.
		val newestIndex = chart.data?.xMax ?: return
		if (newestIndex < VISIBLE_SAMPLES) {
			return
		}

		// Before the first layout there is no plot area to place a window in, and applying one
		// anyway is worse than waiting: the scale is clamped against an empty content rect, and the
		// layout that follows resets the chart's transform. [newestWindowOnLayout] re-applies it as
		// soon as there is something to apply it to (ADFA-5515).
		if (!chart.viewPortHandler.hasChartDimens()) {
			return
		}

		chart.setVisibleXRangeMaximum(VISIBLE_SAMPLES.toFloat())
		// Not moveViewToX: its scroll is deferred to a later frame and would be converted through
		// a different transform from the scale just set here (ADFA-5515).
		chart.moveViewToXNow(newestIndex - VISIBLE_SAMPLES.toFloat() + 1f)
	}

	/**
	 * Re-applies the newest window whenever the chart is laid out.
	 *
	 * A layout that changes the chart's size resets its transform, which drops the window and shows
	 * the whole buffer from its oldest end. Nothing put it back until the next sample landed a
	 * redraw, so every rebind -- and the carousel is rebound on every resume -- opened on an empty
	 * plot for a second or more (ADFA-5515).
	 */
	private val newestWindowOnLayout =
		View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
			val chart = view as? SafeLineChart ?: return@OnLayoutChangeListener
			if (chart === this.chart) {
				showNewestWindow(chart)
			}
		}

	/**
	 * The sample indices currently on screen, for a series of [sampleCount] samples.
	 *
	 * The buffer holds thousands of samples and the window shows sixty of them, so anything derived
	 * from "all the data" -- an axis range, a peak -- describes a chart the user is not looking at.
	 *
	 * While the chart is following the newest samples this is [VISIBLE_SAMPLES] at the end of the
	 * buffer by definition; only once the user has pinched or panned is the chart itself asked.
	 */
	@VisibleForTesting
	internal fun visibleSampleRange(
		chart: SafeLineChart,
		sampleCount: Int,
	): IntRange {
		if (sampleCount <= 0) {
			return IntRange.EMPTY
		}

		// Until the user drives the viewport themselves, the window is exactly what
		// showNewestWindow put there, and saying so is both cheaper and more reliable than asking
		// the chart -- which reports the whole data range until it has been laid out and drawn.
		if (!userHasZoomed) {
			return (sampleCount - VISIBLE_SAMPLES).coerceAtLeast(0)..(sampleCount - 1)
		}

		val from = floor(chart.lowestVisibleX).toInt().coerceIn(0, sampleCount - 1)
		val to = ceil(chart.highestVisibleX).toInt().coerceIn(from, sampleCount - 1)
		return from..to
	}

	/**
	 * Turns a tap in the x-axis band into [onXAxisTap].
	 *
	 * The axis is drawn by the chart rather than being a view of its own, so there is nothing to
	 * attach a click listener to. `contentBottom` is the bottom of the plotting area and the axis
	 * is drawn below it (see [configure]), so a tap lower than that landed on the axis.
	 *
	 * This used to test `contentTop`, which put the only way to reach the sampling-rate chooser in
	 * an empty band at the *opposite* end of the chart from the labels it is named for. The strip
	 * under the plot had been left alone for the carousel swipe; paging is by the arrows now, so it
	 * is free.
	 */
	private inner class XAxisTapListener(
		private val chart: SafeLineChart,
	) : OnChartGestureListener {
		override fun onChartSingleTapped(me: MotionEvent?) {
			val y = me?.y ?: return
			if (isOnAxisBand(y)) {
				onXAxisTap?.invoke()
			}
		}

		override fun onChartGestureStart(
			me: MotionEvent?,
			lastPerformedGesture: ChartTouchListener.ChartGesture?,
		) = Unit

		override fun onChartGestureEnd(
			me: MotionEvent?,
			lastPerformedGesture: ChartTouchListener.ChartGesture?,
		) = Unit

		override fun onChartLongPressed(me: MotionEvent?) {
			val y = me?.y ?: return
			val tag = helpTagAt(y) ?: return
			// Haptic feedback left at its default, unlike every view-based help site, which
			// passes false. Those rely on View.performLongClick buzzing for them;
			// BarLineChartBase.onTouchEvent never calls super, so the framework's long press --
			// and its feedback -- never runs here and this is the only thing that provides it.
			showIdeCategoryTooltipIfPresent(chart.context, chart, tag)
		}

		override fun onChartDoubleTapped(me: MotionEvent?) = Unit

		override fun onChartFling(
			me1: MotionEvent?,
			me2: MotionEvent?,
			velocityX: Float,
			velocityY: Float,
		) = Unit

		override fun onChartScale(
			me: MotionEvent?,
			scaleX: Float,
			scaleY: Float,
		) {
			userHasZoomed = true
		}

		override fun onChartTranslate(
			me: MotionEvent?,
			dX: Float,
			dY: Float,
		) {
			// A pan is the user driving the viewport just as much as a pinch is. Left unrecorded,
			// showNewestWindow dragged them back to the newest samples on the next tick -- once a
			// second -- so panning a zoomed chart appeared not to work at all.
			userHasZoomed = true
		}
	}

	/**
	 * Labels the x axis by age rather than by sample index, which is meaningless to a reader and
	 * would run to 3599 at the current retention.
	 */
	private class ElapsedTimeFormatter(
		private val sampleIntervalMillis: () -> Long,
	) : IAxisValueFormatter {
		override fun getFormattedValue(
			value: Float,
			axis: AxisBase?,
		): String {
			val newestIndex = (axis?.mAxisMaximum ?: value)
			val secondsAgo = ((newestIndex - value) * sampleIntervalMillis() / 1000f).roundToLong()
			return if (secondsAgo <= 0L) "now" else "-%ds".format(secondsAgo)
		}
	}

	/**
	 * Installs [datasets] on [chart] and applies the theme colours, then redraws.
	 */
	protected fun setData(
		chart: SafeLineChart,
		datasets: Array<LineDataSet>,
		applyAxisRanges: (SafeLineChart) -> Unit = {},
	) {
		val bgColor = chart.context.resolveAttr(R.attr.colorSurfaceDim)
		val textColor = chart.context.resolveAttr(R.attr.colorOnSurface)

		chart.apply {
			data = LineData(*datasets)
			legend.textColor = textColor
			// MPAndroidChart defaults every component's text to Color.BLACK. The y axis and legend
			// were given a themed colour and the x axis never was, so its labels have always been
			// drawn black on a near-black surface -- which is the "x axis has no labels" of
			// ADFA-5486. They were there the whole time, just invisible.
			xAxis.textColor = textColor

			data.setValueTextColor(textColor)
			applyTextScale(this)
			styleValueAxes(this, textColor)
			setBackgroundColor(bgColor)
			setGridBackgroundColor(bgColor)
		}
		// Ranges first, then the notify. setting axisMinimum and axisMaximum only stores them;
		// what recomputes the axis values and the value-to-pixel transform is notifyDataSetChanged,
		// and it is protected against being called directly. Ranged after the notify -- as two of
		// the three renderers did -- the chart draws its next frame through a transform built from
		// the bounds MPAndroidChart picked for itself.
		applyAxisRanges(chart)
		chart.notifyDataSetChanged()
		applyAnnotations(chart)
		showNewestWindow(chart)
		chart.invalidate()
	}

	/**
	 * Sizes every piece of text the chart draws, following the system font scale up to a ceiling.
	 *
	 * MPAndroidChart sizes its text in dp, so nothing it draws responded to the font scale at all:
	 * a user who asked for larger text got it everywhere in the IDE except inside these plots,
	 * where the text is already the smallest on the screen (ADFA-5527).
	 *
	 * Followed only to [MAX_TEXT_SCALE], because a plot is dense by nature and the strip is a
	 * fixed [R.dimen.editor_mem_usage_view_height]. At the full 2.0 the axis labels collide with
	 * each other and the eight staggered annotation rows overlap, so honouring the scale
	 * literally would make the chart less readable rather than more. A ceiling gives most of the
	 * benefit and keeps the plot legible at the extreme.
	 */
	@UiThread
	private fun applyTextScale(chart: SafeLineChart) {
		val scale = textScaleFor(chart.context)
		chart.legend.textSize = BASE_TEXT_SIZE_DP * scale
		// Scaled with its label: a fixed dot beside text at 1.5 reads as though it were shrinking.
		// See [configure] for why the size is the legend's business and not a dataset's.
		chart.legend.formSize = BASE_LEGEND_FORM_DP * scale
		// The gaps go with them. Left fixed they close up as the text grows -- the same argument
		// as the dot, applied to the space around it.
		chart.legend.formToTextSpace = BASE_LEGEND_FORM_TO_TEXT_DP * scale
		chart.legend.xEntrySpace = BASE_LEGEND_ENTRY_SPACE_DP * scale
		chart.xAxis.textSize = BASE_TEXT_SIZE_DP * scale
		chart.axisLeft.textSize = BASE_TEXT_SIZE_DP * scale
		chart.axisRight.textSize = BASE_TEXT_SIZE_DP * scale
		chart.data?.setValueTextSize(BASE_VALUE_TEXT_SIZE_DP * scale)

		// Bigger text needs fewer labels. Growing the text alone left the count untouched, so the
		// memory page's nine value labels went from 29px apart to 6px -- crowded enough that the
		// change made the axis worse rather than better. The count is a hint: granularity still
		// has the last word, which is what keeps the temperature axis on whole degrees.
		val labels = (BASE_LABEL_COUNT / scale).roundToInt().coerceAtLeast(MIN_LABEL_COUNT)
		chart.axisLeft.setLabelCount(labels, false)
		chart.axisRight.setLabelCount(labels, false)
	}

	/**
	 * Colours the value axes' labels. Called from [setData], not [configure], because the styling
	 * here is re-applied on every redraw and would otherwise overwrite whatever a subclass had set
	 * up once at configuration time.
	 *
	 * The default paints both in the surface's text colour, which suits a page whose series all
	 * share one unit. A page with two unrelated axes overrides this.
	 */
	protected open fun styleValueAxes(
		chart: SafeLineChart,
		defaultTextColor: Int,
	) {
		chart.axisLeft.textColor = defaultTextColor
		chart.axisRight.textColor = defaultTextColor
	}

	/**
	 * Draws a vertical marker for each recent significant event (ADFA-5486).
	 *
	 * Annotations are stored by wall-clock time, not sample position, because the ring buffer
	 * shifts under them. Age converts to an x position here: the newest sample sits at the buffer's
	 * last index, and every [sampleIntervalMillis] before that is one index to the left. Anything
	 * older than the buffer holds falls outside the axis and is not drawn.
	 *
	 * Labels are staggered across [ANNOTATION_LABEL_SLOTS] rows. Gradle fires tasks in bursts, so
	 * several markers land within a few pixels of each other and their labels, all drawn on one
	 * row, overwrite each other into an unreadable smear.
	 */
	private fun applyAnnotations(chart: SafeLineChart) {
		val store = annotations ?: return
		val newestIndex = chart.data?.xMax ?: return

		chart.xAxis.removeAllLimitLines()

		val interval = sampleIntervalMillis()
		// Back as far as the oldest sample on screen, and no further. Spanning the whole buffer
		// meant building a LimitLine and a DashPathEffect for every annotation the store holds on
		// every redraw, almost all of them clipped off screen; spanning a fixed sixty-one samples
		// from now was wrong in the other direction, because a panned viewport shows older
		// samples than that and their markers were dropped before their x was worked out.
		val visible = visibleSampleRange(chart, newestIndex.toInt() + 1)
		val oldestVisibleIndex = if (visible.isEmpty()) newestIndex else visible.first.toFloat()
		val spanMillis = ((newestIndex - oldestVisibleIndex).toLong() + 1L) * interval
		val now = nowMillis()
		// Resolved once per redraw rather than once per annotation: applyAnnotations runs on every
		// sampling tick, there can be MAX_ANNOTATIONS of them, and resolveAttr allocates a
		// TypedValue per call.
		val markerColors = MetricsAnnotationStore.Kind.entries.associateWith { markerColorFor(chart, it) }

		store.recentAnnotations(spanMillis).forEach { annotation ->
			val samplesAgo = (now - annotation.atMillis).toFloat() / interval
			val x = newestIndex - samplesAgo
			if (x < 0f) {
				return@forEach
			}

			chart.xAxis.addLimitLine(
				LimitLine(x, labelFor(chart, annotation)).apply {
					val markerColor = markerColors.getValue(annotation.kind)
					lineWidth = ANNOTATION_LINE_WIDTH
					lineColor = markerColor
					textColor = markerColor
					enableDashedLine(ANNOTATION_DASH_LENGTH, ANNOTATION_DASH_LENGTH, 0f)
					labelPosition = LimitLine.LimitLabelPosition.RIGHT_BOTTOM
					// Rows are counted up from the bottom of the plot, and the offset is in dp:
					// LimitLine converts it on the way in.
					yOffset = annotationRowHeightFor(chart.context) * slotFor(annotation.sequence)
				},
			)
		}
	}

	/**
	 * An annotation's label, resolved now rather than when it was recorded.
	 *
	 * A build outcome carries a string id instead of text, so its marker follows the system
	 * language even though the store holding it outlives the activity that recorded it.
	 */
	private fun labelFor(
		chart: SafeLineChart,
		annotation: MetricsAnnotationStore.Annotation,
	): String = annotation.kind.labelRes?.let(chart.context::getString) ?: annotation.label

	/**
	 * The colour a marker is drawn in, from the kind of event it marks (ADFA-5509).
	 *
	 * Build outcomes are the events a user came to the chart for, so they get the theme's semantic
	 * colours -- success for a build starting or finishing, error for one that failed -- while the
	 * task markers that surround them stay in the ordinary text colour. Both the line and the label
	 * take it; colouring only the line would leave the label unreadable against a coloured rule.
	 */
	private fun markerColorFor(
		chart: SafeLineChart,
		kind: MetricsAnnotationStore.Kind,
	): Int {
		val attr =
			when (kind) {
				MetricsAnnotationStore.Kind.BUILD_STARTED,
				MetricsAnnotationStore.Kind.BUILD_FINISHED,
				-> R.attr.colorSuccess

				MetricsAnnotationStore.Kind.BUILD_FAILED -> R.attr.colorError

				// A cancel is the user's own doing, so it is neither good news nor bad.
				MetricsAnnotationStore.Kind.BUILD_CANCELLED,
				MetricsAnnotationStore.Kind.TASK,
				-> R.attr.colorOnSurface
			}
		// Not plain resolveAttr: it discards resolveAttribute's result and hands back TypedValue.data,
		// which for an attribute the theme does not carry is 0 -- transparent. colorSuccess is
		// ours rather than Material's, and a floating window is built against a window context
		// whose theme is not the activity's, so a build marker could come out invisible. It falls
		// back to the axis text colour, which configure has already set to something legible.
		return chart.context.resolveColorAttr(attr, fallback = chart.xAxis.textColor)
	}

	/**
	 * The colour [attr] names in this context's theme, or [fallback] if the theme has no such
	 * attribute.
	 */
	private fun Context.resolveColorAttr(
		attr: Int,
		fallback: Int,
	): Int {
		val value = TypedValue()
		return if (theme.resolveAttribute(attr, value, true)) value.data else fallback
	}

	/**
	 * The row an annotation's label sits on, cycling so that neighbours never share one.
	 */
	private fun slotFor(sequence: Long): Int = (sequence % ANNOTATION_LABEL_SLOTS).toInt()

	/**
	 * Redraws after the attached series have been mutated in place.
	 */
	protected fun redraw(
		chart: SafeLineChart,
		applyAxisRanges: (SafeLineChart) -> Unit = {},
	) {
		// Same order as [setData], and for the same reason: the bounds have to be in place before
		// the notify that turns them into a transform.
		applyAxisRanges(chart)
		// Re-read the font scale here too, not only in [setData]. EditorActivityKt declares
		// fontScale in configChanges, so the activity is never recreated for one -- and this is
		// the only path a running chart takes per sample. Left out, a live scale change moved the
		// annotation rows, which [applyAnnotations] re-reads below, while none of the text or the
		// legend dot it spaces them for ever grew.
		applyTextScale(chart)
		chart.apply {
			data.notifyDataChanged()
			notifyDataSetChanged()
		}
		// Re-applied on every redraw, not just when data is set: the visible x range is held as a
		// scale factor, so a layout change (a rotation, say) leaves the window pointing at a
		// different part of the history. Landscape showed samples from half an hour ago.
		applyAnnotations(chart)
		showNewestWindow(chart)
		chart.invalidate()
	}

	@VisibleForTesting
	internal companion object {
		/**
		 * Samples shown at once. Thousands are retained; a minute is what fits legibly in the strip.
		 */
		const val VISIBLE_SAMPLES = 60

		const val X_LABEL_GRANULARITY_SAMPLES = 15f

		const val ANNOTATION_LINE_WIDTH = 1f
		const val ANNOTATION_DASH_LENGTH = 6f

		/**
		 * Rows the annotation labels cycle through, counted up from the bottom of the plot.
		 *
		 * Eight rows at [ANNOTATION_LABEL_ROW_HEIGHT_DP] apiece stay inside the strip's plot area
		 * while spreading a burst of Gradle tasks far enough apart to read.
		 */
		const val ANNOTATION_LABEL_SLOTS = 8

		/**
		 * One row, in dp, at a font scale of 1. The label text is [BASE_TEXT_SIZE_DP], so this
		 * leaves a little air between rows; it is scaled with the text by [annotationRowHeightFor],
		 * or the rows would overlap exactly when the labels grew (ADFA-5527).
		 */
		const val ANNOTATION_LABEL_ROW_HEIGHT_DP = 12f

		/** MPAndroidChart's own default for axis and legend text, which this matches at scale 1. */
		const val BASE_TEXT_SIZE_DP = 10f

		/** MPAndroidChart's own default for value labels. */
		const val BASE_VALUE_TEXT_SIZE_DP = 9f

		/**
		 * The legend's dot, in dp, at a font scale of 1.
		 *
		 * MPAndroidChart's own default, and about the cap height of [BASE_TEXT_SIZE_DP] text, so the
		 * marker reads as part of its label rather than as a block beside it.
		 */
		const val BASE_LEGEND_FORM_DP = 8f

		/** The gap between a legend dot and its label, in dp, at a font scale of 1. */
		const val BASE_LEGEND_FORM_TO_TEXT_DP = 5f

		/** The gap between one legend entry and the next, in dp, at a font scale of 1. */
		const val BASE_LEGEND_ENTRY_SPACE_DP = 6f

		/**
		 * The most the chart will grow its text by, whatever the system font scale.
		 *
		 * 1.5 rather than the platform's maximum of 2.0: see [applyTextScale]. Eight annotation
		 * rows at 1.5 still fit the plot, where at 2.0 they do not.
		 */
		const val MAX_TEXT_SCALE = 1.5f

		/** Value-axis labels at a font scale of 1, which is MPAndroidChart's own default. */
		const val BASE_LABEL_COUNT = 6

		/** Never fewer than this, or the axis stops conveying a scale at all. */
		const val MIN_LABEL_COUNT = 3

		/** The font scale the charts follow: the system's, held to [MAX_TEXT_SCALE]. */
		@JvmStatic
		fun textScaleFor(context: Context): Float =
			context.resources.configuration.fontScale
				.coerceIn(1f, MAX_TEXT_SCALE)

		/** One annotation row, scaled with the label text it has to leave room for. */
		@JvmStatic
		fun annotationRowHeightFor(context: Context): Float = ANNOTATION_LABEL_ROW_HEIGHT_DP * textScaleFor(context)
	}
}
