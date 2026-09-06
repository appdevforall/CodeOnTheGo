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
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineDataSet
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.MetricsAnnotationStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins how annotation labels are placed (ADFA-5486, ADFA-5499).
 *
 * Nothing covered the drawing of annotations before, only the store behind them, which is how a
 * burst of Gradle tasks came to render its labels stacked on one row as an unreadable smear.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsAnnotationRenderingTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	/** A minimal renderer, so the placement is tested without a particular page's data. */
	private class TestRenderer(
		private val sampleCount: Int,
		annotations: MetricsAnnotationStore,
		now: () -> Long,
	) : MetricsChartRenderer(
			sampleIntervalMillis = { SAMPLE_INTERVAL_MS },
			annotations = annotations,
			nowMillis = now,
		) {
		override fun rebuild() {
			val chart = this.chart ?: return
			val entries = List(sampleCount) { Entry(it.toFloat(), 0f) }
			setData(chart, arrayOf(LineDataSet(entries, "test")))
		}
	}

	private class Fixture {
		var now = 0L
		val store = MetricsAnnotationStore(nowMillis = { now })

		/** Records [count] annotations, spaced far enough apart to clear the store's throttle. */
		fun recordBurst(count: Int) {
			repeat(count) { index ->
				store.record("task $index")
				now += MetricsAnnotationStore.THROTTLE_INTERVAL_MS
			}
		}
	}

	private fun render(fixture: Fixture): Pair<TestRenderer, SafeLineChart> {
		val chart = SafeLineChart(context)
		val renderer = TestRenderer(SAMPLE_COUNT, fixture.store, { fixture.now })
		renderer.attach(chart)
		return renderer to chart
	}

	private fun rowsOf(chart: SafeLineChart): List<Float> = chart.xAxis.limitLines.map { it.yOffset }

	@Test
	fun `a marker is drawn for each annotation in the window`() {
		val fixture = Fixture()
		fixture.recordBurst(4)

		val (_, chart) = render(fixture)

		assertThat(chart.xAxis.limitLines).hasSize(4)
	}

	@Test
	fun `labels are staggered across rows rather than stacked on one`() {
		val fixture = Fixture()
		fixture.recordBurst(4)

		val (_, chart) = render(fixture)

		// All on one row is exactly the smear this exists to prevent.
		assertThat(rowsOf(chart).toSet()).hasSize(4)
	}

	@Test
	fun `neighbouring labels never share a row`() {
		val fixture = Fixture()
		fixture.recordBurst(10)

		val (_, chart) = render(fixture)

		// Gradle fires tasks in bursts, so consecutive markers are the ones likeliest to collide.
		val rows = rowsOf(chart)
		assertThat(rows.zipWithNext().none { (earlier, later) -> earlier == later }).isTrue()
	}

	@Test
	fun `the rows cycle once more annotations than rows are drawn`() {
		val fixture = Fixture()
		fixture.recordBurst(10)

		val (_, chart) = render(fixture)

		// Ten annotations over eight rows: the ninth starts the cycle again.
		val rows = rowsOf(chart)
		assertThat(rows.toSet()).hasSize(8)
		assertThat(rows[8]).isEqualTo(rows[0])
		assertThat(rows[9]).isEqualTo(rows[1])
	}

	@Test
	fun `a label keeps its row as older annotations scroll out of the window`() {
		val fixture = Fixture()
		fixture.recordBurst(3)

		val (renderer, chart) = render(fixture)
		assertThat(chart.xAxis.limitLines).hasSize(3)
		val newestRowBefore = rowsOf(chart).last()

		// Age the chart until the first two annotations have fallen out of the buffer's span and
		// only the third is still inside it. Nothing new is recorded.
		fixture.now = SURVIVOR_ONLY_AT_MS
		renderer.rebuild()

		// Rows come from the order recorded, not from a position in the visible list: taking the
		// row from the latter would move this label from the third row to the first while it has
		// merely sat still.
		assertThat(chart.xAxis.limitLines).hasSize(1)
		assertThat(rowsOf(chart).single()).isEqualTo(newestRowBefore)
	}

	private companion object {
		const val SAMPLE_INTERVAL_MS = 1_000L
		const val SAMPLE_COUNT = 60

		/**
		 * A time by which the burst's first two annotations are older than the buffer's span and
		 * its third is not: they were recorded at 0ms, 5000ms and 10000ms, and the buffer holds
		 * SAMPLE_COUNT * SAMPLE_INTERVAL_MS = 60000ms.
		 */
		const val SURVIVOR_ONLY_AT_MS = 66_000L
	}
}
