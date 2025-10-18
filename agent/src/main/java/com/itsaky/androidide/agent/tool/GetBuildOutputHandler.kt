package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.model.ToolResult

class GetBuildOutputHandler : ToolHandler {
    override val name: String = "get_build_output"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        return AgentDependencies.requireToolingApi().getBuildOutput()
    }
}
