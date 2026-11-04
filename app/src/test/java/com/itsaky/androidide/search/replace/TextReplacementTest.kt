package com.itsaky.androidide.search.replace

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.models.SearchResult
import com.itsaky.androidide.utils.ProjectSearchOptions
import com.itsaky.androidide.utils.RecursiveFileSearcher
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TextReplacementTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private fun matches(
		text: String,
		query: String,
	): List<SearchResult> {
		val file = File(tmp.root, "A.java").apply { writeText(text) }
		return RecursiveFileSearcher
			.search(query, emptyList(), listOf(tmp.root), ProjectSearchOptions(matchCase = true))[file]
			.orEmpty()
	}

	private fun applied(result: TextReplacement.Result): String = (result as TextReplacement.Result.Applied).newText

	@Test
	fun replacesEveryMatchOnOneLineAndAcrossLines() {
		val text = "a /server33/ b /server33/\nc /server33/"
		val out = TextReplacement.apply(text, matches(text, "/server33/"), "/server34/")
		assertThat(applied(out)).isEqualTo("a /server34/ b /server34/\nc /server34/")
	}

	@Test
	fun replacesOnlyGivenMatches() {
		val text = "x x x"
		val all = matches(text, "x")
		assertThat(applied(TextReplacement.apply(text, listOf(all[1]), "y"))).isEqualTo("x y x")
	}

	@Test
	fun emptyReplacementDeletes() {
		val text = "keep DROP keep"
		assertThat(applied(TextReplacement.apply(text, matches(text, "DROP "), ""))).isEqualTo("keep keep")
	}

	@Test
	fun preservesCrlfAndSurroundingText() {
		val text = "a\r\nserver3\r\nb"
		assertThat(applied(TextReplacement.apply(text, matches(text, "server3"), "server4"))).isEqualTo("a\r\nserver4\r\nb")
	}

	@Test
	fun replacementContainingQueryDoesNotCascade() {
		val text = "server3 server3"
		assertThat(applied(TextReplacement.apply(text, matches(text, "server3"), "server33"))).isEqualTo("server33 server33")
	}

	@Test
	fun multiLineMatchIsReplaced() {
		val text = "one\ntwo three"
		assertThat(applied(TextReplacement.apply(text, matches(text, "one\ntwo"), "x"))).isEqualTo("x three")
	}

	@Test
	fun oneStaleMatchMakesTheWholeFileStale() {
		val found = matches("aa bb aa", "aa")
		assertThat(TextReplacement.apply("aa bb zz", found, "cc")).isEqualTo(TextReplacement.Result.Stale)
	}

	@Test
	fun rangeBeyondTextIsStale() {
		val found = matches("line0\nline1 target", "target")
		assertThat(TextReplacement.apply("line0", found, "x")).isEqualTo(TextReplacement.Result.Stale)
	}
}
