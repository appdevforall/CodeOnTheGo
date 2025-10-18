package com.itsaky.androidide.agent.diff

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.itsaky.androidide.agent.protocol.FileChange
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.pathString

/**
 * Tracks file states over the course of a single turn of tool executions so that
 * we can surface unified diffs in the chat UI.
 */
class AgentDiffTracker(
    val projectRoot: Path
) {

    private val baselineSnapshots = mutableMapOf<Path, String?>()

    fun snapshotFile(rawPath: String) {
        val path = try {
            Paths.get(rawPath)
        } catch (_: InvalidPathException) {
            return
        }
        snapshotFile(path)
    }

    fun snapshotFile(rawPath: Path) {
        val key = rawPath.normalize()
        if (baselineSnapshots.containsKey(key)) return
        baselineSnapshots[key] = readFileContent(key)
    }

    fun generateChanges(): Map<Path, FileChange> {
        val changes = mutableMapOf<Path, FileChange>()
        for ((path, beforeContent) in baselineSnapshots) {
            val afterContent = readFileContent(path)
            when {
                beforeContent == null && afterContent != null -> {
                    changes[path] = FileChange.Add(afterContent)
                }

                beforeContent != null && afterContent == null -> {
                    changes[path] = FileChange.Delete(beforeContent)
                }

                beforeContent != null && afterContent != null && beforeContent != afterContent -> {
                    val diff = computeUnifiedDiff(beforeContent, afterContent, path)
                    changes[path] = FileChange.Update(diff)
                }
            }
        }
        return changes
    }

    fun clear() {
        baselineSnapshots.clear()
    }

    private fun readFileContent(key: Path): String? {
        val absolute = resolveToProject(key)
        return try {
            val file = absolute.toFile()
            if (!file.exists() || !file.isFile) {
                null
            } else {
                file.readText()
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun resolveToProject(relative: Path): Path {
        return if (relative.isAbsolute) {
            relative
        } else {
            projectRoot.resolve(relative).normalize()
        }
    }

    private fun computeUnifiedDiff(original: String, revised: String, path: Path): String {
        val originalLines = original.toDiffLines()
        val revisedLines = revised.toDiffLines()
        val patch = DiffUtils.diff(originalLines, revisedLines)
        val displayPath = path.pathString
        val diffLines = UnifiedDiffUtils.generateUnifiedDiff(
            "a/$displayPath",
            "b/$displayPath",
            originalLines,
            patch,
            3
        )
        return diffLines.joinToString("\n")
    }

    private fun String.toDiffLines(): List<String> {
        if (isEmpty()) return emptyList()
        // Use limit = 0 for unlimited splits (Kotlin requires non-negative limit)
        val parts = split('\n', ignoreCase = false, limit = 0)
        if (parts.isEmpty()) return emptyList()
        return if (parts.last().isEmpty()) {
            parts.dropLast(1)
        } else {
            parts
        }
    }
}
