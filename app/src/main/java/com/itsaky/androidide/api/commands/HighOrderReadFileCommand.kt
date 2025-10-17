package com.itsaky.androidide.api.commands

import com.itsaky.androidide.agent.model.ExplorationKind
import com.itsaky.androidide.agent.model.ExplorationMetadata
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.projects.IProjectManager
import java.io.File

class HighOrderReadFileCommand(private val path: String) : Command<String> {
    override fun execute(): ToolResult {
        val readFileCommand = ReadFileCommand(path)
        val result = readFileCommand.execute()
        return if (result.isSuccess) {
            val projectDir = IProjectManager.getInstance().projectDir
            val normalized = File(projectDir, path).normalize()
            val relative = try {
                normalized.relativeTo(projectDir)
            } catch (_: IllegalArgumentException) {
                normalized
            }
            val displayPath = if (relative.isAbsolute) {
                path
            } else {
                relative.path.replace(File.separatorChar, '/')
            }
            ToolResult(
                message = "File read successfully.",
                success = true,
                data = result.getOrNull(),
                error_details = null,
                exploration = ExplorationMetadata(
                    kind = ExplorationKind.READ,
                    items = listOf(displayPath)
                )
            )
        } else {
            ToolResult(
                message = "",
                success = false,
                data = null,
                error_details = "Failed to read the file."
            )
        }

    }
}
