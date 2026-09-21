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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.time.ZoneId

/** The one name every exported metrics file gets (ADFA-5531's rule (c)). */
@RunWith(JUnit4::class)
class MetricsFileNameTest {
	private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

	@Test
	fun `the name is the local time to the millisecond, and the extension`() {
		val name = MetricsFileName.forTime(T0, "csv", zone)

		assertThat(name).isEqualTo("2026_09_06_22_33_40_123.csv")
	}

	@Test
	fun `the image and the data exported at one moment differ only in extension`() {
		// Which is the point of rule (c): a pair exported together sorts together, and neither
		// leads with a chart title that would sort them apart.
		val csv = MetricsFileName.forTime(T0, "csv", zone)
		val png = MetricsFileName.forTime(T0, "png", zone)

		assertThat(csv.removeSuffix(".csv")).isEqualTo(png.removeSuffix(".png"))
	}

	@Test
	fun `names sort in the order the files were written`() {
		val earlier = MetricsFileName.forTime(T0, "csv", zone)
		val later = MetricsFileName.forTime(T0 + 1L, "csv", zone)
		val muchLater = MetricsFileName.forTime(T0 + 86_400_000L, "csv", zone)

		// A directory listing is sorted lexicographically, so the format has to be too -- which is
		// why it is fixed-width and big-endian rather than anything friendlier to read.
		assertThat(listOf(muchLater, later, earlier).sorted())
			.containsExactly(earlier, later, muchLater)
			.inOrder()
	}

	private companion object {
		/** 2026-09-06T22:33:40.123 in America/Los_Angeles. */
		const val T0 = 1_788_759_220_123L
	}
}
