package com.itsaky.androidide.plugins.extensions

import com.itsaky.androidide.plugins.IPlugin
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * Optional extension for plugins that contribute additional sections to project search.
 *
 * The IDE runs the built-in text search first, then asks enabled [ProjectSearchExtension]
 * plugins for extra sections to append in the existing Search Results tab.
 */
interface ProjectSearchExtension : IPlugin {
	/**
	 * Searches the project for [ProjectSearchRequest.query] and returns the sections to
	 * append to the Search Results tab.
	 *
	 * Called on the UI thread - implementations must not block. Do the work asynchronously
	 * and complete the returned future from any thread. The IDE waits a bounded time for
	 * the future; sections from futures that complete after the timeout are dropped.
	 *
	 * Complete with an empty list (never null) when there are no results. A future that
	 * completes exceptionally, or a method that throws, is logged and recorded as a plugin
	 * crash, and contributes no sections.
	 */
	fun searchProject(request: ProjectSearchRequest): CompletableFuture<List<ProjectSearchSection>>
}

/**
 * Query parameters passed to [ProjectSearchExtension.searchProject].
 *
 * @property query The literal (non-regex), non-empty search text entered by the user.
 * @property roots Source directories the user selected for this search; implementations
 *   must not report matches outside them.
 * @property extensions File-name suffixes to restrict the search to (e.g. "java", ".kt");
 *   empty means all files match. The IDE splits the search dialog's filter field on "|" and
 *   trims each token, dropping blanks, so "java | kt" arrives as ["java", "kt"]. A match is a
 *   plain suffix test (file name ends with the token).
 */
data class ProjectSearchRequest(
	val query: String,
	val roots: List<File>,
	val extensions: List<String> = emptyList(),
)

/**
 * A titled group of results rendered as its own section in the Search Results list.
 *
 * @property title Section header shown above the results, e.g. the contributing plugin's
 *   display name. Sections with no results are not rendered.
 * @property results Matches in this section; the IDE groups them by [ProjectSearchResult.file].
 */
data class ProjectSearchSection(
	val title: String,
	val results: List<ProjectSearchResult>,
)

/**
 * A single match within a [ProjectSearchSection].
 *
 * All line and column values are 0-based, matching the IDE's editor coordinates: [startLine]
 * and [startColumn] address the first character of the match, [endLine] and [endColumn]
 * address the position just past its last character (exclusive end). Columns are character
 * offsets within the line. The IDE feeds these values directly into editor navigation when
 * the user opens the result, so 1-based values will misplace the cursor by one.
 *
 * @property file The file containing the match.
 * @property linePreview Short single-line snippet shown in the results list.
 * @property matchText The exact text that matched.
 * @property score Optional relevance score, higher is more relevant. The IDE currently groups
 *   a section's results by [file] and does not order them by score.
 */
data class ProjectSearchResult(
	val file: File,
	val linePreview: String,
	val matchText: String,
	val startLine: Int,
	val startColumn: Int,
	val endLine: Int,
	val endColumn: Int,
	val score: Float? = null,
)
