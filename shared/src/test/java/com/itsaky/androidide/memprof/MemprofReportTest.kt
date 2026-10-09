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

package com.itsaky.androidide.memprof

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneOffset

class MemprofReportTest {
	private val report = MemprofReport(processStartMs = START, heapLimitBytes = 512 * MB, zone = ZoneOffset.UTC)

	@Test
	fun `a finished phase reports its time, allocation, retention, peak and blocking GCs`() {
		val phase = report.phaseStarted("Parse project model", START + 12_000, counters(alloc = 100, freed = 60, gcs = 3))
		report.sampled(usedBytes = 210 * MB)
		report.phaseEnded(phase, START + 13_200, counters(alloc = 149, freed = 74, gcs = 3), emptyMap())

		val row = rowOf(render(), "Parse project model")

		assertThat(row).containsExactly("Parse project model", "+12.0 s", "1.2 s", "49 MB", "35 MB", "210 MB", "0").inOrder()
	}

	@Test
	fun `a phase still running is measured up to the render and marked in progress`() {
		report.phaseStarted("Index Kotlin sources", START + 80_000, counters(alloc = 1_000, gcs = 10))

		val rendered = report.render(START + 90_000, counters(alloc = 3_000, gcs = 407, used = 500))
		val row = rowOf(rendered, "Index Kotlin sources")

		assertThat(row)
			.containsExactly(
				"Index Kotlin sources",
				"+80.0 s",
				"10.0 s",
				"2,000 MB",
				"2,000 MB",
				"500 MB",
				"397",
				"(in progress)",
			).inOrder()
	}

	@Test
	fun `an abandoned phase is shown as stopped early, with its details`() {
		val phase = report.phaseStarted("Index Kotlin sources", START, counters())
		report.phaseEnded(phase, START + 1, counters(), mapOf("indexed" to 79), completed = false)

		assertThat(rowOf(render(), "Index Kotlin sources").last()).isEqualTo("(stopped early) indexed=79")
	}

	@Test
	fun `a byte-valued detail is shown in megabytes`() {
		val phase = report.phaseStarted("Parse project model", START, counters())
		report.phaseEnded(phase, START + 1, counters(), mapOf("bytes" to 20 * MB, "files" to 1683))

		assertThat(rowOf(render(), "Parse project model").last()).isEqualTo("bytes=20 MB files=1,683")
	}

	@Test
	fun `repeated work names its largest single instance`() {
		report.sectionEnded("Index Kotlin file", "HelpActivity.kt", durationMs = 800, allocatedBytes = 9 * MB)
		report.sectionEnded("Index Kotlin file", "ActionItem.kt", durationMs = 31_400, allocatedBytes = 2_049 * MB)

		val row = rowOf(render(), "Index Kotlin file")

		assertThat(row)
			.containsExactly("Index Kotlin file", "2", "32.2 s", "2,058 MB", "ActionItem.kt: 2,049 MB in 31.4 s")
			.inOrder()
	}

	@Test
	fun `the heap peak names the phase it happened in`() {
		val phase = report.phaseStarted("Index Kotlin sources", START, counters())
		report.sampled(usedBytes = 512 * MB)
		report.phaseEnded(phase, START + 1, counters(used = 300), emptyMap())

		assertThat(render()).contains("Heap peak 512 MB of 512 MB, during \"Index Kotlin sources\"")
	}

	@Test
	fun `a peak before any phase is attributed to no phase`() {
		report.sampled(usedBytes = 100 * MB)

		assertThat(render()).contains("Heap peak 100 MB of 512 MB, outside any phase")
	}

	@Test
	fun `a report with no phases says so`() {
		assertThat(render()).contains("Phases\n  none yet")
	}

	@Test
	fun `open phases are tracked until each one ends`() {
		val phase = report.phaseStarted("Parse project model", START, counters())
		assertThat(report.hasOpenPhases).isTrue()

		report.phaseEnded(phase, START + 1, counters(), emptyMap())

		assertThat(report.hasOpenPhases).isFalse()
	}

	private fun render(): String = report.render(START + 200_000, counters())

	private fun rowOf(
		rendered: String,
		title: String,
	): List<String> =
		rendered
			.lines()
			.single { it.trimStart().startsWith(title) }
			.trim()
			.split(Regex("\\s{2,}"))

	private fun counters(
		alloc: Long = 0,
		freed: Long = 0,
		gcs: Long = 0,
		used: Long = 0,
	) = HeapCounters(allocatedBytes = alloc * MB, freedBytes = freed * MB, blockingGcCount = gcs, usedBytes = used * MB)

	private companion object {
		const val MB = 1024L * 1024L
		const val START = 1_790_000_000_000L
	}
}
