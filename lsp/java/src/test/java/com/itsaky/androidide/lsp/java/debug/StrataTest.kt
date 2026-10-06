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
package com.itsaky.androidide.lsp.java.debug

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.java.debug.utils.isInlinedBody
import org.junit.Test

class StrataTest {
	@Test
	fun `a line the class wrote itself is not inlined`() {
		assertThat(
			isInlinedBody(
				kotlinSourceName = "DebugFixture.kt",
				javaSourceName = "DebugFixture.kt",
				kotlinLine = 27,
				javaLine = 27,
			),
		).isFalse()
	}

	@Test
	fun `a body inlined from another file is inlined`() {
		// Greeter.greetAll output line 60: the stdlib map body.
		assertThat(
			isInlinedBody(
				kotlinSourceName = "_Collections.kt",
				javaSourceName = "DebugFixture.kt",
				kotlinLine = 1586,
				javaLine = 60,
			),
		).isTrue()
	}

	@Test
	fun `a body inlined from the same file is inlined`() {
		// Greeter.timed output line 64: measured's body, declared in DebugFixture.kt itself, so the
		// name matches and only the line differs. Comparing the name alone would miss this.
		assertThat(
			isInlinedBody(
				kotlinSourceName = "DebugFixture.kt",
				javaSourceName = "DebugFixture.kt",
				kotlinLine = 7,
				javaLine = 64,
			),
		).isTrue()
	}

	@Test
	fun `the compiler's placeholder file is not inlined`() {
		// fake.kt reads as a foreign file but is generated code, so SMAPBuilder gives it no call
		// site and KotlinDebug would answer by best match.
		assertThat(
			isInlinedBody(
				kotlinSourceName = "fake.kt",
				javaSourceName = "DebugFixture.kt",
				kotlinLine = 1,
				javaLine = 42,
			),
		).isFalse()
	}

	@Test
	fun `a location with no Kotlin stratum name is not inlined`() {
		assertThat(
			isInlinedBody(
				kotlinSourceName = null,
				javaSourceName = "Greeter.java",
				kotlinLine = 12,
				javaLine = 12,
			),
		).isFalse()
	}
}
