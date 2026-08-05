package com.itsaky.androidide.lsp

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.models.Location
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import io.github.rosemoe.sora.text.Content
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The panel used to read each result file in full, once per hit, on the main thread. These pin the
 * replacement: one pass per file, only the lines a hit needs, and stale hits dropped rather than
 * throwing.
 */
class SearchResultGroupingTest {
	@get:Rule
	val folder = TemporaryFolder()

	private fun location(
		file: File,
		startLine: Int,
		startColumn: Int,
		endLine: Int = startLine,
		endColumn: Int = startColumn,
	) = Location(
		file.toPath(),
		Range(Position(startLine, startColumn, 0), Position(endLine, endColumn, 0)),
	)

	@Test
	fun `a single-line hit carries its line and the matched text`() {
		val file = File("Example.kt")
		val lines = mapOf(1 to "fun caller() { target() }")

		val results = SearchResultGrouping.resultsFor(file, listOf(location(file, 1, 15, 1, 21)), lines)

		assertThat(results).hasSize(1)
		assertThat(results[0].line).isEqualTo("fun caller() { target() }")
		assertThat(results[0].match).isEqualTo("target")
		assertThat(results[0].file).isEqualTo(file)
	}

	@Test
	fun `a multi-line hit joins the lines it spans`() {
		val file = File("Multi.kt")
		val lines = mapOf(0 to "first line", 1 to "middle", 2 to "last line")

		val results = SearchResultGrouping.resultsFor(file, listOf(location(file, 0, 6, 2, 4)), lines)

		assertThat(results).hasSize(1)
		assertThat(results[0].match).isEqualTo("line\nmiddle\nlast")
		// The row's line text is the line the hit starts on.
		assertThat(results[0].line).isEqualTo("first line")
	}

	@Test
	fun `a hit on a line that no longer exists is dropped`() {
		val file = File("Stale.kt")

		val results = SearchResultGrouping.resultsFor(file, listOf(location(file, 9, 0, 9, 3)), mapOf(0 to "only line"))

		assertThat(results).isEmpty()
	}

	@Test
	fun `a column past the end of its line is clamped rather than throwing`() {
		val file = File("Clamped.kt")

		val results = SearchResultGrouping.resultsFor(file, listOf(location(file, 0, 2, 0, 99)), mapOf(0 to "short"))

		assertThat(results).hasSize(1)
		assertThat(results[0].match).isEqualTo("ort")
	}

	@Test
	fun `an open file's rows come from its buffer, not its saved bytes`() {
		val file = folder.newFile("Buffered.kt")
		file.writeText("saved text\n")

		val results =
			SearchResultGrouping.resultsFor(file, listOf(location(file, 0, 4, 0, 10)), Content("fun target() {}"))

		assertThat(results).hasSize(1)
		assertThat(results[0].line).isEqualTo("fun target() {}")
		assertThat(results[0].match).isEqualTo("target")
	}

	@Test
	fun `a hit past the end of the buffer is dropped`() {
		// The Content overload filters out-of-range lines itself, before the shared row builder sees them.
		val file = File("StaleBuffer.kt")

		val results = SearchResultGrouping.resultsFor(file, listOf(location(file, 1, 0, 1, 3)), Content("only"))

		assertThat(results).isEmpty()
	}

	@Test
	fun `only the lines a hit needs are collected`() {
		val file = folder.newFile("Wanted.kt")
		file.writeText("zero\none\ntwo\nthree\nfour\n")

		assertThat(SearchResultGrouping.readLines(file, setOf(1, 3)))
			.isEqualTo(mapOf(1 to "one", 3 to "three"))
	}

	@Test
	fun `lines past the end of the file are absent rather than failing`() {
		val file = folder.newFile("Short.kt")
		file.writeText("only\n")

		assertThat(SearchResultGrouping.readLines(file, setOf(0, 7))).isEqualTo(mapOf(0 to "only"))
	}

	@Test
	fun `an unreadable file yields no lines rather than throwing`() {
		val missing = File(folder.root, "Absent.kt")

		assertThat(SearchResultGrouping.readLines(missing, setOf(0))).isEmpty()
	}

	@Test
	fun `every hit in a file is built from one read`() {
		val file = folder.newFile("Several.kt")
		file.writeText("fun a() { target() }\nfun b() { target() }\n")

		val results =
			SearchResultGrouping.readFromDisk(
				mapOf(file to listOf(location(file, 0, 10, 0, 16), location(file, 1, 10, 1, 16))),
			)

		assertThat(results.keys).containsExactly(file)
		assertThat(results.getValue(file).map { it.match }).containsExactly("target", "target")
	}

	@Test
	fun `a file whose every hit is stale is omitted entirely`() {
		val file = folder.newFile("AllStale.kt")
		file.writeText("one line\n")

		assertThat(SearchResultGrouping.readFromDisk(mapOf(file to listOf(location(file, 40, 0, 40, 2))))).isEmpty()
	}

	@Test
	fun `linesNeededBy covers every line a hit spans`() {
		val file = File("Spans.kt")

		assertThat(SearchResultGrouping.linesNeededBy(listOf(location(file, 2, 0, 4, 1), location(file, 9, 0))))
			.containsExactly(2, 3, 4, 9)
	}
}
