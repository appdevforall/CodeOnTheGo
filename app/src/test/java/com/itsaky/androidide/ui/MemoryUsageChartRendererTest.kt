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

import android.graphics.Color
import androidx.collection.MutableIntObjectMap
import androidx.test.core.app.ApplicationProvider
import com.github.mikephil.charting.data.LineDataSet
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.MemoryUsageWatcher.ProcessMemoryInfo
import com.itsaky.androidide.utils.MutableShiftedLongArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the properties ADFA-5487's metrics carousel relies on: the renderer holds no sample state, so
 * a chart attached at any time shows the complete history, and a change to the watched process set
 * is picked up rather than dropped.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryUsageChartRendererTest {
	private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

	private fun chart() = SafeLineChart(context)

	private fun renderer(processes: () -> Array<ProcessMemoryInfo>) =
		MemoryUsageChartRenderer(
			usagesProvider = processes,
			lineColorFor = { Color.BLUE },
		)

	/** A process whose history ramps from [firstMegabytes] by 1MB per sample. */
	private fun proc(
		pid: Int,
		pname: String,
		firstMegabytes: Long,
	) = ProcessMemoryInfo(
		pid,
		pname,
		MutableShiftedLongArray(MemoryUsageWatcher.MAX_USAGE_ENTRIES) { (firstMegabytes + it) * BYTES_PER_MB },
	)

	private fun datasetFor(
		chart: SafeLineChart,
		index: Int,
	) = chart.data.getDataSetByIndex(index) as LineDataSet

	@Test
	fun `attach renders the complete existing history, not a flat line`() {
		val processes = arrayOf(proc(pid = 1, pname = "IDE", firstMegabytes = 100))
		val chart = chart()

		renderer { processes }.attach(chart)

		val dataset = datasetFor(chart, 0)
		assertThat(dataset.entryCount).isEqualTo(MemoryUsageWatcher.MAX_USAGE_ENTRIES)
		// The old resetMemUsageChart() seeded every entry with 0f and waited a tick for real values;
		// a carousel page attached mid-session would have shown that flat line.
		assertThat(dataset.entries.map { it.y }).doesNotContain(0f)
		assertThat(dataset.entries.first().y).isEqualTo(100f)
		assertThat(dataset.entries.last().y).isEqualTo((100 + MemoryUsageWatcher.MAX_USAGE_ENTRIES - 1).toFloat())
		assertThat(dataset.label).isEqualTo("IDE - %.2fMB".format(dataset.entries.last().y))
	}

	@Test
	fun `onUsagesChanged updates entries in place without replacing the datasets`() {
		val processes = arrayOf(proc(pid = 1, pname = "IDE", firstMegabytes = 100))
		val chart = chart()
		val renderer = renderer { processes }
		renderer.attach(chart)

		val datasetBefore = datasetFor(chart, 0)
		val entryBefore = datasetBefore.entries.first()

		val updated = proc(pid = 1, pname = "IDE", firstMegabytes = 200)
		renderer.onUsagesChanged(MutableIntObjectMap<ProcessMemoryInfo>().apply { put(1, updated) })

		// Same dataset and same Entry objects, new values: this path runs once a second for the
		// lifetime of the editor, so it must not allocate.
		assertThat(datasetFor(chart, 0)).isSameInstanceAs(datasetBefore)
		assertThat(datasetBefore.entries.first()).isSameInstanceAs(entryBefore)
		assertThat(entryBefore.y).isEqualTo(200f)
	}

	@Test
	fun `onUsagesChanged rebuilds when a process starts being watched`() {
		var processes = arrayOf(proc(pid = 1, pname = "IDE", firstMegabytes = 100))
		val chart = chart()
		val renderer = renderer { processes }
		renderer.attach(chart)

		assertThat(chart.data.dataSetCount).isEqualTo(1)

		// Gradle Tooling starts up. The old code looked the new pid up in a map that only reset()
		// populated, logged "No dataset found for process", and dropped its samples.
		val gradle = proc(pid = 2, pname = "Gradle Tooling", firstMegabytes = 300)
		processes = arrayOf(processes[0], gradle)
		renderer.onUsagesChanged(
			MutableIntObjectMap<ProcessMemoryInfo>().apply {
				put(1, processes[0])
				put(2, gradle)
			},
		)

		assertThat(chart.data.dataSetCount).isEqualTo(2)
		assertThat(datasetFor(chart, 1).label).startsWith("Gradle Tooling - ")
		assertThat(datasetFor(chart, 1).entries.first().y).isEqualTo(300f)
	}

	@Test
	fun `onUsagesChanged after detach is a no-op`() {
		val processes = arrayOf(proc(pid = 1, pname = "IDE", firstMegabytes = 100))
		val renderer = renderer { processes }
		renderer.attach(chart())
		renderer.detach()

		// A recycled carousel page must not keep the renderer writing into a dead view.
		renderer.onUsagesChanged(
			MutableIntObjectMap<ProcessMemoryInfo>().apply { put(1, processes[0]) },
		)
	}

	@Test
	fun `attach after detach renders the history into the new chart`() {
		val processes = arrayOf(proc(pid = 1, pname = "IDE", firstMegabytes = 100))
		val renderer = renderer { processes }
		renderer.attach(chart())
		renderer.detach()

		val rebound = chart()
		renderer.attach(rebound)

		assertThat(datasetFor(rebound, 0).entryCount).isEqualTo(MemoryUsageWatcher.MAX_USAGE_ENTRIES)
		assertThat(datasetFor(rebound, 0).entries.first().y).isEqualTo(100f)
	}

	private companion object {
		const val BYTES_PER_MB = 1024L * 1024L
	}
}
