package com.itsaky.androidide.api.commands

import android.util.Log
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
            val baseDir = IProjectManager.getInstance().projectDir.normalize()

            val sanitizedPath = path.trim()
            val targetDir = when {
                sanitizedPath.isEmpty() || sanitizedPath == "." || sanitizedPath == "./" -> baseDir
                else -> {
                    val candidate = File(sanitizedPath)
                    if (candidate.isAbsolute) candidate else File(baseDir, sanitizedPath)
                }
            }
            val normalizedTarget = targetDir.normalize()
            val resolvedPath = normalizedTarget.absolutePath
            val basePath = baseDir.absolutePath
            val insideProject = resolvedPath == basePath ||
                    resolvedPath.startsWith(basePath + File.separator)

            Log.d(
                TAG,
                "list_dir requested path='$path' (sanitized='$sanitizedPath'), recursive=$recursive, baseDir='${baseDir.absolutePath}', resolved='$resolvedPath'"
            )

            when {
                !insideProject -> {
                    Log.w(
                        TAG,
                        "Resolved path is outside the active project. baseDir='$basePath', resolved='$resolvedPath'"
                    )
                    ToolResult.failure(
                        message = "Path is outside the current project: $path",
                        error_details = "Resolved path: $resolvedPath"
                    )
                }

                !normalizedTarget.exists() -> {
                    Log.w(
                        TAG,
                        "Requested directory does not exist. baseDir='${baseDir.absolutePath}', sanitizedPath='$sanitizedPath', resolved='$resolvedPath'"
                    )
                    ToolResult.failure(
                        message = "Directory not found at path: $path",
                        error_details = "Resolved path: $resolvedPath"
                    )
                }

                !normalizedTarget.isDirectory -> {
                    Log.w(
                        TAG,
                        "Requested path is not a directory. baseDir='${baseDir.absolutePath}', sanitizedPath='$sanitizedPath', resolved='$resolvedPath'"
                    )
                    ToolResult.failure(
                        message = "Path is not a directory: $path",
                        error_details = "Resolved path: $resolvedPath"
                    )
                }

                else -> {
                    val files = if (recursive) {
                        normalizedTarget
                            .walkTopDown()
                            .mapNotNull { file ->
                                try {
                                    file.normalize().relativeTo(baseDir).path
                                } catch (_: IllegalArgumentException) {
                                    null
                                }
                            }
                            .toList()
                    } else {
                        normalizedTarget
                            .listFiles()
                            ?.mapNotNull { file ->
                                try {
                                    file.normalize().relativeTo(baseDir).path
                                } catch (_: IllegalArgumentException) {
                                    null
                                }
                            } ?: emptyList()
                    }
                    val preview = files.take(PREVIEW_LIMIT)
                    Log.d(
                        TAG,
                        "Listed ${files.size} entr${if (files.size == 1) "y" else "ies"} under '$resolvedPath'. Preview=$preview"
                    )
                    val relative = try {
                        normalizedTarget.relativeTo(baseDir)
                    } catch (_: IllegalArgumentException) {
                        normalizedTarget
                    }
                    val displayPath = when (relative.path.replace(File.separatorChar, '/')) {
                        "" -> "."
                        else -> relative.path.replace(File.separatorChar, '/')
                    }
                    ToolResult.success(
                        message = "Directory contents listed successfully.",
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
            Log.e(
                TAG,
                "Failed to list files for path='$path', recursive=$recursive",
                e
            )
            ToolResult.failure(
                message = "Failed to list files.",
                error_details = e.message
            )
        }
    }

    companion object {
        private const val TAG = "ListFilesCommand"
        private const val PREVIEW_LIMIT = 5
    }
}
