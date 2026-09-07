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
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
				NetworkUsageWatcher.NetworkUsage(LongArray(SAMPLES) { 1_000L }, LongArray(SAMPLES) { 500L })
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
				NetworkUsageWatcher.NetworkUsage(LongArray(SAMPLES) { 1_000L }, LongArray(SAMPLES) { 500L })
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
		val rows = MetricsChartRenderer.ANNOTATION_LABEL_SLOTS
		val used = rows * MetricsChartRenderer.annotationRowHeightFor(context)

		assertThat(used).isLessThan(PLOT_HEIGHT_DP)
	}

	private companion object {
		const val SAMPLES = 60
		const val TOLERANCE = 0.01f

		/**
		 * The plot's share of editor_mem_usage_view_height (248dp), less the title row, the
		 * legend and the x axis. Deliberately conservative.
		 */
		const val PLOT_HEIGHT_DP = 150f
	}
}
