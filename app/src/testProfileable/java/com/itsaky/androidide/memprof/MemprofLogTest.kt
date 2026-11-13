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

class MemprofLogTest {
	@Test
	fun `fills each placeholder with the next value in order`() {
		val line = MemprofLog.format("MEMPROF|x|{}|a={}|b={}", listOf(1L, "two", null))

		assertThat(line).isEqualTo("MEMPROF|x|1|a=two|b=null")
	}

	@Test
	fun `leaves placeholders without a value as written`() {
		val line = MemprofLog.format("a={}|b={}", listOf(1))

		assertThat(line).isEqualTo("a=1|b={}")
	}

	@Test
	fun `ignores values beyond the last placeholder`() {
		val line = MemprofLog.format("a={}", listOf(1, 2))

		assertThat(line).isEqualTo("a=1")
	}

	@Test
	fun `returns a format without placeholders unchanged`() {
		val line = MemprofLog.format("MEMPROF|report| plain", emptyList())

		assertThat(line).isEqualTo("MEMPROF|report| plain")
	}
}
