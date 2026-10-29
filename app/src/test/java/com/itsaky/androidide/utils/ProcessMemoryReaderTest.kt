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
import org.robolectric.RobolectricTestRunner

/**
 * Which read is used for which process, and what the cheap one makes of a rollup (ADFA-5574).
 *
 * The equivalence of the two reads is deliberately not asserted here. `Debug.getMemoryInfo` is not
 * meaningfully callable off a device, and the interesting part of the claim is a device fact: for a
 * plain JVM the rollup agrees with it to 0.009%, while for the app's own process it reads ~124MB low
 * because graphics memory is accounted through memtrack rather than through `/proc/pid/smaps`. That
 * is recorded on the ticket from a real measurement. What can be pinned here is the rule that acts
 * on it, and the parse.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessMemoryReaderTest {
	@Test
	fun `the app's own process keeps the expensive read`() {
		// It is the only Zygote fork the carousel plots, and the only one with GPU memory. A rollup
		// cannot see EGL or GL mtrack, so this process would silently lose about a quarter of its
		// footprint.
		val reader = ProcessMemoryReaders.chooseReader(pid = OWN_PID, ownPid = OWN_PID, rollupSupported = true)

		assertThat(reader).isSameInstanceAs(DebugMemoryInfoReader)
	}

	@Test
	fun `every other process gets the rollup`() {
		// The tooling server and the Gradle daemon: plain OpenJDK processes with no graphics
		// memory, where the rollup is the same number for less than half the cost.
		val reader = ProcessMemoryReaders.chooseReader(pid = OTHER_PID, ownPid = OWN_PID, rollupSupported = true)

		assertThat(reader).isSameInstanceAs(SmapsRollupReader)
	}

	@Test
	fun `a kernel without a rollup falls back for everything`() {
		// smaps_rollup arrived in Linux 4.14, so Android 10 in practice, and minSdk here is 28.
		// Such a device gets exactly what it had before this change.
		val reader = ProcessMemoryReaders.chooseReader(pid = OTHER_PID, ownPid = OWN_PID, rollupSupported = false)

		assertThat(reader).isSameInstanceAs(DebugMemoryInfoReader)
	}

	@Test
	fun `the rollup's own Pss is read, not one of the fields that start like it`() {
		// A rollup carries Pss_Anon, Pss_File, Pss_Shmem and Pss_Dirty as well, and matching on
		// "Pss" alone would take whichever came first -- here Pss_Dirty, a different number.
		val value =
			parse(
				"""
				02000000-7ffc009000 ---p 00000000 00:00 0                                [rollup]
				Rss:              653352 kB
				Pss_Dirty:        379731 kB
				Pss:              441070 kB
				Pss_Anon:         385191 kB
				SwapPss:              15 kB
				""".trimIndent(),
			)

		assertThat(value).isEqualTo(441070)
	}

	@Test
	fun `a rollup with no Pss line is unavailable rather than zero`() {
		// Zero is a measurement -- a process really using no memory. Unavailable is the absence of
		// one, and the caller falls back rather than plotting it.
		assertThat(parse("Rss:              653352 kB")).isEqualTo(ProcessMemoryReaders.UNAVAILABLE)
	}

	@Test
	fun `a Pss line with no number is unavailable`() {
		assertThat(parse("Pss:                 kB")).isEqualTo(ProcessMemoryReaders.UNAVAILABLE)
	}

	@Test
	fun `a process with no rollup at all is unavailable`() {
		// The pid is gone, or the kernel has no rollup. Either way this must not throw: it runs on
		// the sampling thread once a second.
		val value = SmapsRollupReader.totalKb(NO_SUCH_PID, android.os.Debug.MemoryInfo())

		assertThat(value).isEqualTo(ProcessMemoryReaders.UNAVAILABLE)
	}

	/**
	 * The reader's own line-picking and parsing, over a fixture.
	 *
	 * Through [SmapsRollupReader.pssKbFrom], not by finding the line here first: an earlier version
	 * of this helper did its own `startsWith("Pss:")` and so pinned only the number extraction --
	 * loosening the reader's prefix to "Pss" left every case below green.
	 */
	private fun parse(rollup: String): Int = SmapsRollupReader.pssKbFrom(rollup.lineSequence())

	private companion object {
		const val OWN_PID = 4242

		const val OTHER_PID = 4243

		/** Comfortably above any real pid on a device, so `/proc/<pid>` cannot exist. */
		const val NO_SUCH_PID = 999_999
	}
}
