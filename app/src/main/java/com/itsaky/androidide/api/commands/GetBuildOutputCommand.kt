package com.itsaky.androidide.api.commands

import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.BuildOutputProvider

class GetBuildOutputCommand : Command<Unit> {
    override fun execute(): ToolResult {
        return try {
            val logContent = BuildOutputProvider.getBuildOutputContent()

            if (!logContent.isNullOrBlank()) {
                ToolResult.success(
                    message = "Successfully retrieved build output.",
                    data = logContent
                )
            } else {
                ToolResult.failure(message = "Build output is empty or not available.")
            }
        } catch (e: Exception) {
            ToolResult.failure(
                message = "An error occurred while getting build output.",
                error_details = e.message
            )
        }
    }
}