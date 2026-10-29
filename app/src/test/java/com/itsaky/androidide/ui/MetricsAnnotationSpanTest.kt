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
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.MetricsAnnotationStore
import com.itsaky.androidide.utils.NetworkUsageWatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How far back the renderer asks the annotation store to look (ADFA-5486).
 *
 * It asked for a fixed sixty-one samples' worth of time from now, which is right only while the
 * viewport is following the newest samples. Once panning began to stick, a viewport showing older
 * samples had its markers dropped before their x was worked out -- invisible in the one view that
 * was looking at them.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsAnnotationSpanTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private var now = 1_000_000L

	private val store = MetricsAnnotationStore(nowMillis = { now })

	private fun chartWithAnnotations(): Pair<NetworkUsageChartRenderer, SafeLineChart> {
		val chart = SafeLineChart(context)
		val renderer =
			NetworkUsageChartRenderer(
				usageProvider = {
					NetworkUsageWatcher.NetworkUsage(
						LongArray(SAMPLES) { 1_000L },
						LongArray(SAMPLES) { 500L },
						LongArray(SAMPLES),
					)
				},
				annotations = store,
				sampleInterval = { INTERVAL_MS },
			)
		renderer.attach(chart)
		chart.measure(
			View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
		)
		chart.layout(0, 0, WIDTH, HEIGHT)
		draw(chart)
		return renderer to chart
	}

	private fun draw(chart: SafeLineChart) {
		chart.draw(Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)))
	}

	@Test
	fun `a marker outside the newest window is drawn once the viewport is panned to it`() {
		// One annotation, then enough elapsed time to push it far outside the newest 61 samples.
		store.record("an old task")
		now += INTERVAL_MS * 200L

		val (renderer, chart) = chartWithAnnotations()
		val whileFollowing = chart.xAxis.limitLines.size

		// Pan back to where that marker lives, and record that the user drove the viewport.
		chart.setVisibleXRangeMaximum(VISIBLE_WINDOW.toFloat())
		chart.moveViewToXNow(0f)
		draw(chart)
		val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 10f, 10f, 0)
		chart.onChartGestureListener.onChartTranslate(event, -50f, 0f)
		event.recycle()

		renderer.rebuild()

		assertThat(whileFollowing).isEqualTo(0)
		assertThat(chart.xAxis.limitLines.size).isEqualTo(1)
	}

	@Test
	fun `following the newest samples still asks for only the visible window`() {
		// The other half: the span must not quietly become the whole buffer, which would build a
		// LimitLine and a DashPathEffect per stored annotation on every redraw.
		store.record("a recent task")

		val (_, chart) = chartWithAnnotations()

		assertThat(chart.xAxis.limitLines.size).isEqualTo(1)
	}

	private companion object {
		const val WIDTH = 720
		const val HEIGHT = 400
		const val SAMPLES = 400
		const val VISIBLE_WINDOW = 60
		const val INTERVAL_MS = 1_000L
	}
}
