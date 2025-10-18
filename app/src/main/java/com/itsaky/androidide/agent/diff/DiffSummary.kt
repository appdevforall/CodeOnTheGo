package com.itsaky.androidide.agent.diff

import com.itsaky.androidide.agent.protocol.FileChange
import java.nio.file.Path

data class DiffStats(
    val fileCount: Int,
    val addedLines: Int,
    val removedLines: Int
)

fun calculateDiffStats(changes: Map<Path, FileChange>): DiffStats {
    var added = 0
    var removed = 0
    changes.values.forEach { change ->
        when (change) {
            is FileChange.Add -> {
                added += countFileLines(change.content)
            }

            is FileChange.Delete -> {
                removed += countFileLines(change.content)
            }

            is FileChange.Update -> {
                change.unifiedDiff.lineSequence().forEach { line ->
                    when {
                        line.startsWith("+++") || line.startsWith("---") -> Unit
                        line.startsWith("+") -> added++
                        line.startsWith("-") -> removed++
                    }
                }
            }
        }
    }
    return DiffStats(
        fileCount = changes.size,
        addedLines = added,
        removedLines = removed
    )
}

fun formatDiffSummary(changes: Map<Path, FileChange>): String {
    if (changes.isEmpty()) return "No file changes."
    val stats = calculateDiffStats(changes)
    val fileLabel = if (stats.fileCount == 1) "file" else "files"
    return "Edited ${stats.fileCount} $fileLabel (+${stats.addedLines} -${stats.removedLines})"
}

private fun countFileLines(content: String): Int {
    if (content.isEmpty()) return 0
    val parts = content.split('\n', ignoreCase = false, limit = -1)
    if (parts.isEmpty()) return 0
    val trailingBlank = parts.last().isEmpty()
    val count = parts.size
    return if (trailingBlank) count - 1 else count
}
