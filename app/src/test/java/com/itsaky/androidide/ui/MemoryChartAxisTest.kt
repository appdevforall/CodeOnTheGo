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
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The labels the memory chart's value axis actually prints (ADFA-5535).
 *
 * Asserted on what MPAndroidChart computes rather than on the formatter alone, because the bug was
 * never in the formatter: it was in the interval the library chose to hand it. That interval is
 * decided during a layout and a draw, so the chart has to be laid out and drawn here.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryChartAxisTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private fun chartWith(megabytes: List<Float>): SafeLineChart {
		val chart = SafeLineChart(context)
		MemoryChartAxis.configure(chart.axisRight)
		chart.axisLeft.isEnabled = false
		chart.data =
			LineData(LineDataSet(megabytes.mapIndexed { i, mb -> Entry(i.toFloat(), mb) }, "IDE"))
		chart.notifyDataSetChanged()

		chart.measure(
			View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
		)
		chart.layout(0, 0, WIDTH, HEIGHT)
		chart.draw(Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)))
		return chart
	}

	private fun labelsOf(chart: SafeLineChart): List<String> {
		val axis = chart.axisRight
		return (0 until axis.mEntryCount).map { i -> axis.getFormattedLabel(i) }
	}

	@Test
	fun `an all-zero chart prints no duplicate labels`() {
		// Every editor open looks like this until the first sample lands, and so does the moment
		// after the sampling rate changes, which clears the buffers.
		val labels = labelsOf(chartWith(List(60) { 0f }))

		assertThat(labels).isNotEmpty()
		assertThat(labels).containsNoDuplicates()
	}

	@Test
	fun `an all-zero chart prints no negative megabytes`() {
		// The reported symptom was "-1MB, -1MB, 0MB, 0MB, 1MB, 1MB". Memory is never negative, so
		// half of that was nonsense before it was even repeated.
		val labels = labelsOf(chartWith(List(60) { 0f }))

		labels.forEach { label -> assertThat(label).doesNotContain("-") }
	}

	@Test
	fun `a chart with real readings still labels distinctly`() {
		// Granularity is a floor on the interval, not the interval, so a chart spanning hundreds of
		// megabytes is unaffected by it. If this ever fails, the floor has started to bite.
		val labels = labelsOf(chartWith(listOf(120f, 480f, 733f, 906f, 512f)))

		assertThat(labels).containsNoDuplicates()
		assertThat(labels.size).isAtLeast(3)
	}

	@Test
	fun `the labels are whole megabytes`() {
		val labels = labelsOf(chartWith(listOf(120f, 480f, 733f)))

		labels.forEach { label -> assertThat(label).matches("\\d+MB") }
	}

	private companion object {
		const val WIDTH = 720
		const val HEIGHT = 400
	}
}
