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
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The destinations a crash handler snapshots into, so that it never has to allocate (ADFA-5526).
 */
@RunWith(JUnit4::class)
class MetricsScratchTest {
	@After
	fun tearDown() = MetricsScratch.resetForTesting()

	@Test
	fun `one claim at a time`() {
		val scratch = MetricsScratch(entries = 4, memorySeries = 2)

		assertThat(scratch.claim()).isTrue()
		// Two writers into one array is a scrambled file, so the second caller is refused and
		// allocates for itself rather than waiting -- a crash must not block on an export.
		assertThat(scratch.claim()).isFalse()

		scratch.release()
		assertThat(scratch.claim()).isTrue()
	}

	@Test
	fun `every destination is the retained length`() {
		val scratch = MetricsScratch(entries = 7, memorySeries = 3)

		// copyInto requires an exact-length destination, so a mismatch here is a crash-time failure.
		val all =
			listOf(
				scratch.memoryTimes,
				scratch.networkTimes,
				scratch.networkReceived,
				scratch.networkTransmitted,
				scratch.powerTimes,
				scratch.temperature,
				scratch.power,
				scratch.thermal,
			) + scratch.memoryValues
		all.forEach { assertThat(it.size).isEqualTo(7) }
		assertThat(scratch.memoryValues).hasSize(3)
	}

	@Test
	fun `installing is idempotent, so a second call keeps the first arrays`() {
		MetricsScratch.install(entries = 4, memorySeries = 1)
		val first = MetricsScratch.instance

		MetricsScratch.install(entries = 99, memorySeries = 1)

		// Replacing it would hand a second set of destinations to whoever already held the first.
		assertThat(MetricsScratch.instance).isSameInstanceAs(first)
		assertThat(MetricsScratch.instance!!.entries).isEqualTo(4)
	}

	@Test
	fun `there is no scratch until it is installed`() {
		assertThat(MetricsScratch.instance).isNull()
	}

	@Test
	fun `the default size matches the retained history`() {
		MetricsScratch.install()

		// If these drift apart, copyInto throws at crash time -- exactly when nothing may throw.
		assertThat(MetricsScratch.instance!!.entries).isEqualTo(MemoryUsageWatcher.MAX_USAGE_ENTRIES)
		assertThat(MetricsScratch.instance!!.memoryValues).hasSize(MetricsCsv.MEMORY_COLUMNS.size)
	}
}
