package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.prompt.ModelFamily

data class ToolsConfigParams(
    val modelFamily: ModelFamily,
    val enableExperimentalTools: Boolean = false,
    val experimentalSupportedTools: Set<String> = emptySet(),
    val enableShellCommandTool: Boolean = true
)

data class ToolsConfig(
    val experimentalSupportedTools: Set<String> = emptySet(),
    val enableShellCommandTool: Boolean = true
)

fun buildToolsConfig(params: ToolsConfigParams): ToolsConfig {
    val experimentalTools = if (params.enableExperimentalTools) {
        params.experimentalSupportedTools
    } else {
        emptySet()
    }
    return ToolsConfig(
        experimentalSupportedTools = experimentalTools,
        enableShellCommandTool = params.enableShellCommandTool
    )
}

fun buildToolRouter(config: ToolsConfig): ToolRouter {
    val handlers = mutableMapOf<String, ToolHandler>()

    listOf(
        CreateFileHandler(),
        ReadFileHandler(),
        UpdateFileHandler(),
        DeleteFileHandler(),
        ListFilesHandler(),
        ReadMultipleFilesHandler(),
        AddDependencyHandler(),
        AddStringResourceHandler(),
        RunAppHandler(),
        TriggerGradleSyncHandler(),
        GetBuildOutputHandler()
    ).forEach { handler ->
        handlers[handler.name] = handler
    }

    if (config.enableShellCommandTool) {
        val handler = ExecuteShellCommandHandler()
        handlers[handler.name] = handler
    }

    // Example hook for future experimental handlers.
    if (config.experimentalSupportedTools.contains("grep_files")) {
        // handlers[GrepFilesHandler().name] = GrepFilesHandler()
    }

    return ToolRouter(handlers)
}
