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
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineDataSet
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.utils.MetricsAnnotationStore
import com.itsaky.androidide.utils.resolveAttr
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
	// Themed: the marker colours come from theme attributes, and against a bare application
	// context every one of them resolves to 0, so a colour test would pass by comparing nothing.
	private val context: Context =
		ContextThemeWrapper(
			ApplicationProvider.getApplicationContext(),
			com.itsaky.androidide.R.style.Theme_AndroidIDE,
		)

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
		override val helpTag: String = TooltipTag.CAROUSEL_CHART_MEMORY

		override fun rebuild() {
			val chart = this.chart ?: return
			val entries = List(sampleCount) { Entry(it.toFloat(), 0f) }
			setData(chart, arrayOf(LineDataSet(entries, "test")))
		}
	}

	private class Fixture {
		var now = 0L
		val store = MetricsAnnotationStore(nowMillis = { now })

		/** Records [count] task markers, spaced far enough apart to clear the store's throttle. */
		fun recordBurst(count: Int) {
			repeat(count) { index -> record("task $index") }
		}

		/**
		 * Records one annotation and advances past the throttle window.
		 *
		 * Every caller wanted both halves and had to remember the second one; forgetting it made
		 * the store drop the next annotation, and the test then asserted against a chart with one
		 * fewer marker than it had asked for.
		 */
		fun record(
			label: String,
			kind: MetricsAnnotationStore.Kind = MetricsAnnotationStore.Kind.TASK,
		) {
			store.record(label, kind)
			now += MetricsAnnotationStore.THROTTLE_INTERVAL_MS
		}

		/** Records a build outcome, whose label comes from its kind, and advances the clock. */
		fun recordBuild(kind: MetricsAnnotationStore.Kind) {
			store.recordBuild(kind)
			now += MetricsAnnotationStore.THROTTLE_INTERVAL_MS
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

	@Test
	fun `a failed build is drawn in a different colour from a task marker`() {
		val fixture = Fixture()
		fixture.record("some task")
		fixture.record("Build failed", MetricsAnnotationStore.Kind.BUILD_FAILED)

		val (_, chart) = render(fixture)

		val lines = chart.xAxis.limitLines
		assertThat(lines).hasSize(2)
		// Which colour, not merely a different one: asserting inequality alone passes just as
		// happily with the two attributes swapped, telling the user a failed build succeeded.
		assertThat(lines[0].lineColor).isEqualTo(context.resolveAttr(R.attr.colorOnSurface))
		assertThat(lines[1].lineColor).isEqualTo(context.resolveAttr(R.attr.colorError))
		// The label sits on the line, so colouring only the line would leave it unreadable.
		assertThat(lines[1].textColor).isEqualTo(lines[1].lineColor)
	}

	@Test
	fun `a build starting and finishing share one colour, distinct from a failure`() {
		val fixture = Fixture()
		fixture.record("Build started", MetricsAnnotationStore.Kind.BUILD_STARTED)
		fixture.record("Build finished", MetricsAnnotationStore.Kind.BUILD_FINISHED)
		fixture.record("Build failed", MetricsAnnotationStore.Kind.BUILD_FAILED)

		val (_, chart) = render(fixture)

		val lines = chart.xAxis.limitLines
		assertThat(lines).hasSize(3)
		// Started and finished are both outcomes worth seeing; only failure is bad news.
		assertThat(lines[0].lineColor).isEqualTo(context.resolveAttr(R.attr.colorSuccess))
		assertThat(lines[1].lineColor).isEqualTo(context.resolveAttr(R.attr.colorSuccess))
		assertThat(lines[2].lineColor).isEqualTo(context.resolveAttr(R.attr.colorError))
	}

	@Test
	fun `a cancelled build is not drawn as a failure`() {
		val fixture = Fixture()
		fixture.recordBuild(MetricsAnnotationStore.Kind.BUILD_CANCELLED)

		val (_, chart) = render(fixture)

		// The user stopped the build themselves; reporting that back in the error colour reads as
		// something having gone wrong.
		val line = chart.xAxis.limitLines.single()
		assertThat(line.lineColor).isNotEqualTo(context.resolveAttr(R.attr.colorError))
		assertThat(line.lineColor).isEqualTo(context.resolveAttr(R.attr.colorOnSurface))
	}

	@Test
	fun `a build marker takes its label from its kind, not from the recorded text`() {
		val fixture = Fixture()
		fixture.recordBuild(MetricsAnnotationStore.Kind.BUILD_FAILED)

		val (_, chart) = render(fixture)

		// Resolved at draw time, so the marker follows the system language even though the store
		// outlives the activity that recorded it.
		assertThat(
			chart.xAxis.limitLines
				.single()
				.label,
		).isEqualTo(context.getString(string.metrics_annotation_build_failed))
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
