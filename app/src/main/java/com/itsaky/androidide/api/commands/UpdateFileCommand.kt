package com.itsaky.androidide.api.commands

import com.blankj.utilcode.util.FileIOUtils
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.projects.IProjectManager
import org.apache.commons.text.StringEscapeUtils
import java.io.File


class UpdateFileCommand(private val path: String, private val content: String) : Command<Unit> {
    override fun execute(): ToolResult {
        return try {
            val targetFile = File(IProjectManager.getInstance().projectDir, path)
            when {
                !targetFile.exists() -> ToolResult(
                    success = false,
                    error_details = "File not found at path: $path. Cannot update a non-existent file.",
                    message = "",
                    data = null
                )

                targetFile.isDirectory -> ToolResult(
                    success = false,
                    error_details = "Path points to a directory, not a file: $path",
                    message = "",
                    data = null
                )

                else -> {
                    val formattedContent = StringEscapeUtils.unescapeJava(content)

                    if (FileIOUtils.writeFileFromString(targetFile, formattedContent)) {
                        ToolResult(
                            success = true,
                            message = "File updated successfully at path: $path"
                        )
                    } else {
                        ToolResult(
                            success = false,
                            error_details = "Failed to write to file at path: $path",
                            message = "",
                            data = null
                        )
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult(
                success = false,
                error_details = "Failed to update file: ${e.message}",
                message = "",
                data = null
            )
        }
    }
}
