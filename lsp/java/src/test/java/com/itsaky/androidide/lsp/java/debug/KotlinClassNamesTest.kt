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
import com.itsaky.androidide.lsp.java.debug.utils.classifierBinaryNameOrNull
import com.itsaky.androidide.lsp.java.debug.utils.fileFacadeBinaryName
import com.itsaky.androidide.lsp.java.debug.utils.isKotlinSource
import com.itsaky.androidide.lsp.java.debug.utils.kotlinBinaryNames
import org.junit.Test

class KotlinClassNamesTest {
	@Test
	fun `recognises kotlin sources by extension`() {
		assertThat(isKotlinSource("/src/com/example/Foo.kt")).isTrue()
		assertThat(isKotlinSource("/src/com/example/Foo.java")).isFalse()
		assertThat(isKotlinSource("/src/com/example/Foo.kts")).isFalse()
	}

	@Test
	fun `derives file facade from package and file name`() {
		assertThat(fileFacadeBinaryName("com.example", "Foo")).isEqualTo("com.example.FooKt")
		assertThat(fileFacadeBinaryName("com.example", "utils")).isEqualTo("com.example.UtilsKt")
		assertThat(fileFacadeBinaryName("", "Foo")).isEqualTo("FooKt")
	}

	@Test
	fun `a file name that cannot start a java identifier is prefixed`() {
		assertThat(fileFacadeBinaryName("com.example", "2foo")).isEqualTo("com.example._2fooKt")
		assertThat(fileFacadeBinaryName("com.example", "_foo")).isEqualTo("com.example._fooKt")
	}

	@Test
	fun `classifier keys convert internal names to binary names`() {
		assertThat(classifierBinaryNameOrNull("com/example/Greeter"))
			.isEqualTo("com.example.Greeter")
		assertThat(classifierBinaryNameOrNull("com/example/Greeter\$Inner"))
			.isEqualTo("com.example.Greeter\$Inner")
		assertThat(classifierBinaryNameOrNull("com/example/Greeter\$Companion"))
			.isEqualTo("com.example.Greeter\$Companion")
	}

	@Test
	fun `callable and property keys are not classifiers`() {
		assertThat(classifierBinaryNameOrNull("com.example.topLevel(kotlin.Int)")).isNull()
		assertThat(classifierBinaryNameOrNull("com/example/Greeter#name")).isNull()
		assertThat(classifierBinaryNameOrNull("com.example#counter")).isNull()
		assertThat(classifierBinaryNameOrNull("com.example.SomeAlias")).isNull()
	}

	@Test
	fun `collects the facade and every declared classifier for a file`() {
		val names =
			kotlinBinaryNames(
				packageFqName = "com.example",
				fileNameWithoutExtension = "Greeter",
				symbolKeys =
					listOf(
						"com/example/Greeter",
						"com/example/Greeter\$Inner",
						"com/example/Greeter\$Companion",
						"com.example.greet(kotlin.String)",
						"com/example/Greeter#name",
					),
			)

		assertThat(names)
			.containsExactly(
				"com.example.GreeterKt",
				"com.example.Greeter",
				"com.example.Greeter\$Inner",
				"com.example.Greeter\$Companion",
			).inOrder()
	}

	@Test
	fun `does not repeat a name that is both facade and declared class`() {
		val names =
			kotlinBinaryNames(
				packageFqName = "com.example",
				fileNameWithoutExtension = "Greeter",
				symbolKeys = listOf("com/example/GreeterKt"),
			)

		assertThat(names).containsExactly("com.example.GreeterKt")
	}
}
