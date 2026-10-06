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
package org.appdevforall.codeonthego.lsp.java.debug

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.lsp.java.debug.utils.isSyntheticKotlinLocal
import org.appdevforall.codeonthego.lsp.java.debug.utils.kotlinDisplayName
import org.appdevforall.codeonthego.lsp.java.debug.utils.kotlinLambdaScopes
import org.junit.Test

class KotlinLocalsTest {
	private val greetAllLocals =
		listOf(
			"\$i\$a\$-map-Greeter\$greetAll\$1\\3\\62\\0",
			"greeting\\3",
			"each\\3",
			"item\\2",
			"\$i\$f\$mapTo\\2\\60",
			"\$this\$mapTo\\2",
			"destination\\2",
			"\$i\$f\$map\\1\\27",
			"\$this\$map\\1",
			"this",
			"names",
		)

	private val timedLocals =
		listOf(
			"\$i\$a\$-measured-Greeter\$timed\$1\\2\\65\\0",
			"doubled\\2",
			"\$i\$f\$measured\\1\\33",
			"started\\1",
			"result\\1",
			"label\\1",
			"this",
		)

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
	fun `declaration-site receivers are synthetic`() {
		// A member inline function leaves its receiver behind as the bare `this_`, which shares no
		// prefix with the `$this` family, plus one `$iv` per inlining.
		assertThat(isSyntheticKotlinLocal("this_")).isTrue()
		assertThat(isSyntheticKotlinLocal("this_\$iv")).isTrue()
		assertThat(isSyntheticKotlinLocal("this_\$iv\$iv")).isTrue()
		assertThat(isSyntheticKotlinLocal("\$this_foo")).isTrue()
		assertThat(isSyntheticKotlinLocal("\$this")).isTrue()
	}

	@Test
	fun `a user local that merely starts with this_ is kept`() {
		assertThat(isSyntheticKotlinLocal("this_count")).isFalse()
		assertThat(isSyntheticKotlinLocal("this_thing")).isFalse()
	}

	@Test
	fun `scope-numbered inline markers are synthetic`() {
		assertThat(isSyntheticKotlinLocal("\$i\$a\$-map-Greeter\$greetAll\$1\\3\\62\\0")).isTrue()
		assertThat(isSyntheticKotlinLocal("\$i\$f\$mapTo\\2\\60")).isTrue()
		assertThat(isSyntheticKotlinLocal("\$this\$mapTo\\2")).isTrue()
	}

	@Test
	fun `lambda scopes come only from inline lambda markers`() {
		assertThat(kotlinLambdaScopes(greetAllLocals)).containsExactly(3)
		assertThat(kotlinLambdaScopes(timedLocals)).containsExactly(2)
		assertThat(kotlinLambdaScopes(listOf("savedInstanceState", "this"))).isEmpty()
	}

	@Test
	fun `a local in the user's inlined lambda shows its source name`() {
		val scopes = kotlinLambdaScopes(greetAllLocals)
		assertThat(kotlinDisplayName("each\\3", scopes)).isEqualTo("each")
		assertThat(kotlinDisplayName("greeting\\3", scopes)).isEqualTo("greeting")
		assertThat(kotlinDisplayName("doubled\\2", kotlinLambdaScopes(timedLocals))).isEqualTo("doubled")
	}

	@Test
	fun `a local of an inlined function body keeps its scope number`() {
		val scopes = kotlinLambdaScopes(greetAllLocals)
		assertThat(kotlinDisplayName("item\\2", scopes)).isEqualTo("item\\2")
		assertThat(kotlinDisplayName("destination\\2", scopes)).isEqualTo("destination\\2")
		assertThat(kotlinDisplayName("started\\1", kotlinLambdaScopes(timedLocals))).isEqualTo("started\\1")
	}

	@Test
	fun `names without a single scope number are unchanged`() {
		val scopes = setOf(1, 2, 3)
		assertThat(kotlinDisplayName("names", scopes)).isEqualTo("names")
		assertThat(kotlinDisplayName("this", scopes)).isEqualTo("this")
		assertThat(kotlinDisplayName("item\$iv\$iv", scopes)).isEqualTo("item\$iv\$iv")
		assertThat(kotlinDisplayName("x\\3\\1", scopes)).isEqualTo("x\\3\\1")
		assertThat(kotlinDisplayName("\\3", scopes)).isEqualTo("\\3")
	}

	@Test
	fun `an inlined lambda argument is hidden, not treated as library code`() {
		// $i$a$ scopes a lambda the user wrote, inlined into the user's own class, so it is noise in
		// the variables list but says nothing about whose code is executing.
		assertThat(isSyntheticKotlinLocal("\$i\$a\$-forEach-MainActivity")).isTrue()
	}
}
