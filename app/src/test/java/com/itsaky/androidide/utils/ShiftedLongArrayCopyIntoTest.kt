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

/** Copying a ring buffer into a destination the caller owns (ADFA-5526). */
@RunWith(JUnit4::class)
class ShiftedLongArrayCopyIntoTest {
	private fun buffer(): MutableShiftedLongArray {
		val buffer = MutableShiftedLongArray(4)
		// Appended the way the watchers append: newest in at 0, then shift.
		listOf(10L, 20L, 30L).forEach { value ->
			buffer[0] = value
			buffer.shift(1)
		}
		return buffer
	}

	@Test
	fun `it writes the same order toLongArray produces`() {
		val buffer = buffer()

		val dest = LongArray(buffer.size)
		assertThat(buffer.copyInto(dest).toList()).isEqualTo(buffer.toLongArray().toList())
	}

	@Test
	fun `it returns the destination it was given, not a copy`() {
		val buffer = buffer()
		val dest = LongArray(buffer.size)

		// The whole point: the caller pre-allocated this, so nothing new may be handed back.
		assertThat(buffer.copyInto(dest)).isSameInstanceAs(dest)
	}

	@Test
	fun `a destination of the wrong length is refused`() {
		val buffer = buffer()

		// A short destination truncates the history and a long one leaves a stale tail behind it,
		// and both read as data. Better to fail where the mistake is than to file a wrong graph.
		listOf(LongArray(buffer.size - 1), LongArray(buffer.size + 1)).forEach { wrong ->
			val failure = runCatching { buffer.copyInto(wrong) }.exceptionOrNull()
			assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
		}
	}

	@Test
	fun `a second copy overwrites the first, leaving nothing of it`() {
		val dest = LongArray(4)
		buffer().copyInto(dest)

		val fresh = MutableShiftedLongArray(4)
		fresh.copyInto(dest)

		// The scratch is reused across snapshots, so a stale value surviving into the next one
		// would be reported as a measurement.
		assertThat(dest.toList()).containsExactly(0L, 0L, 0L, 0L)
	}
}
