package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.DeleteFileArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade

class DeleteFileHandler : ToolHandler {
    override val name: String = "delete_file"
    override val isPotentiallyDangerous: Boolean = true

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<DeleteFileArgs>(args)
        return IDEApiFacade.deleteFile(toolArgs.path)
    }
}
