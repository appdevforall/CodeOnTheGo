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
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Where [SafeLineChart] paints its background spans relative to the grid background.
 *
 * The spans first went in before the call up to `super.onDraw`, which was the one place they could
 * not survive: the grid background is an opaque fill of the whole plot, so every span was painted
 * and then covered. Nothing in the span geometry tests noticed -- they read
 * [SafeLineChart.backgroundSpans], which was correct all along -- so this asserts against pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SafeLineChartTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private fun drawn(configure: SafeLineChart.() -> Unit): Bitmap {
		val chart = SafeLineChart(context)
		chart.setDrawGridBackground(true)
		chart.setGridBackgroundColor(GRID_BACKGROUND)
		chart.description.isEnabled = false
		chart.legend.isEnabled = false
		chart.axisLeft.axisMinimum = 0f
		chart.axisLeft.axisMaximum = 10f
		// Flat at the axis minimum, so the line itself stays clear of the sampled pixel.
		chart.data = LineData(LineDataSet(List(SAMPLES) { Entry(it.toFloat(), 0f) }, "flat"))
		chart.configure()
		chart.measure(
			View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
		)
		chart.layout(0, 0, WIDTH, HEIGHT)
		val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
		chart.draw(Canvas(bitmap))
		return bitmap
	}

	/** A pixel inside the plot, near its top, away from the flat data line. */
	private fun Bitmap.plotPixel(): Int = getPixel(WIDTH / 2, HEIGHT / 4)

	@Test
	fun `a span reaches the screen instead of being covered by the grid background`() {
		val shaded =
			drawn {
				backgroundSpans =
					listOf(SafeLineChart.Span(startX = 0f, endX = SAMPLES.toFloat(), color = SPAN))
			}

		// Painted before the grid background this pixel came back GRID_BACKGROUND, every time.
		assertThat(shaded.plotPixel()).isEqualTo(SPAN)
	}

	@Test
	fun `the grid background still shows through where nothing is shaded`() {
		// The other half of the order: the span must not be a wash over the whole plot either.
		assertThat(drawn { }.plotPixel()).isEqualTo(GRID_BACKGROUND)
	}

	private companion object {
		const val WIDTH = 720
		const val HEIGHT = 400
		const val SAMPLES = 20

		val GRID_BACKGROUND = Color.WHITE
		val SPAN = Color.RED
	}
}
