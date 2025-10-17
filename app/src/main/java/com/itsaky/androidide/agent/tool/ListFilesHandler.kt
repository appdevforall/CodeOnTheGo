package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.ListFilesArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade

class ListFilesHandler : ToolHandler {
    override val name: String = "list_dir"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<ListFilesArgs>(args)
        return IDEApiFacade.listFiles(toolArgs.path, toolArgs.recursive)
    }
}
