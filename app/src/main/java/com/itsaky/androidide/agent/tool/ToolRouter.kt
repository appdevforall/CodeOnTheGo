package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.ToolResult

class ToolRouter(
    private val handlers: Map<String, ToolHandler>
) {

    suspend fun dispatch(toolName: String, args: Map<String, Any?>): ToolResult {
        val handler = handlers[toolName]
            ?: return ToolResult.failure("Unknown function '$toolName'")
        return handler.invoke(args)
    }

    fun getHandler(toolName: String): ToolHandler? = handlers[toolName]
}
