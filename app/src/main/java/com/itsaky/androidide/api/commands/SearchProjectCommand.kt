package com.itsaky.androidide.api.commands

import android.util.Log
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

        return try {
            val projectDir = IProjectManager.getInstance().projectDir.normalize()
            val basePath = projectDir.absolutePath
            val sanitizedPath = path?.trim().takeUnless { it.isNullOrEmpty() }
            val targetDir = sanitizedPath?.let { raw ->
                val candidate = File(raw)
                if (candidate.isAbsolute) candidate else File(projectDir, raw)
            } ?: projectDir
            val normalizedTarget = targetDir.normalize()
            val resolvedPath = normalizedTarget.absolutePath
            val insideProject = resolvedPath == basePath ||
                    resolvedPath.startsWith(basePath + File.separator)

            Log.d(
                TAG,
                "search_project query='$query', path='${path ?: ""}', sanitized='${sanitizedPath ?: ""}', resolved='$resolvedPath', base='$basePath', maxResults=$maxResults, ignoreCase=$ignoreCase"
            )

            if (!insideProject) {
                Log.w(
                    TAG,
                    "Resolved search path is outside the active project. base='$basePath', resolved='$resolvedPath'"
                )
                return ToolResult.failure(
                    message = "Search path is outside the current project: ${path ?: ""}",
                    error_details = "Resolved path: $resolvedPath"
                )
            }

            if (!normalizedTarget.exists()) {
                Log.w(
                    TAG,
                    "Search directory not found. requested='${sanitizedPath ?: "."}', resolved='$resolvedPath'"
                )
                return ToolResult.failure(
                    message = "Search directory not found: ${sanitizedPath ?: "."}",
                    error_details = "Resolved path: $resolvedPath"
                )
            }
            if (!normalizedTarget.isDirectory) {
                Log.w(
                    TAG,
                    "Search target is not a directory. requested='${sanitizedPath ?: "."}', resolved='$resolvedPath'"
                )
                return ToolResult.failure(
                    message = "Search path is not a directory: ${sanitizedPath ?: resolvedPath}",
                    error_details = "Resolved path: $resolvedPath"
                )
            }

            val matches = mutableListOf<String>()
            val filesWithMatches = linkedSetOf<String>()
            var totalMatches = 0

            Log.d(
                TAG,
                "Beginning search for '$query' at '$resolvedPath'"
            )

            val normalizedProjectDir = projectDir.normalize()
            for (file in normalizedTarget.walkTopDown()) {
                if (file.isDirectory) continue
                if (file.length() > MAX_FILE_BYTES || !file.canRead()) continue

                val relativePath = try {
                    file.normalize().relativeTo(normalizedProjectDir)
                } catch (_: IllegalArgumentException) {
                    Log.v(
                        TAG,
                        "Skipping file outside project scope: ${file.absolutePath}"
                    )
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

            Log.d(
                TAG,
                "Completed search for '$query'. totalMatches=$totalMatches filesWithMatches=${filesWithMatches.size}"
            )

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

            ToolResult.success(
                message = summaryMessage,
                data = matches.joinToString("\n"),
                exploration = metadata
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to execute search_project for query='$query'",
                e
            )
            ToolResult.failure(
                message = "Failed to search project.",
                error_details = e.message
            )
        }
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
        private const val TAG = "SearchProjectCommand"
        private const val MAX_FILE_BYTES = 512 * 1024 // 512 KB
    }
}
