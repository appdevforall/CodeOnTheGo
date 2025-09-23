package com.itsaky.androidide.api.commands

import com.itsaky.androidide.agent.model.ToolResult


class HighOrderReadFileCommand(private val path: String) : Command<String> {
    override fun execute(): ToolResult {
        val readFileCommand = ReadFileCommand(path)
        val result = readFileCommand.execute()
        return if (result.isSuccess) {
            ToolResult(
                message = "File read successfully.",
                success = true,
                data = result.getOrNull(),
                error_details = null
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
