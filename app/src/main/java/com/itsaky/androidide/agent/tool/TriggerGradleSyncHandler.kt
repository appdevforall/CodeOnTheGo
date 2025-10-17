package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade

class TriggerGradleSyncHandler : ToolHandler {
    override val name: String = "trigger_gradle_sync"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        return IDEApiFacade.triggerGradleSync()
    }
}
