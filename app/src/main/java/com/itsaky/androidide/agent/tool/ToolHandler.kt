package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.ToolResult

/**
 * Contract for individual tool handlers. Each handler validates its own arguments and
 * performs the corresponding IDE operation, returning a [ToolResult].
 */
interface ToolHandler {
    val name: String
    suspend fun invoke(args: Map<String, Any?>): ToolResult
}
