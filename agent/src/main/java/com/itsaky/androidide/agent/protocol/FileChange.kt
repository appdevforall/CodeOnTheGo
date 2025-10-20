package com.itsaky.androidide.agent.protocol

import java.nio.file.Path

/**
 * Represents a single file change event generated during a tool execution turn.
 * Mirrors the core agent protocol's FileChange enum.
 */
sealed class FileChange {
    data class Add(val content: String) : FileChange()

    data class Delete(val content: String) : FileChange()

    data class Update(
        val unifiedDiff: String,
        val movePath: Path? = null
    ) : FileChange()
}
