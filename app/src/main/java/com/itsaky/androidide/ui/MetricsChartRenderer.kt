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
import androidx.annotation.CallSuper
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import com.github.mikephil.charting.components.AxisBase
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
	 */
	private fun isOnAxisBand(y: Float): Boolean {
		val chart = this.chart ?: return false
		return y >= chart.viewPortHandler.contentBottom()
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
		userHasZoomed = false
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

		chart.setVisibleXRangeMaximum(VISIBLE_SAMPLES.toFloat())
		chart.moveViewToX(newestIndex - VISIBLE_SAMPLES.toFloat() + 1f)
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
		) = Unit
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
		// The visible window, not the whole buffer. Spanning the buffer meant asking for every
		// annotation the store holds -- up to MAX_ANNOTATIONS -- and building a LimitLine and a
		// DashPathEffect for each one on every redraw, almost all of them clipped off screen.
		val bufferSpanMillis = (VISIBLE_SAMPLES.toLong() + 1L) * interval
		val now = nowMillis()
		// Resolved once per redraw rather than once per annotation: applyAnnotations runs on every
		// sampling tick, there can be MAX_ANNOTATIONS of them, and resolveAttr allocates a
		// TypedValue per call.
		val markerColors = MetricsAnnotationStore.Kind.entries.associateWith { markerColorFor(chart, it) }

		store.recentAnnotations(bufferSpanMillis).forEach { annotation ->
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
					yOffset = ANNOTATION_LABEL_ROW_HEIGHT_DP * slotFor(annotation.sequence)
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

	private companion object {
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

		/** One row, in dp. The label text is 10dp, so this leaves a little air between rows. */
		const val ANNOTATION_LABEL_ROW_HEIGHT_DP = 12f
	}
}
