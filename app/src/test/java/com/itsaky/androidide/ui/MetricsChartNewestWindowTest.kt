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
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.NetworkUsageWatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Where the chart's viewport ends up when a page is bound before it has been laid out.
 *
 * That is the order the carousel always binds in -- `onBindViewHolder` attaches the renderer while
 * RecyclerView is still laying the page out -- and the editor rebinds the whole carousel on every
 * resume. Returning from the APK install prompt therefore left the chart parked in the zeroed head
 * of the buffer, reading -9999s and drawing nothing, until the next sample landed a redraw a second
 * or more later: an empty plot at the moment the user has just run a build and is looking straight
 * at it (ADFA-5515).
 */
@RunWith(RobolectricTestRunner::class)
class MetricsChartNewestWindowTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private fun renderer() =
		NetworkUsageChartRenderer(
			usageProvider = {
				NetworkUsageWatcher.NetworkUsage(
					LongArray(SAMPLES) { 1_000L },
					LongArray(SAMPLES) { 500L },
					LongArray(SAMPLES),
				)
			},
		)

	/**
	 * The window the chart is meant to settle on: the newest [MetricsChartRenderer.VISIBLE_SAMPLES]
	 * of them, ending on the newest sample.
	 *
	 * The window is asked to start one sample further right than this and is clamped back, since it
	 * cannot extend past the end of the data -- so the newest sample sits exactly on the right edge.
	 */
	private fun assertShowsNewestSamples(chart: SafeLineChart) {
		val newest = (SAMPLES - 1).toFloat()
		assertThat(chart.highestVisibleX).isWithin(TOLERANCE).of(newest)
		assertThat(chart.lowestVisibleX)
			.isWithin(TOLERANCE)
			.of(newest - MetricsChartRenderer.VISIBLE_SAMPLES)
	}

	@Test
	fun `a page bound before it is laid out still opens on the newest samples`() {
		val chart = SafeLineChart(context)

		// The carousel's order: attach first, lay out second.
		renderer().attach(chart)
		chart.layOutAndDraw()

		assertShowsNewestSamples(chart)
	}

	@Test
	fun `a page bound after it is laid out opens on the newest samples`() {
		val chart = SafeLineChart(context)
		chart.layOutAndDraw()

		renderer().attach(chart)

		assertShowsNewestSamples(chart)
	}

	@Test
	fun `a resize puts the window back without waiting for a sample`() {
		val chart = SafeLineChart(context)
		renderer().attach(chart)
		chart.layOutAndDraw()

		// A size change resets the chart's transform, dropping the window. Only a redraw used to
		// restore it, which is why a rotation showed samples from half an hour ago until the next
		// tick.
		chart.layOutAndDraw(width = CHART_WIDTH, height = CHART_HEIGHT + 40)

		assertShowsNewestSamples(chart)
	}

	@Test
	fun `a detached renderer stops following the chart it left`() {
		val chart = SafeLineChart(context)
		val renderer = renderer()
		renderer.attach(chart)
		chart.layOutAndDraw()
		renderer.detach()

		// A resize drops the window, and nothing should put it back: the page is on its way to
		// another renderer, and a stale listener would fight whichever one binds next.
		chart.layOutAndDraw(width = CHART_WIDTH, height = CHART_HEIGHT + 40)

		assertThat(chart.lowestVisibleX).isWithin(TOLERANCE).of(0f)
	}

	@Test
	fun `a rebind onto a new page forgets a pan on the old one`() {
		val renderer = renderer()
		val first = SafeLineChart(context)
		renderer.attach(first)
		first.layOutAndDraw()

		// The user pans. From here the viewport on *this* chart is theirs, not the renderer's.
		checkNotNull(first.onChartGestureListener).onChartTranslate(null, -20f, 0f)

		// A resume rebinds the carousel, which attaches the replacement page before the outgoing
		// one is recycled -- so the detach naming the old chart arrives afterwards and finds a
		// different one bound. The pan belonged to the page the user left; the fresh page must
		// still open on the newest samples.
		val second = SafeLineChart(context)
		renderer.attach(second)
		renderer.detachIfAttached(first)
		second.layOutAndDraw()

		assertShowsNewestSamples(second)
	}

	private companion object {
		/** Longer than the visible window, so there is a wrong end of the buffer to park in. */
		const val SAMPLES = 200

		/** The viewport is computed in pixels and read back as a value, so it lands near-exactly. */
		const val TOLERANCE = 0.01f
	}
}
