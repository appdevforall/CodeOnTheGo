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
package com.itsaky.androidide.lsp.java.actions

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [isJavaFileName] decides [BaseJavaCodeAction.prepare]'s visibility from the file name alone, with
 * no [java.nio.file.Files] call, since `prepare()` runs on the UI thread.
 */
class IsJavaFileNameTest {
	@Test
	fun `a dot-java file is a java file`() {
		assertThat(isJavaFileName("Foo.java")).isTrue()
	}

	@Test
	fun `a non-java file is not a java file`() {
		assertThat(isJavaFileName("Foo.kt")).isFalse()
	}

	@Test
	fun `module-info java is not a java file`() {
		assertThat(isJavaFileName("module-info.java")).isFalse()
	}

	@Test
	fun `package-info java is not a java file`() {
		assertThat(isJavaFileName("package-info.java")).isFalse()
	}
}
