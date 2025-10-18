package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.model.ToolResult

/**
 * Returns the current date and time in Quito, Ecuador.
 */
class GetCurrentDateTimeHandler : ToolHandler {
    override val name: String = "get_current_datetime"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        return AgentDependencies.requireToolingApi().getCurrentDateTime()
    }
}

