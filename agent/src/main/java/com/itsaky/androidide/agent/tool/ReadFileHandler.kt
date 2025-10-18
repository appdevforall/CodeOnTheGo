package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.model.ReadFileArgs
import com.itsaky.androidide.agent.model.ToolResult

class ReadFileHandler : ToolHandler {
    override val name: String = "read_file"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<ReadFileArgs>(args)
        return AgentDependencies.requireToolingApi()
            .readFile(toolArgs.filePath, toolArgs.offset, toolArgs.limit)
    }
}
