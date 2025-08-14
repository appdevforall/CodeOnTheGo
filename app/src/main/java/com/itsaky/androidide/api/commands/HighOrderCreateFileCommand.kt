package com.itsaky.androidide.api.commands

import com.itsaky.androidide.data.model.ToolResult
import com.itsaky.androidide.events.ListProjectFilesRequestEvent
import com.itsaky.androidide.projects.IProjectManager
import org.greenrobot.eventbus.EventBus
import java.io.File


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

                val targetFile = File(baseDir, path)
                val fileExisted = targetFile.exists()
                val message = if (fileExisted) {
                    "File updated successfully at path: $path"
                } else {
                    "File created successfully at path: $path"
                }
                ToolResult(success = true, message = message)
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
