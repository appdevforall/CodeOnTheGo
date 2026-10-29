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
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.LayoutMemUsageBinding
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.MetricsAnnotationStore
import com.itsaky.androidide.utils.NetworkUsageWatcher
import com.itsaky.androidide.utils.PowerUsageWatcher
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins that every control in the metrics carousel answers a long press (ADFA-5510).
 *
 * The assertion is that a listener is installed, not that a tooltip appears: TooltipManager reads
 * the docs database from device storage in its static initialiser and cannot be loaded off-device.
 * Whether a tag has copy behind it is the database's business, not this code's.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsCarouselHelpTest {
	private val context: Context =
		ContextThemeWrapper(
			ApplicationProvider.getApplicationContext(),
			R.style.Theme_AndroidIDE,
		)

	/**
	 * Every controller this test builds, so [tearDown] can release them.
	 *
	 * Each one installs itself as the listener on three watchers; a controller left bound holds
	 * its views and goes on being fed for the rest of the JVM's life, and the tests then run
	 * against a growing pile of live carousels.
	 */
	private val controllers = mutableListOf<MetricsCarouselController>()

	@After
	fun tearDown() {
		controllers.forEach { it.unbind() }
		controllers.clear()
	}

	private fun boundStrip(): LayoutMemUsageBinding {
		val binding = LayoutMemUsageBinding.inflate(LayoutInflater.from(context))
		controller().bind(binding)
		return binding
	}

	private fun controller() = newController().also(controllers::add)

	private fun newController() =
		MetricsCarouselController(
			memoryUsageWatcher = MemoryUsageWatcher(),
			networkUsageWatcher = NetworkUsageWatcher(uid = TEST_UID),
			powerUsageWatcher =
				PowerUsageWatcher(
					source = {
						PowerUsageWatcher.PowerReading(
							temperatureMilliCelsius = 30_000L,
							powerMicroWatts = 1_000_000L,
							thermalStatus = 0,
							battery = PowerUsageWatcher.BatteryState.UNKNOWN,
						)
					},
				),
			lineColorFor = { android.graphics.Color.BLUE },
			annotations = MetricsAnnotationStore(),
		)

	@Test
	fun `every control in the strip answers a long press`() {
		val binding = boundStrip()

		val controls: List<Pair<String, View>> =
			listOf(
				"panel" to binding.root,
				"title" to binding.metricsTitle,
				"previous" to binding.metricsPrevious,
				"next" to binding.metricsNext,
				"snapshot" to binding.metricsSnapshot,
				"battery" to binding.metricsBattery,
				"undocked message" to binding.metricsUndockedMessage,
			)

		val unwired = controls.filterNot { (_, view) -> view.isLongClickable }.map { it.first }
		assertThat(unwired).isEmpty()
	}

	@Test
	fun `the arrow at the end of the carousel is dimmed but still answers a long press`() {
		val binding = boundStrip()

		// On the first page there is nowhere to go back to. Disabling that arrow would leave it
		// consuming the long press and dropping it, so the one arrow whose greyed-out state a
		// user is likeliest to ask about was the one with no answer.
		assertThat(binding.metricsPager.currentItem).isEqualTo(0)
		assertThat(binding.metricsPrevious.alpha).isLessThan(1f)
		assertThat(binding.metricsPrevious.isEnabled).isTrue()
		assertThat(binding.metricsPrevious.isLongClickable).isTrue()
		// It answers no tap, though: that is the narrower and the true statement.
		assertThat(binding.metricsPrevious.isClickable).isFalse()

		// ...and the other end is at full strength, so the dimming means something.
		assertThat(binding.metricsNext.alpha).isEqualTo(1f)
		assertThat(binding.metricsNext.isClickable).isTrue()
	}

	@Test
	fun `a dimmed arrow still reads as disabled to a screen reader`() {
		val binding = boundStrip()

		// Alpha is invisible to accessibility services, so dropping isEnabled would have taken
		// the state away from exactly the users who cannot see the dimming.
		val previous = nodeInfoFor(binding.metricsPrevious)
		assertThat(previous.isEnabled).isFalse()
		assertThat(previous.isClickable).isFalse()

		val next = nodeInfoFor(binding.metricsNext)
		assertThat(next.isEnabled).isTrue()
		assertThat(next.isClickable).isTrue()
	}

	/** What a screen reader would be handed for [view]. */
	private fun nodeInfoFor(view: View): AccessibilityNodeInfoCompat {
		val info = view.createAccessibilityNodeInfo()
		assertThat(info).isNotNull()
		return AccessibilityNodeInfoCompat.wrap(info!!)
	}

	@Test
	fun `an unbound strip has no help wired`() {
		// Guards the test above: if inflation alone made these long-clickable, it would pass
		// against a controller that wires nothing.
		val binding = LayoutMemUsageBinding.inflate(LayoutInflater.from(context))

		assertThat(binding.metricsPrevious.isLongClickable).isFalse()
		assertThat(binding.metricsSnapshot.isLongClickable).isFalse()
	}

	@Test
	fun `unbinding releases the help listeners`() {
		val binding = LayoutMemUsageBinding.inflate(LayoutInflater.from(context))
		val controller = controller()
		controller.bind(binding)
		controller.unbind()

		assertThat(binding.metricsPrevious.isLongClickable).isFalse()
		assertThat(binding.metricsSnapshot.isLongClickable).isFalse()
	}

	@Test
	fun `each control is wired to its own tag`() {
		val binding = LayoutMemUsageBinding.inflate(LayoutInflater.from(context))
		val targets = controller().helpTargets(binding)

		// Asserting the constants against their own literals, as this test used to, would pass
		// just as happily with two controls' tags swapped.
		val byTag = targets.associate { (view, tag) -> tag to view }
		assertThat(byTag[TooltipTag.CAROUSEL_PREVIOUS]).isSameInstanceAs(binding.metricsPrevious)
		assertThat(byTag[TooltipTag.CAROUSEL_NEXT]).isSameInstanceAs(binding.metricsNext)
		assertThat(byTag[TooltipTag.CAROUSEL_SNAPSHOT]).isSameInstanceAs(binding.metricsSnapshot)
		assertThat(byTag[TooltipTag.CAROUSEL_BATTERY]).isSameInstanceAs(binding.metricsBattery)
		assertThat(byTag[TooltipTag.CAROUSEL_TITLE]).isSameInstanceAs(binding.metricsTitle)
		assertThat(byTag[TooltipTag.CAROUSEL_UNDOCKED]).isSameInstanceAs(binding.metricsUndockedMessage)
		// Every tag distinct, so no two controls can answer with the same one.
		assertThat(targets.map { it.second }.toSet()).hasSize(targets.size)
	}

	@Test
	fun `unbinding keeps the undocked message answering`() {
		val binding = LayoutMemUsageBinding.inflate(LayoutInflater.from(context))
		val controller = controller()
		controller.bind(binding)
		controller.unbind()

		// That view becomes visible *because* the carousel unbound, so clearing its listener left
		// the one control a user can still reach with no help at all.
		assertThat(binding.metricsUndockedMessage.isLongClickable).isTrue()
	}

	@Test
	fun `a long press below the plot asks about the sampling rate, not the metric`() {
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
			)
		renderer.attach(chart)
		chart.layOutAndDraw()

		val handler = chart.viewPortHandler
		// Guards the two assertions below: on an unlaid-out chart both points land on one edge.
		assertThat(handler.contentBottom()).isLessThan(CHART_HEIGHT.toFloat())

		// Below the plot is the time axis, which is what the sampling rate belongs to.
		assertThat(renderer.helpTagAt(handler.contentBottom() + 1f)).isEqualTo(TooltipTag.CAROUSEL_AXIS_TIME)
		// Inside the plot, the metric itself answers.
		assertThat(renderer.helpTagAt((handler.contentTop() + handler.contentBottom()) / 2f))
			.isEqualTo(TooltipTag.CAROUSEL_CHART_NETWORK)
	}

	private companion object {
		const val TEST_UID = 10_123
		const val SAMPLES = 60
	}
}
