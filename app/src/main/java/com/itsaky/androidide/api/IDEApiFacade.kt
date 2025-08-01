package com.itsaky.androidide.api

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.api.commands.AddDependencyCommand
import com.itsaky.androidide.api.commands.AddStringResourceCommand
import com.itsaky.androidide.api.commands.GetBuildOutputCommand
import com.itsaky.androidide.api.commands.HighOrderCreateFileCommand
import com.itsaky.androidide.api.commands.HighOrderReadFileCommand
import com.itsaky.androidide.api.commands.ListFilesCommand
import com.itsaky.androidide.api.commands.UpdateFileCommand
import com.itsaky.androidide.data.model.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The single, clean entry point for the AI agent to interact with the IDE.
 * This Facade translates simple requests into executable Commands.
 */
object IDEApiFacade {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun createFile(path: String, content: String): ToolResult {
        val command = HighOrderCreateFileCommand(path, content)
        return command.execute()
    }

    fun readFile(path: String) = HighOrderReadFileCommand(path).execute()

    fun listFiles(path: String, recursive: Boolean) = ListFilesCommand(path, recursive).execute()

    fun runApp(): ToolResult {
        val activity = ActionContextProvider.getActivity()
            ?: return ToolResult.failure("No active IDE window to launch the app.")

        val action: ActionItem = ActionsRegistry.getInstance()
            .findAction(ActionItem.Location.EDITOR_TOOLBAR, "ide.editor.build.quickRun")
            ?: return ToolResult.failure("Launch App action is not available.")

        val actionData = ActionData.create(activity)

        coroutineScope.launch {
            action.execAction(actionData)
        }

        return ToolResult.success("App run command initiated successfully.")
    }

    fun addDependency(dependencyString: String, buildFilePath: String): ToolResult {
        val finalPath = buildFilePath.ifEmpty { "app/build.gradle.kts" } // Default path
        return AddDependencyCommand(finalPath, dependencyString).execute()
    }

    fun updateFile(path: String, content: String): ToolResult {
        val command = UpdateFileCommand(path, content)
        return command.execute()
    }

    /**
     * Retrieves the latest build output logs.
     * @return A ToolResult containing the build log content.
     */
    fun getBuildOutput(): ToolResult {
        val command = GetBuildOutputCommand()
        return command.execute()
    }

    /**
     * Adds a new string resource to the strings.xml file.
     * @param name The name of the string resource.
     * @param value The content of the string.
     * @return A ToolResult indicating success or failure and providing the resource reference.
     */
    fun addStringResource(name: String, value: String): ToolResult {
        return AddStringResourceCommand(name, value).execute()
    }
}