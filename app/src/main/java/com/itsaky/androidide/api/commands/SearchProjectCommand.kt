package com.itsaky.androidide.api.commands

import com.itsaky.androidide.agent.model.ExplorationKind
import com.itsaky.androidide.agent.model.ExplorationMetadata
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.projects.IProjectManager
import java.io.File
import java.util.Locale

class SearchProjectCommand(
    private val query: String,
    private val path: String?,
    private val maxResults: Int,
    private val ignoreCase: Boolean
) : Command<List<String>> {

    override fun execute(): ToolResult {
        if (query.isBlank()) {
            return ToolResult.failure("Search query cannot be empty.")
        }

        val projectDir = IProjectManager.getInstance().projectDir
        val targetDir = path?.takeIf { it.isNotBlank() }?.let { File(projectDir, it).normalize() }
            ?: projectDir

        if (!targetDir.exists()) {
            return ToolResult.failure("Search directory not found: ${path ?: projectDir.name}")
        }
        if (!targetDir.isDirectory) {
            return ToolResult.failure("Search path is not a directory: ${path ?: targetDir.path}")
        }

        val matches = mutableListOf<String>()
        val filesWithMatches = linkedSetOf<String>()
        var totalMatches = 0

        val normalizedProjectDir = projectDir.normalize()

        for (file in targetDir.walkTopDown()) {
            if (file.isDirectory) {
                continue
            }
            if (file.length() > MAX_FILE_BYTES || !file.canRead()) {
                continue
            }

            val relativePath = try {
                file.normalize().relativeTo(normalizedProjectDir)
            } catch (_: IllegalArgumentException) {
                continue
            }.path.replace(File.separatorChar, '/')

            var lineNumber = 0
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    lineNumber++
                    val occurrences = countOccurrences(line, query, ignoreCase)
                    if (occurrences > 0) {
                        filesWithMatches += relativePath
                        val preview = line.trim()
                        totalMatches += occurrences
                        matches.add("$relativePath:$lineNumber: $preview")
                        if (matches.size >= maxResults) {
                            return@forEach
                        }
                    }
                }
            }

            if (matches.size >= maxResults) {
                break
            }
        }

        val summaryMessage = if (totalMatches == 0) {
            "No matches found for '$query'."
        } else {
            "Found $totalMatches matches for '$query' in ${filesWithMatches.size} file(s)."
        }

        val metadata = ExplorationMetadata(
            kind = ExplorationKind.SEARCH,
            items = filesWithMatches.toList(),
            query = query,
            matchCount = totalMatches
        )

        return ToolResult.success(
            message = summaryMessage,
            data = matches.joinToString("\n"),
            exploration = metadata
        )
    }

    private fun countOccurrences(
        line: String,
        query: String,
        ignoreCase: Boolean
    ): Int {
        if (line.isEmpty()) return 0
        var count = 0
        var startIndex = 0
        val haystack = if (ignoreCase) line.lowercase(Locale.getDefault()) else line
        val needle = if (ignoreCase) query.lowercase(Locale.getDefault()) else query
        while (true) {
            val index = haystack.indexOf(needle, startIndex)
            if (index == -1) break
            count += 1
            startIndex = index + needle.length
        }
        return count
    }

    companion object {
        private const val MAX_FILE_BYTES = 512 * 1024 // 512 KB
    }
}
