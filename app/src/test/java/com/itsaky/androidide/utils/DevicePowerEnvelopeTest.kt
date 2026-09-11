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

package com.itsaky.androidide.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which battery readings the power page will believe (ADFA-5499).
 *
 * A kernel that reports CURRENT_NOW in milliamps rather than microamps divides every reading by a
 * thousand, and a single sample cannot tell that from a genuinely tiny draw. So the envelope is a
 * plausibility floor rather than a detector, and what these cases pin is that the floor is placed
 * where a misreported build actually lands. The earlier floor was 1,000uW and the comment claimed
 * it caught the case; five watts misreported is 5,000uW, which sailed through.
 */
@RunWith(RobolectricTestRunner::class)
class DevicePowerEnvelopeTest {
	private val source = DevicePowerSource(ApplicationProvider.getApplicationContext<Context>())

	@Test
	fun `a five-watt build misreported in milliamps is corrected, not dropped`() {
		// 5W at 4V is 1.25A; a milliamp kernel reports 1250 where microamps would say 1_250_000,
		// so the product comes out a thousand times small. This used to answer UNAVAILABLE, which
		// identified the misreport and then discarded the sample.
		assertThat(source.microWattsOrUnavailable(microAmps = 1_250, milliVolts = 4_000))
			.isEqualTo(5_000_000L)
	}

	@Test
	fun `the Galaxy Note 20 Ultra's own reading becomes a number rather than n slash a`() {
		// Measured on the device: CURRENT_NOW 318 at 3807mV, with the editor open after a build.
		// Taken at face value that is 1,210 microwatts -- 1.2mW for a phone running an IDE -- and
		// being below the floor it was dropped, so the Power series read "n/a" on every sample for
		// the life of the session while temperature plotted normally.
		val microWatts = source.microWattsOrUnavailable(microAmps = 318, milliVolts = 3_807)

		assertThat(microWatts).isNotEqualTo(PowerUsageWatcher.UNAVAILABLE)
		assertThat(microWatts).isEqualTo(1_210_626L)
	}

	@Test
	fun `a discharging misreport keeps its sign through the correction`() {
		// The same device discharging: CURRENT_NOW -496 at 3731mV, i.e. 1.85W leaving the battery.
		assertThat(source.microWattsOrUnavailable(microAmps = -496, milliVolts = 3_731))
			.isEqualTo(-1_850_576L)
	}

	@Test
	fun `the same build reported correctly is believed`() {
		assertThat(source.microWattsOrUnavailable(microAmps = 1_250_000, milliVolts = 4_000))
			.isEqualTo(5_000_000L)
	}

	@Test
	fun `a discharging reading keeps its sign`() {
		// CURRENT_NOW is negative for current leaving the battery. The envelope tests the
		// magnitude; the sign survives, because the chart decides for itself what to plot.
		assertThat(source.microWattsOrUnavailable(microAmps = -1_250_000, milliVolts = 4_000))
			.isEqualTo(-5_000_000L)
	}

	@Test
	fun `an exactly-zero reading is a reading, not an absence`() {
		// A device on mains with a full battery really does draw nothing through it.
		assertThat(source.microWattsOrUnavailable(microAmps = 0, milliVolts = 4_000)).isEqualTo(0L)
	}

	@Test
	fun `an absurdly large reading is rejected the other way`() {
		// The mismatch in the opposite direction: nanoamps read as microamps. No phone draws 400W.
		assertThat(source.microWattsOrUnavailable(microAmps = 100_000_000, milliVolts = 4_000))
			.isEqualTo(PowerUsageWatcher.UNAVAILABLE)
	}
}
