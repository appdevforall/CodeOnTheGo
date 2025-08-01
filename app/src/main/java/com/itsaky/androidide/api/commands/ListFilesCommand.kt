package com.itsaky.androidide.api.commands

import com.itsaky.androidide.data.model.ToolResult
import com.itsaky.androidide.projects.IProjectManager
import java.io.File

class ListFilesCommand(
    private val path: String,
    private val recursive: Boolean
) : Command<List<String>> {
    override fun execute(): ToolResult {
        return try {
            val baseDir = IProjectManager.getInstance().projectDir

            // ✨ Sanitize the path to handle different root directory representations
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
                    ToolResult.success(
                        message = "Files listed successfully.",
                        data = files.joinToString("\n")
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