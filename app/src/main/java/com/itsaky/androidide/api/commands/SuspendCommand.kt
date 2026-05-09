package com.itsaky.androidide.api.commands

import com.itsaky.androidide.agent.model.ToolResult

interface SuspendCommand<T> {
    suspend fun execute(): ToolResult
}
