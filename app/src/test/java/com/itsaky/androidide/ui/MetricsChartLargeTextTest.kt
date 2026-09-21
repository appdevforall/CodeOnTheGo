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
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.LayoutMemUsageBinding
import com.itsaky.androidide.utils.NetworkUsageWatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chart at the size the carousel actually gives it, with the text a low-vision user runs
 * (ADFA-5602).
 *
 * Every other chart test lays out at [CHART_HEIGHT], 400px, which is far taller than the strip:
 * `editor_mem_usage_view_height` is 248dp and the plot is only the part of it left over after the
 * title row, the legend and the arrows. At 400px there is room for the axis text to grow and
 * nothing collapses, which is why the whole suite passed while the chart on the device drew
 * nothing at all at 2x.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsChartLargeTextTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private val themed: Context =
		ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_AndroidIDE)

	private fun laidOutChart(height: Int): SafeLineChart {
		val chart = SafeLineChart(context)
		NetworkUsageChartRenderer(
			usageProvider = {
				NetworkUsageWatcher.NetworkUsage(
					LongArray(SAMPLES) { 1_000L + it },
					LongArray(SAMPLES) { 500L + it },
					LongArray(SAMPLES),
				)
			},
		).attach(chart)
		chart.layOutAndDraw(CHART_WIDTH, height)
		return chart
	}

	/** The x labels the axis would draw, as the reader sees them. */
	private fun xLabels(chart: SafeLineChart): List<String> {
		val axis = chart.xAxis
		val formatter = axis.valueFormatter ?: return emptyList()
		return axis.mEntries.map { formatter.getFormattedValue(it, axis).orEmpty() }
	}

	@Test
	fun `the plot keeps a usable area in the strip the carousel gives it`() {
		val chart = laidOutChart(STRIP_PLOT_HEIGHT)

		val handler = chart.viewPortHandler
		assertWithMessage("content width").that(handler.contentWidth()).isGreaterThan(0f)
		assertWithMessage("content height").that(handler.contentHeight()).isGreaterThan(0f)
	}

	@Test
	@Config(fontScale = 2.0f)
	fun `the plot keeps a usable area at 2x font scale`() {
		// The strip's height is fixed, so everything the axes and the legend reserve comes out of
		// the plot. At 2x that reservation grew past what was there.
		val chart = laidOutChart(STRIP_PLOT_HEIGHT)

		val handler = chart.viewPortHandler
		assertWithMessage("content width").that(handler.contentWidth()).isGreaterThan(0f)
		assertWithMessage("content height").that(handler.contentHeight()).isGreaterThan(0f)
	}

	@Test
	@Config(fontScale = 2.0f)
	fun `the time axis still says how long ago, not 'now' for every label`() {
		// The reported symptom. ElapsedTimeFormatter answers "now" whenever a label's value equals
		// the axis maximum, so an axis whose range has collapsed labels every tick "now" -- and the
		// same collapse is why nothing is drawn.
		val chart = laidOutChart(STRIP_PLOT_HEIGHT)

		val labels = xLabels(chart)
		assertThat(labels).isNotEmpty()
		assertWithMessage("labels were $labels").that(labels.any { it != "now" }).isTrue()
	}

	@Test
	fun `a series with no readings at all does not collapse the time axis`() {
		// What the device showed when this was reported: the legend read "Power - n/a", the plot was
		// empty, and every x label read "now". A power source that stops answering gives the chart a
		// series of pure sentinels, which is not the same as no chart at all -- the axis still has to
		// say how long ago each sample was.
		val chart = SafeLineChart(context)
		NetworkUsageChartRenderer(
			usageProvider = {
				NetworkUsageWatcher.NetworkUsage(LongArray(0), LongArray(0), LongArray(0))
			},
		).attach(chart)
		chart.layOutAndDraw(CHART_WIDTH, STRIP_PLOT_HEIGHT)

		val labels = xLabels(chart)
		assertWithMessage("labels were $labels, xRange=${chart.xAxis.mAxisMinimum}..${chart.xAxis.mAxisMaximum}")
			.that(labels.all { it == "now" } && labels.isNotEmpty())
			.isFalse()
	}

	private companion object {
		const val SAMPLES = 200

		/**
		 * What the plot gets inside the 248dp strip once the title row, legend and arrows have
		 * taken theirs. Robolectric's density is 1.0, so dp and px are the same here.
		 */
		const val STRIP_PLOT_HEIGHT = 150
	}

	@Test
	@Config(fontScale = 2.0f, qualifiers = "xhdpi")
	fun `the undocked message fits the strip at 2x text`() {
		// The strip is a fixed editor_mem_usage_view_height and the message fills it with no room to
		// scroll, so the only thing keeping it readable at 2x is that it still fits. Measured at the
		// real height rather than the 400px the other tests use.
		val binding = LayoutMemUsageBinding.inflate(LayoutInflater.from(themed))
		val strip = binding.root as MetricsCarouselLayout
		strip.setUndocked(true)

		val height = themed.resources.getDimensionPixelSize(R.dimen.editor_mem_usage_view_height)
		strip.measure(
			View.MeasureSpec.makeMeasureSpec(CHART_WIDTH, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
		)
		strip.layout(0, 0, CHART_WIDTH, height)

		val message = strip.findViewById<TextView>(R.id.metrics_undocked_message)
		val needed = message.layout.height + message.paddingTop + message.paddingBottom

		assertWithMessage("undocked message needs %spx of the %spx it has", needed, message.height)
			.that(needed)
			.isAtMost(message.height)
	}
}
