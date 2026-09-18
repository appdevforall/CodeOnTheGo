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
	fun `a local copied out of an inlined body shows the name its source gave it`() {
		assertThat(kotlinLocalDisplayName("started\$iv")).isEqualTo("started")
		assertThat(kotlinLocalDisplayName("label\$iv")).isEqualTo("label")
		assertThat(kotlinLocalDisplayName("item\$iv\$iv")).isEqualTo("item")
	}

	@Test
	fun `an inline receiver keeps its label through the copy suffixes`() {
		assertThat(isSyntheticKotlinLocal("\$this\$map\$iv")).isFalse()
		assertThat(kotlinLocalDisplayName("\$this\$map\$iv")).isEqualTo("this@map")
		assertThat(kotlinLocalDisplayName("\$this\$mapTo\$iv\$iv")).isEqualTo("this@mapTo")
	}

	@Test
	fun `a synthetic lambda receiver is left alone rather than given a fabricated label`() {
		// Kotlin names a lambda's receiver after the enclosing method's synthetic lambda, where _u24
		// is a mangled '$'. No label the user wrote is recoverable from it.
		assertThat(kotlinLocalDisplayName("\$this\$direct_u24lambda_u240"))
			.isEqualTo("\$this\$direct_u24lambda_u240")
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
