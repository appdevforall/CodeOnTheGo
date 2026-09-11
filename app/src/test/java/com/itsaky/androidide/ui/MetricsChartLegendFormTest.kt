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
import com.github.mikephil.charting.components.Legend
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.MutableShiftedLongArray
import com.itsaky.androidide.utils.NetworkUsageWatcher
import com.itsaky.androidide.utils.PowerUsageWatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The legend marker is a small dot, and it stays one (ADFA-5553).
 *
 * The squares were 15dp and set per dataset, which crowded the label beside them and the axis
 * below. The size now comes from the legend, and that only works while no dataset overrides it:
 * `LegendRenderer` takes the dataset's `formSize` whenever it is not NaN and falls back to the
 * legend's only otherwise, so one renderer setting it again would silently take the setting back
 * without anything failing. That deference is what these tests pin -- asserting `legend.form` alone
 * would restate a setter and pass against the code this ticket exists to change.
 *
 * The size test runs at xhdpi on purpose. MPAndroidChart stores half of these properties in dp and
 * half in pixels, and at Robolectric's default density of 1.0 nothing tells the two apart.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsChartLegendFormTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private fun memoryChart(): SafeLineChart {
		val chart = SafeLineChart(context)
		MemoryUsageChartRenderer(
			usagesProvider = {
				arrayOf(
					MemoryUsageWatcher
						.ProcessMemoryInfo(1, "IDE", MutableShiftedLongArray(SAMPLES), watchedSinceMillis = 0L)
						// Fully sampled: the renderer plots only the slots that hold a real sample.
						.also { it.sampledCount = SAMPLES },
					MemoryUsageWatcher
						.ProcessMemoryInfo(
							2,
							"Gradle Tooling",
							MutableShiftedLongArray(SAMPLES),
							watchedSinceMillis = 0L,
						).also { it.sampledCount = SAMPLES },
				)
			},
			lineColorFor = { android.graphics.Color.BLUE },
		).attach(chart)
		return chart
	}

	private fun networkChart(): SafeLineChart {
		val chart = SafeLineChart(context)
		NetworkUsageChartRenderer(
			usageProvider = {
				NetworkUsageWatcher.NetworkUsage(
					LongArray(SAMPLES) { 1L },
					LongArray(SAMPLES) { 1L },
					LongArray(SAMPLES),
				)
			},
		).attach(chart)
		return chart
	}

	private fun powerChart(): SafeLineChart {
		val chart = SafeLineChart(context)
		PowerUsageChartRenderer(
			usageProvider = {
				PowerUsageWatcher.PowerUsage(
					LongArray(SAMPLES) { 30_000L },
					LongArray(SAMPLES) { 1_000L },
					LongArray(SAMPLES) { 0L },
					LongArray(SAMPLES),
				)
			},
			batteryProvider = { PowerUsageWatcher.BatteryState.UNKNOWN },
		).attach(chart)
		return chart
	}

	private fun charts() = listOf("memory" to memoryChart(), "network" to networkChart(), "power" to powerChart())

	@Test
	fun `every page's legend marker is a dot`() {
		charts().forEach { (name, chart) ->
			assertWithMessage(name).that(chart.legend.form).isEqualTo(Legend.LegendForm.CIRCLE)
		}
	}

	// Deliberately no size assertion at the default scale: MPAndroidChart's own Legend constructor
	// sets formSize to 8f, which is the value this renderer asks for, so such an assertion passes
	// with the production line deleted. The scale test below is what pins the size, because 12f is
	// a number only this code produces.

	@Test
	fun `no dataset overrides the legend, which is the only reason the legend's value applies`() {
		charts().forEach { (name, chart) ->
			chart.layOutAndDraw()

			val entries = chart.legend.entries
			assertThat(name to entries.isNotEmpty()).isEqualTo(name to true)
			entries.forEach { entry ->
				assertThat(name to entry.form).isEqualTo(name to Legend.LegendForm.DEFAULT)
				assertThat(name to entry.formSize.isNaN()).isEqualTo(name to true)
			}
		}
	}

	@Test
	@Config(fontScale = 2.0f, qualifiers = "xhdpi")
	fun `the dot, its gaps and its label all grow together, to the same ceiling`() {
		// A fixed marker beside text at the ceiling reads as though it were shrinking, and so do
		// the gaps around it. Spelled out rather than derived from BASE_LEGEND_FORM_DP *
		// MAX_TEXT_SCALE: computing the expectation from the same two constants the code
		// multiplies can only show that a multiplication happened. Each figure below is its dp
		// constant at the chart's 1.5 ceiling -- the chart's, not the platform's 2.0 -- so raising
		// either constant has to come and change this line.
		//
		// Half of these properties are stored in pixels and half in dp, which is MPAndroidChart's
		// doing and not ours: Legend keeps formSize, formToTextSpace and xEntrySpace as the dp it
		// was given and converts them when it draws, while ComponentBase.setTextSize and
		// setYOffset convert on the way in. At Robolectric's default density of 1.0 the two are
		// indistinguishable and a pixel getter compared against a dp constant passes anyway, so
		// this runs at xhdpi where they differ by 2x.
		assertThat(context.resources.displayMetrics.density).isWithin(TOLERANCE).of(2f)

		charts().forEach { (name, chart) ->
			val legend = chart.legend
			assertWithMessage("$name formSize").that(legend.formSize).isWithin(TOLERANCE).of(12f)
			assertWithMessage("$name formToTextSpace").that(legend.formToTextSpace).isWithin(TOLERANCE).of(7.5f)
			assertWithMessage("$name xEntrySpace").that(legend.xEntrySpace).isWithin(TOLERANCE).of(9f)

			// 15dp and 4.5dp, in pixels. Both are looks only. An earlier version of this comment
			// claimed the offset also moved the sampling-rate tap band, which it cannot: the
			// legend adds yOffset into mNeededHeight itself, and the band is measured from that.
			assertWithMessage("$name textSize").that(legend.textSize).isWithin(TOLERANCE).of(30f)
			assertWithMessage("$name yOffset").that(legend.yOffset).isWithin(TOLERANCE).of(9f)
		}
	}

	@Test
	fun `a font scale changed mid-session still reaches the chart through a redraw`() {
		// The per-tick path applies the scale only when it has moved, so this is the case that
		// guards the saving: EditorActivityKt handles fontScale itself, so no activity is
		// recreated and a redraw is the only thing a running chart does.
		val usage =
			NetworkUsageWatcher.NetworkUsage(
				LongArray(SAMPLES) { 1L },
				LongArray(SAMPLES) { 1L },
				LongArray(SAMPLES),
			)
		val chart = SafeLineChart(context)
		val renderer = NetworkUsageChartRenderer(usageProvider = { usage })
		renderer.attach(chart)
		assertThat(chart.legend.formSize).isWithin(TOLERANCE).of(8f)

		context.resources.configuration.fontScale = 2.0f
		// The same sample, so the series keep their shape and this takes the in-place redraw
		// rather than falling back to a rebuild, which would apply the scale by another route
		// and prove nothing.
		renderer.onUsageChanged(usage)

		assertThat(chart.legend.formSize).isWithin(TOLERANCE).of(12f)
	}

	private companion object {
		const val SAMPLES = 60

		/** The sizes are computed in floats and read back, so they land near-exactly. */
		const val TOLERANCE = 0.01f
	}
}
