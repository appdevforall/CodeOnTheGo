package com.itsaky.androidide.lsp

import com.itsaky.androidide.models.Location
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.models.SearchResult
import io.github.rosemoe.sora.text.Content
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File

/**
 * Builds the search-results panel's rows for a set of [Location]s.
 *
 * Exists because the panel used to read every result file **in full, once per hit, on the main
 * thread**: a file with twelve usages was read and materialised twelve times. Find usages made that a
 * real cost rather than a latent one.
 *
 * A row needs only two short strings per hit - the hit's line, and the matched text - so nothing here
 * retains a file's contents. Reads are one sequential pass per file, and peak memory is one line rather
 * than one file. A per-file content cache would fix the repeated reads but hold every result file's text
 * at once, which is the wrong trade on a phone.
 */
internal object SearchResultGrouping {
	private val logger = LoggerFactory.getLogger(SearchResultGrouping::class.java)

	/**
	 * Rows for [locations] in [file], built from already-available [lines] (0-based line number to text).
	 *
	 * A location whose lines are not all present is dropped: a stale location can point past the end of
	 * a file that has since been edited, and a row referring to a line that no longer exists is worse
	 * than no row.
	 */
	fun resultsFor(
		file: File,
		locations: List<Location>,
		lines: Map<Int, String>,
	): List<SearchResult> =
		locations.mapNotNull { location ->
			val range = location.range
			val lineText = lines[range.start.line] ?: return@mapNotNull null
			val match = matchedText(lineText, range, lines)
			if (match == null) {
				logger.debug("Dropping stale search result in {}", file.name)
				return@mapNotNull null
			}
			SearchResult(range, file, lineText, match)
		}

	/** Rows for [locations] in [file], read from the live editor buffer [content]. */
	fun resultsFor(
		file: File,
		locations: List<Location>,
		content: Content,
	): List<SearchResult> {
		val lines =
			linesNeededBy(locations)
				.filter { it >= 0 && it < content.lineCount }
				.associateWith { content.getLineString(it) }

		return resultsFor(file, locations, lines)
	}

	/** Rows for every file in [byFile], reading each file exactly once. */
	fun readFromDisk(byFile: Map<File, List<Location>>): Map<File, List<SearchResult>> =
		byFile
			.mapValues { (file, locations) -> resultsFor(file, locations, readLines(file, linesNeededBy(locations))) }
			.filterValues { it.isNotEmpty() }

	/** Every 0-based line number whose text [locations] need. */
	fun linesNeededBy(locations: List<Location>): Set<Int> =
		locations
			.flatMapTo(mutableSetOf()) { location ->
				location.range.start.line..location.range.end.line
			}

	/**
	 * The text of just the [wanted] lines of [file], in one sequential pass.
	 *
	 * Stops as soon as the last wanted line has been seen, and never holds more than the current line,
	 * so a hit near the top of a large file does not read the rest of it. Missing lines - a file shorter
	 * than the location claims, or an unreadable file - are simply absent from the result.
	 */
	fun readLines(
		file: File,
		wanted: Set<Int>,
	): Map<Int, String> {
		if (wanted.isEmpty()) {
			return emptyMap()
		}

		val last = wanted.max()
		val lines = HashMap<Int, String>(wanted.size)
		return try {
			file.bufferedReader().use { reader ->
				reader.collectLines(wanted, last, lines)
			}
			lines
		} catch (e: Exception) {
			// A result file that has been deleted or is unreadable drops its rows, which is what the
			// previous implementation did too by way of an exists() check per hit.
			logger.debug("Could not read search result file {}", file, e)
			lines
		}
	}

	private fun BufferedReader.collectLines(
		wanted: Set<Int>,
		last: Int,
		into: MutableMap<Int, String>,
	) {
		var number = 0
		while (number <= last) {
			val line = readLine() ?: return
			if (number in wanted) {
				into[number] = line
			}
			number++
		}
	}

	/**
	 * The text [range] covers, given [firstLine] (the text of the line it starts on) and [lines] for the
	 * rest. Null when any line it spans is missing.
	 */
	private fun matchedText(
		firstLine: String,
		range: Range,
		lines: Map<Int, String>,
	): String? {
		val start = range.start
		val end = range.end
		if (start.line == end.line) {
			val from = start.column.coerceIn(0, firstLine.length)
			return firstLine.substring(from, end.column.coerceIn(from, firstLine.length))
		}

		return buildString {
			append(firstLine.substring(start.column.coerceIn(0, firstLine.length)))
			for (line in (start.line + 1) until end.line) {
				append('\n').append(lines[line] ?: return null)
			}
			val lastLine = lines[end.line] ?: return null
			append('\n').append(lastLine.substring(0, end.column.coerceIn(0, lastLine.length)))
		}
	}
}
