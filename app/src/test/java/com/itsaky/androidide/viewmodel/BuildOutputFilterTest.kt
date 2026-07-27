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

package com.itsaky.androidide.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BuildOutputFilterTest {
	@Test
	fun `empty query returns content unchanged`() {
		val content = "> Task :app:compileDebugKotlin\nBUILD SUCCESSFUL\n"
		assertSame(content, BuildOutputViewModel.filterLines(content, ""))
	}

	@Test
	fun `only matching lines are kept, case-insensitively`() {
		val content = "> Task :app:compileDebugKotlin\nwarning: deprecated API\nBUILD SUCCESSFUL\n"
		assertEquals(
			"> Task :app:compileDebugKotlin\n",
			BuildOutputViewModel.filterLines(content, "task"),
		)
	}

	@Test
	fun `no matches yields empty text`() {
		val content = "BUILD SUCCESSFUL in 1s\n"
		assertEquals("", BuildOutputViewModel.filterLines(content, "error"))
	}

	@Test
	fun `multi-line batch keeps every matching line`() {
		val content = "> Task :a\nnoise\n> Task :b\nnoise\n"
		assertEquals(
			"> Task :a\n> Task :b\n",
			BuildOutputViewModel.filterLines(content, "> Task"),
		)
	}

	@Test
	fun `batch without trailing newline still terminates matched lines`() {
		val content = "> Task :a\n> Task :b"
		assertEquals(
			"> Task :a\n> Task :b\n",
			BuildOutputViewModel.filterLines(content, "> Task"),
		)
	}

	@Test
	fun `empty query with default toggles returns prefixed content unchanged`() {
		val content = prefix() + "> Task :a\n" + prefix() + "BUILD SUCCESSFUL\n"
		assertSame(content, BuildOutputViewModel.filterLines(content, ""))
	}

	@Test
	fun `filtering does not add blank lines to newline-terminated content`() {
		val content = "> Task :a\nnoise\n"
		assertEquals(
			"> Task :a\nnoise\n",
			BuildOutputViewModel.filterLines(content, "", showTimestamps = false, showDeltas = true),
		)
	}

	@Test
	fun `prefix round-trips through display stripping`() {
		val line = prefix() + "> Task :app:build"
		assertEquals(
			"> Task :app:build",
			BuildOutputViewModel.formatLineForDisplay(line, showTimestamps = false, showDeltas = false),
		)
	}

	@Test
	fun `hiding only timestamps keeps the delta part`() {
		val line = prefix(totalDeltaMs = 65_123, stepDeltaMs = 42) + "task output"
		val displayed =
			BuildOutputViewModel.formatLineForDisplay(line, showTimestamps = false, showDeltas = true)
		assertEquals("[+01:05.123] (Δ42ms) task output", displayed)
	}

	@Test
	fun `hiding only deltas keeps the timestamp part`() {
		val line = prefix() + "task output"
		val displayed =
			BuildOutputViewModel.formatLineForDisplay(line, showTimestamps = true, showDeltas = false)
		assertEquals(line.substringBefore("] ") + "] task output", displayed)
	}

	@Test
	fun `prefix of builds longer than 99 minutes still strips`() {
		val line = prefix(totalDeltaMs = 100 * 60_000L + 7_412) + "> Task :app:lint"
		assertEquals(
			"> Task :app:lint",
			BuildOutputViewModel.formatLineForDisplay(line, showTimestamps = false, showDeltas = false),
		)
	}

	@Test
	fun `timestamp-shaped text inside a message body is preserved`() {
		val line = prefix() + "Test run started [10:22:31.004] on device"
		val displayed =
			BuildOutputViewModel.formatLineForDisplay(line, showTimestamps = false, showDeltas = false)
		assertEquals("Test run started [10:22:31.004] on device", displayed)
	}

	@Test
	fun `unprefixed lines are returned unchanged when toggles are off`() {
		val line = "some output containing [10:22:31.004] and (x42ms)"
		assertSame(
			line,
			BuildOutputViewModel.formatLineForDisplay(line, showTimestamps = false, showDeltas = false),
		)
	}

	@Test
	fun `query matches the displayed text, not the hidden prefix`() {
		val content = prefix(stepDeltaMs = 42) + "compileKotlin\n"
		assertEquals(
			"",
			BuildOutputViewModel.filterLines(content, "42ms", showTimestamps = false, showDeltas = false),
		)
		assertEquals(
			"compileKotlin\n",
			BuildOutputViewModel.filterLines(content, "compile", showTimestamps = false, showDeltas = false),
		)
	}

	private fun prefix(
		nowMs: Long = 1_722_000_000_000L,
		totalDeltaMs: Long = 65_123L,
		stepDeltaMs: Long = 42L,
	): String = BuildOutputViewModel.formatLinePrefix(nowMs, totalDeltaMs, stepDeltaMs)
}
