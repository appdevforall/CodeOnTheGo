package com.itsaky.androidide.api.commands

import com.itsaky.androidide.agent.model.ToolResult

interface Command<T> {
    fun execute(): ToolResult
}
