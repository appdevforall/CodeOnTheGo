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
	fun searchProject(request: ProjectSearchRequest): CompletableFuture<List<ProjectSearchSection>>
}

data class ProjectSearchRequest(
	val query: String,
	val roots: List<File>,
	val extensions: List<String> = emptyList(),
)

data class ProjectSearchSection(
	val title: String,
	val results: List<ProjectSearchResult>,
)

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
