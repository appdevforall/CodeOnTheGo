package com.itsaky.androidide.api.commands

import com.itsaky.androidide.data.model.ToolResult

interface Command<T> {
    fun execute(): ToolResult
}
