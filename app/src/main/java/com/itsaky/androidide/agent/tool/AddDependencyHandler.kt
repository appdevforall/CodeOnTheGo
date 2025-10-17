package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.AddDependencyArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade

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

        return IDEApiFacade.addDependency(dependencyString, toolArgs.buildFilePath)
    }
}
