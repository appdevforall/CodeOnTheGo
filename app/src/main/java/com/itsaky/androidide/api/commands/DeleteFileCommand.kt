package com.itsaky.androidide.api.commands

import com.blankj.utilcode.util.FileUtils
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.projects.IProjectManager
import java.io.File

/**
 * A command to delete a file or a directory at a specified path.
 */
class DeleteFileCommand(private val path: String) {
    fun execute(): ToolResult {
        return try {
            val file = File(IProjectManager.getInstance().projectDir, path)

            if (!file.exists()) {
                return ToolResult.failure("File or directory does not exist at path: $path")
            }

            if (FileUtils.delete(file)) {
                ToolResult.success("Successfully deleted: $path")
            } else {
                ToolResult.failure("Failed to delete: $path")
            }
        } catch (e: Exception) {
            ToolResult.failure(
                "An error occurred during deletion: ${e.message}",
                e.stackTraceToString()
            )
        }
    }
}