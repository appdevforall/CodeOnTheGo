package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.model.ToolResult

class RunAppHandler : ToolHandler {
    override val name: String = "run_app"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        return AgentDependencies.requireToolingApi().runApp()
    }
}
