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
import org.junit.Test

class KotlinLocalsTest {
	@Test
	fun `inline markers are synthetic`() {
		assertThat(isSyntheticKotlinLocal("\$i\$f\$Column")).isTrue()
		assertThat(isSyntheticKotlinLocal("\$i\$a\$-map-Foo")).isTrue()
		assertThat(isSyntheticKotlinLocal("\$i\$f\$all")).isTrue()
	}

	@Test
	fun `an inline marker copied out of a nested inlining is still synthetic`() {
		assertThat(isSyntheticKotlinLocal("\$i\$f\$measured\$iv")).isTrue()
		assertThat(isSyntheticKotlinLocal("\$i\$f\$mapTo\$iv\$iv")).isTrue()
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
	fun `a local copied out of an inlined body is shown, suffix and all`() {
		// mapTo's loop variable and accumulator reach the list under these names; the suffix is the
		// only cue that they are not the user's, so it is neither hidden nor stripped.
		assertThat(isSyntheticKotlinLocal("item\$iv\$iv")).isFalse()
		assertThat(isSyntheticKotlinLocal("destination\$iv\$iv")).isFalse()
		assertThat(isSyntheticKotlinLocal("started\$iv")).isFalse()
	}

	@Test
	fun `inline receivers are synthetic`() {
		// map's and mapTo's extension receivers, and the receiver of a lambda the user wrote. No
		// label a user could have typed is recoverable from any of them, so all three are hidden.
		assertThat(isSyntheticKotlinLocal("\$this\$map\$iv")).isTrue()
		assertThat(isSyntheticKotlinLocal("\$this\$mapTo\$iv\$iv")).isTrue()
		assertThat(isSyntheticKotlinLocal("\$this\$direct_u24lambda_u240")).isTrue()
	}

	@Test
	fun `an inlined lambda argument is hidden, not treated as library code`() {
		// $i$a$ scopes a lambda the user wrote, inlined into the user's own class, so it is noise in
		// the variables list but says nothing about whose code is executing.
		assertThat(isSyntheticKotlinLocal("\$i\$a\$-forEach-MainActivity")).isTrue()
	}
}
