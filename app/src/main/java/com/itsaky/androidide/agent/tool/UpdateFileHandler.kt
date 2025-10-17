package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.model.UpdateFileArgs
import com.itsaky.androidide.api.IDEApiFacade

class UpdateFileHandler : ToolHandler {
    override val name: String = "update_file"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<UpdateFileArgs>(args)
        return IDEApiFacade.updateFile(toolArgs.path, toolArgs.content)
    }
}
