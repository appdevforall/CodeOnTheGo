package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.model.AddDependencyArgs
import com.itsaky.androidide.agent.model.ToolResult

class AddDependencyHandler : ToolHandler {
    override val name: String = "add_dependency"
    override val isPotentiallyDangerous: Boolean = true

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<AddDependencyArgs>(args)
        if (toolArgs.dependency.isBlank() || toolArgs.buildFilePath.isBlank()) {
            return ToolResult.failure("Both 'dependency' and 'build_file_path' are required.")
        }

        val dependencyString = if (toolArgs.buildFilePath.endsWith(".kts")) {
            "implementation(\"${toolArgs.dependency}\")"
        } else {
            "implementation '${toolArgs.dependency}'"
        }

        return AgentDependencies.requireToolingApi()
            .addDependency(dependencyString, toolArgs.buildFilePath)
    }
}
