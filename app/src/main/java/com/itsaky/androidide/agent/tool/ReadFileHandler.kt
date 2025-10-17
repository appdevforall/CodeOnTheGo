package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.ReadFileArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade

class ReadFileHandler : ToolHandler {
    override val name: String = "read_file"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<ReadFileArgs>(args)
        return IDEApiFacade.readFile(toolArgs.path)
    }
}
