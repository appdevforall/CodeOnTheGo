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
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.LayoutMemUsageBinding
import com.itsaky.androidide.utils.MetricsAnnotationStore
import com.itsaky.androidide.utils.NetworkUsageWatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chart text-scale policy of ADFA-5527: follow the system font scale, up to a ceiling.
 *
 * MPAndroidChart sizes its text in dp, so before this the charts ignored the font scale entirely
 * -- a user who asked for larger text got it everywhere in the IDE except inside these plots. The
 * scale is followed only to [MetricsChartRenderer.MAX_TEXT_SCALE], because the strip is a fixed
 * height and at the platform's full 2.0 the axis labels collide and the eight staggered annotation
 * rows overlap.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsChartTextScaleTest {
	private val context: Context get() = ApplicationProvider.getApplicationContext()

	private fun chart(): SafeLineChart {
		val chart = SafeLineChart(context)
		NetworkUsageChartRenderer(
			usageProvider = {
				NetworkUsageWatcher.NetworkUsage(
					LongArray(SAMPLES) { 1_000L },
					LongArray(SAMPLES) { 500L },
					LongArray(SAMPLES),
				)
			},
		).attach(chart)
		return chart
	}

	private val base get() = MetricsChartRenderer.BASE_TEXT_SIZE_DP

	@Test
	fun `at the default scale the text is the size it always was`() {
		val chart = chart()

		// Matching MPAndroidChart's own default, so nothing moves for a user who has not changed
		// the setting.
		assertThat(chart.xAxis.textSize).isWithin(TOLERANCE).of(base)
		assertThat(chart.legend.textSize).isWithin(TOLERANCE).of(base)
		assertThat(chart.axisRight.textSize).isWithin(TOLERANCE).of(base)
	}

	@Test
	@Config(fontScale = 1.3f)
	fun `a modest font scale is followed exactly`() {
		val chart = chart()

		assertThat(chart.xAxis.textSize).isWithin(TOLERANCE).of(base * 1.3f)
		assertThat(chart.legend.textSize).isWithin(TOLERANCE).of(base * 1.3f)
	}

	@Test
	@Config(fontScale = 2.0f)
	fun `the largest font scale is held to the ceiling`() {
		val chart = chart()

		// Not base * 2: eight annotation rows at that size do not fit the plot, and the axis
		// labels collide with each other.
		assertThat(chart.xAxis.textSize)
			.isWithin(TOLERANCE)
			.of(base * MetricsChartRenderer.MAX_TEXT_SCALE)
	}

	@Test
	@Config(fontScale = 0.85f)
	fun `a font scale below one does not shrink the chart further`() {
		val chart = chart()

		// The chart's text is already the smallest on the screen; following a reduction would
		// make the labels unreadable rather than merely small.
		assertThat(chart.xAxis.textSize).isWithin(TOLERANCE).of(base)
	}

	@Test
	@Config(fontScale = 2.0f)
	fun `the annotation rows a chart actually draws grow with the labels`() {
		// Asserted on the drawn marker, not on the helper: an earlier version of this test called
		// annotationRowHeightFor directly, so it passed even with the renderer still using the
		// unscaled constant at the call site.
		var now = 1_000_000L
		val store = MetricsAnnotationStore(nowMillis = { now })
		store.record("first")
		now += MetricsAnnotationStore.THROTTLE_INTERVAL_MS
		store.record("second")

		val chart = SafeLineChart(context)
		NetworkUsageChartRenderer(
			usageProvider = {
				NetworkUsageWatcher.NetworkUsage(
					LongArray(SAMPLES) { 1_000L },
					LongArray(SAMPLES) { 500L },
					LongArray(SAMPLES),
				)
			},
			annotations = store,
			sampleInterval = { 1_000L },
		).attach(chart)

		// Rows sized for scale-1 text would overlap exactly when the text grew, which is what the
		// staggering exists to prevent. Two consecutive markers sit one row apart.
		val offsets =
			chart.xAxis.limitLines
				.map { it.yOffset }
				.sorted()
		assertThat(offsets).hasSize(2)
		val expected =
			MetricsChartRenderer.ANNOTATION_LABEL_ROW_HEIGHT_DP * MetricsChartRenderer.MAX_TEXT_SCALE
		assertThat(offsets[1] - offsets[0]).isWithin(TOLERANCE).of(expected)
	}

	@Test
	@Config(fontScale = 2.0f)
	fun `eight annotation rows still fit the plot at the ceiling`() {
		// The reason the ceiling is 1.5. The strip is a fixed height, and this is the constraint
		// that sets the limit -- if it ever fails, the ceiling is too high or the strip too short.
		//
		// Measured, not guessed. This used to compare against a hand-picked 150dp with a comment
		// admitting it was conservative, which pinned the ceiling against a number no layout change
		// could ever move. The strip is laid out at the ceiling font scale and the pager reports
		// what the title row -- itself grown by that scale -- left it.
		val rows = MetricsChartRenderer.ANNOTATION_LABEL_SLOTS
		val used = rows * MetricsChartRenderer.annotationRowHeightFor(context)

		assertThat(used).isLessThan(plotHeightDp())
	}

	/**
	 * The plot area a chart page actually gets, in dp, with the system font scale at its largest.
	 *
	 * Measured the whole way down, with nothing allowed for by hand: the strip's height is the
	 * dimen the layout uses, the pager's share of it comes from a real measure and layout of the
	 * real strip, and the plot's share of *that* is the content rect a real chart page reports
	 * after a real renderer has put its legend and axis on it. So shortening the strip fails this,
	 * and so does anything above or inside the plot growing with the font scale.
	 */
	private fun plotHeightDp(): Float {
		val themed = ContextThemeWrapper(context, R.style.Theme_AndroidIDE)
		val strip = LayoutMemUsageBinding.inflate(LayoutInflater.from(themed))
		val metrics = context.resources.displayMetrics
		val stripHeightPx = context.resources.getDimensionPixelSize(R.dimen.editor_mem_usage_view_height)
		val widthPx = (STRIP_WIDTH_DP * metrics.density).toInt()

		strip.root.measure(
			View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(stripHeightPx, View.MeasureSpec.EXACTLY),
		)
		strip.root.layout(0, 0, widthPx, stripHeightPx)

		val page =
			LayoutInflater
				.from(themed)
				.inflate(R.layout.item_metrics_chart, strip.metricsPager, false) as SafeLineChart
		NetworkUsageChartRenderer(
			usageProvider = {
				NetworkUsageWatcher.NetworkUsage(
					LongArray(SAMPLES) { 1_000L },
					LongArray(SAMPLES) { 500L },
					LongArray(SAMPLES),
				)
			},
		).attach(page)
		page.layOutAndDraw(width = strip.metricsPager.width, height = strip.metricsPager.height)

		return page.viewPortHandler.contentHeight() / metrics.density
	}

	private companion object {
		const val SAMPLES = 60
		const val TOLERANCE = 0.01f

		/** A narrow phone, so the title row wraps here if it is ever going to. */
		const val STRIP_WIDTH_DP = 360f
	}
}
