package org.appdevforall.codeonthego.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WordBoundaryTest {
	private fun whole(
		text: String,
		query: String,
	): Boolean {
		val start = text.indexOf(query)
		return WordBoundary.isWholeWord(text, start, start + query.length)
	}

	@Test
	fun identifierInsideLongerIdentifierIsNotWholeWord() {
		assertThat(whole("val server33 = 1", "server3")).isFalse()
	}

	@Test
	fun punctuationEdgedQueryMatchesInsidePath() {
		assertThat(whole("\"http://h/server33/api\"", "/server33/")).isTrue()
	}

	@Test
	fun identifierFollowedByDotIsWholeWord() {
		assertThat(whole("foo.bar()", "foo")).isTrue()
	}

	@Test
	fun identifierPrefixIsNotWholeWord() {
		assertThat(whole("foobar()", "foo")).isFalse()
	}

	@Test
	fun matchAtTextBoundsIsWholeWord() {
		assertThat(whole("foo", "foo")).isTrue()
	}
}
