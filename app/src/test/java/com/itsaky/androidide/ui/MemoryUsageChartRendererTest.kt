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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.collection.MutableIntObjectMap
import androidx.test.core.app.ApplicationProvider
import com.github.mikephil.charting.components.YAxis
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

	/**
	 * A chart showing one process with the given byte history, laid out and drawn once.
	 *
	 * The draw matters: MPAndroidChart queues the scroll to the newest samples as a job that only
	 * runs during a draw pass, so without one the chart reports the oldest samples as visible.
	 */
	private fun laidOutChart(history: LongArray): SafeLineChart {
		val chart = chart()
		val process =
			ProcessMemoryInfo(
				PID_IDE,
				"IDE",
				MutableShiftedLongArray(LongArray(history.size) { history[it] }),
			)
		renderer { arrayOf(process) }.attach(chart)
		chart.measure(
			View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
		)
		chart.layout(0, 0, WIDTH, HEIGHT)
		chart.draw(Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)))
		return chart
	}

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
	fun `every memory line is scaled by the axis that labels it`() {
		val chart = laidOutChart(LongArray(SAMPLE_COUNT) { 100L * BYTES_PER_MB })

		// configure() disables axisLeft and this renderer ranges and formats only axisRight, but
		// MPAndroidChart defaults a dataset to LEFT -- so the lines were scaled by an axis nobody
		// had configured while the labels beside them came from another.
		val datasets = (0 until chart.data.dataSetCount).map { chart.data.getDataSetByIndex(it) }
		assertThat(datasets).isNotEmpty()
		for (dataset in datasets) {
			assertThat(dataset.axisDependency).isEqualTo(YAxis.AxisDependency.RIGHT)
		}
	}

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
		var processes = arrayOf(proc(pid = 1, pname = "IDE", firstMegabytes = 100))
		val renderer = renderer { processes }
		val detached = chart()
		renderer.attach(detached)
		val before = datasetFor(detached, 0).entries.map { it.y }

		renderer.detach()

		// Different samples, so a renderer that kept writing would visibly change the chart.
		processes = arrayOf(proc(pid = 1, pname = "IDE", firstMegabytes = 900))
		renderer.onUsagesChanged(
			MutableIntObjectMap<ProcessMemoryInfo>().apply { put(1, processes[0]) },
		)

		// A recycled carousel page must not keep the renderer writing into a dead view. Asserting
		// only that the call does not throw pinned nothing: it would not have thrown anyway.
		assertThat(datasetFor(detached, 0).entries.map { it.y }).isEqualTo(before)
	}

	@Test
	fun `a swapped process rebuilds rather than plotting its samples on another line`() {
		// The count stays the same and a pid changes -- what a tooling-server pid correction does.
		// The suite covered only the count-changed path, and correctness here rested on
		// getDataSetByIndex(-1) happening to return null.
		val first = proc(pid = 1, pname = "IDE", firstMegabytes = 100)
		var processes = arrayOf(first)
		val renderer = renderer { processes }
		val chart = chart()
		renderer.attach(chart)

		val replacement = proc(pid = 2, pname = "Gradle Tooling", firstMegabytes = 700)
		processes = arrayOf(replacement)
		renderer.onUsagesChanged(
			MutableIntObjectMap<ProcessMemoryInfo>().apply { put(2, replacement) },
		)

		assertThat(chart.data.dataSetCount).isEqualTo(1)
		assertThat(datasetFor(chart, 0).label).startsWith("Gradle Tooling - ")
		assertThat(datasetFor(chart, 0).entries.first().y).isEqualTo(700f)
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

	@Test
	fun `the axis is scaled to what is on screen, not to the whole buffer`() {
		// An early 1.5 GB daemon peak, then a long quiet stretch around 200 MB.
		val history = LongArray(SAMPLE_COUNT) { 200L * BYTES_PER_MB }
		history[0] = 1_500L * BYTES_PER_MB
		val chart = laidOutChart(history)

		// Ranged over the whole buffer the axis reaches 1650 MB and presses every later reading
		// into the bottom eighth of the plot for the hours the buffer takes to turn over.
		assertThat(chart.axisRight.axisMaximum).isLessThan(400f)
	}

	@Test
	fun `a peak still on screen does raise the axis`() {
		// Guards the test above: it must not pass by ignoring peaks altogether.
		val history = LongArray(SAMPLE_COUNT) { 200L * BYTES_PER_MB }
		history[SAMPLE_COUNT - 1] = 1_500L * BYTES_PER_MB
		val chart = laidOutChart(history)

		assertThat(chart.axisRight.axisMaximum).isAtLeast(1_500f)
	}

	@Test
	fun `an idle chart still has a readable scale`() {
		val chart = laidOutChart(LongArray(SAMPLE_COUNT))

		// Zero everywhere would otherwise collapse the axis to no height at all.
		assertThat(chart.axisRight.axisMaximum).isGreaterThan(0f)
		assertThat(chart.axisRight.axisMinimum).isEqualTo(0f)
	}

	private companion object {
		/**
		 * The production constant, not a copy of it. With its own literal the test verified its
		 * own arithmetic: change the renderer to decimal megabytes and every assertion still
		 * passed because both sides had stopped agreeing.
		 */
		val BYTES_PER_MB = BYTES_PER_MEGABYTE.toLong()
		const val WIDTH = 720
		const val HEIGHT = 400
		const val PID_IDE = 1

		/** Longer than the visible window, so the start of the history scrolls off screen. */
		const val SAMPLE_COUNT = 200
	}
}
