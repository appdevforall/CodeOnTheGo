package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.CreateFileArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade

class CreateFileHandler : ToolHandler {
    override val name: String = "create_file"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<CreateFileArgs>(args)
        return IDEApiFacade.createFile(toolArgs.path, toolArgs.content)
    }
}
