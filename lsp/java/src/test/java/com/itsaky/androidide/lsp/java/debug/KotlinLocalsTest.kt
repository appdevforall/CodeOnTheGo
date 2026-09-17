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
import com.itsaky.androidide.lsp.java.debug.utils.isSyntheticKotlinLocal
import com.itsaky.androidide.lsp.java.debug.utils.kotlinLocalDisplayName
import org.junit.Test

class KotlinLocalsTest {
	@Test
	fun `inline markers are synthetic`() {
		assertThat(isSyntheticKotlinLocal("\$i\$f\$Column")).isTrue()
		assertThat(isSyntheticKotlinLocal("\$i\$a\$-map-Foo")).isTrue()
		assertThat(isSyntheticKotlinLocal("\$i\$f\$all")).isTrue()
	}

	@Test
	fun `suspend state machine locals are synthetic`() {
		assertThat(isSyntheticKotlinLocal("\$continuation")).isTrue()
		assertThat(isSyntheticKotlinLocal("\$result")).isTrue()
		assertThat(isSyntheticKotlinLocal("\$completion")).isTrue()
	}

	@Test
	fun `user locals are kept`() {
		assertThat(isSyntheticKotlinLocal("answer")).isFalse()
		assertThat(isSyntheticKotlinLocal("savedInstanceState")).isFalse()
		assertThat(isSyntheticKotlinLocal("this")).isFalse()
		assertThat(isSyntheticKotlinLocal("_binding")).isFalse()
	}

	@Test
	fun `inline lambda receivers are kept and renamed`() {
		assertThat(isSyntheticKotlinLocal("\$this\$run")).isFalse()
		assertThat(kotlinLocalDisplayName("\$this\$run")).isEqualTo("this@run")
		assertThat(kotlinLocalDisplayName("\$this\$apply")).isEqualTo("this@apply")
	}

	@Test
	fun `ordinary names are not renamed`() {
		assertThat(kotlinLocalDisplayName("answer")).isEqualTo("answer")
		assertThat(kotlinLocalDisplayName("this")).isEqualTo("this")
		assertThat(kotlinLocalDisplayName("\$this\$")).isEqualTo("\$this\$")
	}

	@Test
	fun `an inlined lambda argument is hidden, not treated as library code`() {
		// $i$a$ scopes a lambda the user wrote, inlined into the user's own class, so it is noise in
		// the variables list but says nothing about whose code is executing.
		assertThat(isSyntheticKotlinLocal("\$i\$a\$-forEach-MainActivity")).isTrue()
	}
}
