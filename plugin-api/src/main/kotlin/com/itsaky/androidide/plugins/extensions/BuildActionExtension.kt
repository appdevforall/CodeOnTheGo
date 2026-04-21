package com.itsaky.androidide.plugins.extensions

import com.itsaky.androidide.plugins.IPlugin

interface BuildActionExtension : IPlugin {
    fun getBuildActions(): List<PluginBuildAction>
    fun toolbarActionsToHide(): Set<String> = emptySet()
    fun onActionStarted(actionId: String) {}
    fun onActionCompleted(actionId: String, result: CommandResult) {}
}

object ToolbarActionIds {
    const val QUICK_RUN = "ide.editor.build.quickRun"
    const val PROJECT_SYNC = "ide.editor.syncProject"
    const val DEBUG = "ide.editor.build.debug"
    const val RUN_TASKS = "ide.editor.build.runTasks"
    const val UNDO = "ide.editor.code.text.undo"
    const val REDO = "ide.editor.code.text.redo"
    const val SAVE = "ide.editor.files.saveAll"
    const val PREVIEW_LAYOUT = "ide.editor.previewLayout"
    const val FIND = "ide.editor.find"
    const val FIND_IN_FILE = "ide.editor.find.inFile"
    const val FIND_IN_PROJECT = "ide.editor.find.inProject"
    const val LAUNCH_APP = "ide.editor.launchInstalledApp"
    const val DISCONNECT_LOG_SENDERS = "ide.editor.service.logreceiver.disconnectSenders"
    const val GENERATE_XML = "ide.editor.generatexml"

    val ALL: Set<String> = setOf(
        QUICK_RUN, PROJECT_SYNC, DEBUG, RUN_TASKS,
        UNDO, REDO, SAVE, PREVIEW_LAYOUT,
        FIND, FIND_IN_FILE, FIND_IN_PROJECT,
        LAUNCH_APP, DISCONNECT_LOG_SENDERS, GENERATE_XML
    )

    val BUILD_HIDEABLE: Set<String> = setOf(
        QUICK_RUN, PROJECT_SYNC, DEBUG, RUN_TASKS, LAUNCH_APP
    )
}

data class PluginBuildAction(
    val id: String,
    val name: String,
    val description: String,
    val icon: Int? = null,
    val category: BuildActionCategory = BuildActionCategory.CUSTOM,
    val command: CommandSpec,
    val timeoutMs: Long = 600_000
)

sealed class CommandSpec {
    data class ShellCommand(
        val executable: String,
        val arguments: List<String> = emptyList(),
        val workingDirectory: String? = null,
        val environment: Map<String, String> = emptyMap()
    ) : CommandSpec()

    data class GradleTask(
        val taskPath: String,
        val arguments: List<String> = emptyList()
    ) : CommandSpec()
}

sealed class CommandOutput {
    data class StdOut(val line: String) : CommandOutput()
    data class StdErr(val line: String) : CommandOutput()
    data class ExitCode(val code: Int) : CommandOutput()
}

sealed class CommandResult {
    data class Success(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long
    ) : CommandResult()

    data class Failure(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val error: String?,
        val durationMs: Long
    ) : CommandResult()

    data class Cancelled(
        val partialStdout: String,
        val partialStderr: String
    ) : CommandResult()
}

enum class BuildActionCategory { BUILD, TEST, DEPLOY, LINT, CUSTOM }