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
import com.itsaky.androidide.lsp.java.debug.utils.matchesSourcePath
import org.junit.Test

private const val KT_BREAKPOINT_PATH =
	"/project/app/src/main/java/com/example/app/MainActivity.kt"

private const val JAVA_BREAKPOINT_PATH =
	"/project/app/src/main/java/com/example/app/Legacy.java"

class SourceReferenceTypeSpecTest {
	@Test
	fun `java stratum path matches a kotlin breakpoint`() {
		assertThat(
			matchesSourcePath(KT_BREAKPOINT_PATH, "com/example/app/MainActivity.kt"),
		).isTrue()
	}

	@Test
	fun `kotlin stratum path cannot match, it carries no file extension`() {
		assertThat(
			matchesSourcePath(KT_BREAKPOINT_PATH, "com/example/app/MainActivity"),
		).isFalse()
	}

	@Test
	fun `kotlin stratum synthetic entry never matches`() {
		assertThat(
			matchesSourcePath(KT_BREAKPOINT_PATH, "kotlin/jvm/internal/FakeKt"),
		).isFalse()
	}

	@Test
	fun `java sources are unaffected`() {
		assertThat(
			matchesSourcePath(JAVA_BREAKPOINT_PATH, "com/example/app/Legacy.java"),
		).isTrue()
	}

	@Test
	fun `a different file in the same package does not match`() {
		assertThat(
			matchesSourcePath(KT_BREAKPOINT_PATH, "com/example/app/Other.kt"),
		).isFalse()
	}

	@Test
	fun `absent source information does not match`() {
		assertThat(matchesSourcePath(KT_BREAKPOINT_PATH, null)).isFalse()
	}
}
