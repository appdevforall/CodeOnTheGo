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
 */
@RunWith(RobolectricTestRunner::class)
class MetricsChartLegendFormTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private fun memoryChart(): SafeLineChart {
		val chart = SafeLineChart(context)
		MemoryUsageChartRenderer(
			usagesProvider = {
				arrayOf(
					MemoryUsageWatcher.ProcessMemoryInfo(1, "IDE", MutableShiftedLongArray(SAMPLES)),
					MemoryUsageWatcher.ProcessMemoryInfo(2, "Gradle Tooling", MutableShiftedLongArray(SAMPLES)),
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
				NetworkUsageWatcher.NetworkUsage(LongArray(SAMPLES) { 1L }, LongArray(SAMPLES) { 1L })
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
	@Config(fontScale = 2.0f)
	fun `the dot grows with its label, to the same ceiling`() {
		// A fixed marker beside text at the ceiling reads as though it were shrinking. Spelled out
		// rather than derived from BASE_LEGEND_FORM_DP * MAX_TEXT_SCALE: computing the expectation
		// from the same two constants the code multiplies can only show that a multiplication
		// happened. 12f is 8dp at the chart's 1.5 ceiling -- which is the chart's, not the
		// platform's 2.0 -- so raising either constant has to come and change this line.
		charts().forEach { (name, chart) ->
			assertWithMessage(name).that(chart.legend.formSize).isWithin(TOLERANCE).of(12f)
			assertWithMessage(name).that(chart.legend.textSize).isWithin(TOLERANCE).of(15f)
		}
	}

	private companion object {
		const val SAMPLES = 60

		/** The sizes are computed in floats and read back, so they land near-exactly. */
		const val TOLERANCE = 0.01f
	}
}
