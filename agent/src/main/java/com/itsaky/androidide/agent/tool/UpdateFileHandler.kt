package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.model.UpdateFileArgs

class UpdateFileHandler : ToolHandler {
    override val name: String = "update_file"
    override val isPotentiallyDangerous: Boolean = true

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<UpdateFileArgs>(args)
        return AgentDependencies.requireToolingApi()
            .updateFile(toolArgs.path, toolArgs.content)
    }
}
