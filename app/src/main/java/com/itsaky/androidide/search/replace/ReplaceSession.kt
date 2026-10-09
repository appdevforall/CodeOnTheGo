package com.itsaky.androidide.search.replace

import com.itsaky.androidide.models.SearchResult
import com.itsaky.androidide.utils.ProjectSearchOptions
import java.io.File

data class MatchKey(
	val file: File,
	val line: Int,
	val column: Int,
)

fun SearchResult.key(): MatchKey = MatchKey(file, start.line, start.column)

enum class FileCheckState { ALL, SOME, NONE }

interface ReplaceHost {
	fun onReplaceRequested(session: ReplaceSession): Boolean
}

data class ReplaceSession(
	val query: String,
	val replacement: String,
	val options: ProjectSearchOptions,
	val results: Map<File, List<SearchResult>>,
	val excluded: Set<MatchKey> = emptySet(),
) {
	val includedCount: Int
		get() = results.values.sumOf { matches -> matches.count(::isIncluded) }

	val includedFileCount: Int
		get() = results.values.count { matches -> matches.any(::isIncluded) }

	fun isIncluded(match: SearchResult): Boolean = match.key() !in excluded

	fun fileState(file: File): FileCheckState {
		val matches = results[file].orEmpty()
		return when (matches.count(::isIncluded)) {
			matches.size -> FileCheckState.ALL
			0 -> FileCheckState.NONE
			else -> FileCheckState.SOME
		}
	}

	fun toggleMatch(match: SearchResult): ReplaceSession {
		val key = match.key()
		return copy(excluded = if (key in excluded) excluded - key else excluded + key)
	}

	fun toggleFile(file: File): ReplaceSession {
		val keys = results[file].orEmpty().map { it.key() }.toSet()
		return copy(excluded = if (fileState(file) == FileCheckState.ALL) excluded + keys else excluded - keys)
	}

	fun includedEdits(): List<FileEdit> =
		results.mapNotNull { (file, matches) ->
			matches.filter(::isIncluded).takeIf { it.isNotEmpty() }?.let { FileEdit(file, it) }
		}
}
