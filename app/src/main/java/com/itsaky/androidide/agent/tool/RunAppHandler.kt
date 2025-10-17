package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade

class RunAppHandler : ToolHandler {
    override val name: String = "run_app"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        return IDEApiFacade.runApp()
    }
}
