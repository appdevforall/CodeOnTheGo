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
import androidx.core.widget.ImageViewCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.LayoutMemUsageBinding
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.NetworkUsageWatcher
import com.itsaky.androidide.utils.PowerUsageWatcher
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What has to survive the carousel moving between the editor and its floating window.
 *
 * Both cases here were reported from a device and neither had a test. Undocking inflates a fresh
 * layout from a plain window context and rebinds the same controller into it, which is a different
 * enough environment from the editor that things correct in one are wrong in the other.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsCarouselRebindTest {
	private val context: Context =
		ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_AndroidIDE)

	private val controllers = mutableListOf<MetricsCarouselController>()

	@After
	fun tearDown() {
		controllers.forEach { it.unbind() }
		controllers.clear()
	}

	private fun controller() =
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
		).also(controllers::add)

	private fun strip() = LayoutMemUsageBinding.inflate(LayoutInflater.from(context))

	/** The pager needs a size before a chart page can produce a bitmap to export. */
	private fun laidOut(binding: LayoutMemUsageBinding) {
		val width = View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
		val height = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
		binding.root.measure(width, height)
		binding.root.layout(0, 0, 720, 400)
	}

	@Test
	fun `the page survives a rebind`() {
		val controller = controller()
		val docked = strip()
		controller.bind(docked)
		docked.metricsPager.setCurrentItem(1, false)

		// Undocking rebinds the same controller into a freshly inflated layout, whose ViewPager2
		// starts at zero. Undocking while reading the network chart put the floating window on
		// the memory chart.
		val floating = strip()
		controller.bind(floating)

		assertThat(floating.metricsPager.currentItem).isEqualTo(1)
	}

	@Test
	fun `the page title follows the restored page, not the first one`() {
		val controller = controller()
		val docked = strip()
		controller.bind(docked)
		docked.metricsPager.setCurrentItem(1, false)
		val title = docked.metricsTitle.text.toString()

		val floating = strip()
		controller.bind(floating)

		// A restored page with the first page's title would be worse than not restoring at all.
		assertThat(floating.metricsTitle.text.toString()).isEqualTo(title)
	}

	@Test
	fun `a second snapshot is refused while the first is still being written`() {
		val controller = controller()
		val binding = strip()
		controller.bind(binding)
		laidOut(binding)

		// The camera button is not debounced, and each tap used to launch its own coroutine over
		// the same scratch directory -- and, within the same second, the same filename, since the
		// name is the chart label plus a whole-second timestamp. The first export could then hand
		// another app a URI whose file the second had already replaced.
		assertThat(controller.exportSnapshot()).isTrue()
		assertThat(controller.exportSnapshot()).isFalse()
	}

	@Test
	fun `both arrows are tinted, whatever inflated them`() {
		val binding = strip()
		controller().bind(binding)

		// app:tint is applied by AppCompat, and only when its factory is on the inflater. The
		// floating window inflates from a plain window context, so there the arrows came out as
		// ordinary ImageButtons and the vector's own android:tint="#000000" won -- black arrows
		// on a near-black strip, reported from a device as "the arrows are not visible".
		val previous = ImageViewCompat.getImageTintList(binding.metricsPrevious)
		val next = ImageViewCompat.getImageTintList(binding.metricsNext)

		assertThat(previous).isNotNull()
		assertThat(next).isNotNull()
		assertThat(previous!!.defaultColor).isNotEqualTo(BLACK)
		assertThat(next!!.defaultColor).isNotEqualTo(BLACK)
		assertThat(previous.defaultColor).isEqualTo(next.defaultColor)
	}

	@Test
	fun `dimming an end arrow changes its alpha, not its tint`() {
		val binding = strip()
		controller().bind(binding)

		// The two are orthogonal, which is why asserting the arrows share a tint does not
		// contradict their looking different at the ends of the carousel: the tint is the colour
		// the glyph is drawn in, and the dimming is alpha over the top of it. A later change that
		// dimmed through a state-aware ColorStateList instead would break that, and this says so.
		assertThat(binding.metricsPager.currentItem).isEqualTo(0)
		assertThat(binding.metricsPrevious.alpha).isLessThan(binding.metricsNext.alpha)

		val previous = ImageViewCompat.getImageTintList(binding.metricsPrevious)!!
		val next = ImageViewCompat.getImageTintList(binding.metricsNext)!!
		assertThat(previous.defaultColor).isEqualTo(next.defaultColor)
		// One colour, no per-state variation: nothing here depends on the enabled state.
		assertThat(previous.isStateful).isFalse()
	}

	@Test
	fun `the arrow tint is the colour the title uses`() {
		val binding = strip()
		controller().bind(binding)

		// The arrows sit either side of the title and should read as the same control surface.
		val tint = ImageViewCompat.getImageTintList(binding.metricsPrevious)!!.defaultColor
		assertThat(tint).isEqualTo(binding.metricsTitle.currentTextColor)
	}

	private companion object {
		const val TEST_UID = 10_123
		const val BLACK = 0xFF000000.toInt()
	}
}
