package com.itsaky.androidide.api.commands

import com.itsaky.androidide.agent.model.ExplorationKind
import com.itsaky.androidide.agent.model.ExplorationMetadata
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.projects.IProjectManager
import java.io.File

class ListFilesCommand(
    private val path: String,
    private val recursive: Boolean
) : Command<List<String>> {
    override fun execute(): ToolResult {
        return try {
            val baseDir = IProjectManager.getInstance().projectDir

            val targetDir = when (val sanitizedPath = path.trim()) {
                "", ".", "./" -> baseDir
                else -> File(baseDir, sanitizedPath)
            }

            when {
                !targetDir.exists() -> ToolResult.failure(
                    message = "Directory not found at path: $path"
                )

                !targetDir.isDirectory -> ToolResult.failure(
                    message = "Path is not a directory: $path"
                )

                else -> {
                    val files = if (recursive) {
                        targetDir.walkTopDown().map { it.relativeTo(baseDir).path }.toList()
                    } else {
                        targetDir.listFiles()?.map { it.relativeTo(baseDir).path } ?: emptyList()
                    }
                    val normalized = targetDir.normalize()
                    val relative = try {
                        normalized.relativeTo(baseDir)
                    } catch (_: IllegalArgumentException) {
                        normalized
                    }
                    val displayPath = when (relative.path.replace(File.separatorChar, '/')) {
                        "" -> "."
                        else -> relative.path.replace(File.separatorChar, '/')
                    }
                    ToolResult.success(
                        message = "Files listed successfully.",
                        data = files.joinToString("\n"),
                        exploration = ExplorationMetadata(
                            kind = ExplorationKind.LIST,
                            path = displayPath,
                            items = files.take(10),
                            entryCount = files.size
                        )
                    )
                }
            }
        } catch (e: Exception) {
            ToolResult.failure(
                message = "Failed to list files.",
                error_details = e.message
            )
        }
    }
}
