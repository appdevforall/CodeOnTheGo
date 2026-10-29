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

/** Pins [MemprofLines] against the regexes the memprof harness scripts parse them with. */
class MemprofLinesTest {
	@Test
	fun `phaseLine matches the harness PHASE regex and carries only its explicit details`() {
		val line = MemprofLines.phaseLine("cache_parsed", 1_700_000_000_123, mapOf("bytes" to 512, "durationMs" to 40))

		val match = PHASE_REGEX.find(line)

		assertThat(match).isNotNull()
		assertThat(match!!.groupValues[1]).isEqualTo("cache_parsed")
		assertThat(match.groupValues[2]).isEqualTo("1700000000123")
		assertThat(match.groupValues[3]).isEqualTo("|bytes=512|durationMs=40")
	}

	@Test
	fun `phaseLine with no details still matches the harness PHASE regex`() {
		val line = MemprofLines.phaseLine("workspace_built", 1_700_000_000_000, emptyMap())

		assertThat(PHASE_REGEX.find(line)).isNotNull()
		assertThat(line).isEqualTo("PHASE|workspace_built|1700000000000")
	}

	@Test
	fun `phaseAbandonedLine does not match the harness PHASE regex`() {
		val line = MemprofLines.phaseAbandonedLine("source_scan_complete", 1_700_000_000_000, emptyMap())

		assertThat(PHASE_REGEX.containsMatchIn(line)).isFalse()
	}

	@Test
	fun `fileLine matches the harness MEMPROF file regex with the fixed field shape`() {
		val line =
			MemprofLines.fileLine(
				nowMs = 1_700_000_000_456,
				allocBytes = 2048,
				durationMs = 12,
				path = "/proj/Foo.kt",
				name = "Index Kotlin file",
				completed = true,
			)

		val match = FILE_REGEX.find(line)

		assertThat(match).isNotNull()
		assertThat(match!!.groupValues[1]).isEqualTo("1700000000456")
		assertThat(match.groupValues[2])
			.isEqualTo("allocBytes=2048|durationMs=12|path=/proj/Foo.kt|name=Index Kotlin file|completed=true")
	}

	private companion object {
		/** `parse_phases.py`'s PHASE regex. */
		val PHASE_REGEX = Regex("""PHASE\|([a-z_]+)\|(\d+)((?:\|[a-zA-Z]+=[^|\s]+)*)""")

		/** `alloc_budget.py`'s FILE regex. */
		val FILE_REGEX = Regex("""MEMPROF\|file\|(\d+)\|(.*)""")
	}
}
