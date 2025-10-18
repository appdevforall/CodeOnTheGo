package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.model.AddStringResourceArgs
import com.itsaky.androidide.agent.model.ToolResult

class AddStringResourceHandler : ToolHandler {
    override val name: String = "add_string_resource"
    override val isPotentiallyDangerous: Boolean = true

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<AddStringResourceArgs>(args)
        if (toolArgs.name.isBlank() || toolArgs.value.isBlank()) {
            return ToolResult.failure("Both 'name' and 'value' are required.")
        }
        return AgentDependencies.requireToolingApi()
            .addStringResource(toolArgs.name, toolArgs.value)
    }
}
