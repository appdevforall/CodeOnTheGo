package com.itsaky.androidide.api.commands

import com.blankj.utilcode.util.FileIOUtils
import com.itsaky.androidide.data.model.ToolResult
import com.itsaky.androidide.events.ListProjectFilesRequestEvent
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.builder.BuildService
import org.greenrobot.eventbus.EventBus
import java.io.File

interface Command<T> {
    fun execute(): ToolResult
}

class HighOrderCreateFileCommand(
    private val baseDir: File,
    private val path: String,
    private val content: String
) : Command<File> {
    constructor(relativePath: String, content: String) : this(
        IProjectManager.getInstance().projectDir,
        relativePath,
        content
    )

    override fun execute(): ToolResult {
        return try {
            val command = CreateFileCommand(
                baseDir = baseDir,
                path = path,
                content = content
            )
            val result = command.execute()

            if (result.isSuccess) {
                EventBus.getDefault().post(ListProjectFilesRequestEvent())
                ToolResult(success = true, message = "File created at path: $path")
            } else {
                ToolResult(
                    success = false, error_details = "Failed to write to file at path: $path",
                    message = "",
                    data = null
                )
            }
        } catch (e: Exception) {
            ToolResult(
                success = false, error_details = e.message,
                message = "",
                data = null
            )
        }
    }

}

class RunBuildCommand(private val module: String?, private val variant: String) : Command<String> {
    override fun execute(): ToolResult {
        val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
        return if (buildService != null) {
            ToolResult(success = true, message = "Build started successfully.")
        } else {
            ToolResult(
                success = false, error_details = "BuildService is not available.",
                message = "",
                data = null
            )
        }
    }
}

class HighOrderReadFileCommand(private val path: String) : Command<String> {
    override fun execute(): ToolResult {
        val readFileCommand = ReadFileCommand(path)
        val result = readFileCommand.execute()
        return if (result.isSuccess) {
            ToolResult(
                message = "File read successfully.",
                success = true,
                data = result.getOrNull(),
                error_details = TODO()
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

class ListFilesCommand(
    private val path: String,
    private val recursive: Boolean
) : Command<List<String>> {
    override fun execute(): ToolResult {
        return try {
            val baseDir = IProjectManager.getInstance().projectDir
            val targetDir = if (path.isEmpty()) baseDir else File(baseDir, path)
            when {
                !targetDir.exists() -> ToolResult(
                    success = false, error_details = "Directory not found at path: $path",
                    message = "",
                    data = null
                )

                !targetDir.isDirectory -> ToolResult(
                    success = false, error_details = "Path is not a directory: $path",
                    message = "",
                    data = null
                )

                else -> {
                    val files = if (recursive) {
                        targetDir.walkTopDown().map { it.relativeTo(baseDir).path }.toList()
                    } else {
                        targetDir.listFiles()?.map { it.relativeTo(baseDir).path } ?: emptyList()
                    }
                    ToolResult(
                        success = true,
                        message = "Files listed successfully.",
                        data = files.joinToString("\n")
                    )
                }
            }
        } catch (e: Exception) {
            ToolResult(
                success = false, error_details = "Failed to list files: ${e.message}",
                message = "",
                data = null
            )
        }
    }
}

/**
 * Mock command to simulate adding a dependency to a build file.
 */
class AddDependencyCommand(
    private val buildFilePath: String,
    private val dependencyString: String
) : Command<Unit> {
    override fun execute(): ToolResult {
        return try {
            val baseDir = IProjectManager.getInstance().projectDir
            val buildFile = File(baseDir, buildFilePath)
            if (!buildFile.exists() || !buildFile.isFile) {
                return ToolResult(
                    success = false, error_details = "Build file not found at: $buildFilePath",
                    message = TODO(),
                    data = TODO()
                )
            }
            // This is a naive implementation for mocking. A real one would parse the file.
            val currentContent = FileIOUtils.readFile2String(buildFile)
            val newContent =
                currentContent + "\n    implementation(\"$dependencyString\")" // Simplified assumption
            if (FileIOUtils.writeFileFromString(buildFile, newContent)) {
                ToolResult(
                    success = true,
                    message = "Dependency '$dependencyString' added to '$buildFilePath'."
                )
            } else {
                ToolResult(
                    success = false, error_details = "Failed to write to build file.",
                    message = TODO(),
                    data = TODO()
                )
            }
        } catch (e: Exception) {
            ToolResult(
                success = false, error_details = "Failed to add dependency: ${e.message}",
                message = TODO(),
                data = TODO()
            )
        }
    }
}

/**
 * Mock command to simulate asking the user a question.
 */
class AskUserCommand(private val question: String, private val options: List<String>) :
    Command<String> {
    override fun execute(): ToolResult {
        // In a real app, this would show a UI dialog.
        // For this mock, we'll just return success and assume the first option was chosen.
        val choice = options.firstOrNull() ?: "OK"
        return ToolResult(
            success = true,
            message = "User was asked: '$question' and the action proceeded.",
            data = choice
        )
    }
}