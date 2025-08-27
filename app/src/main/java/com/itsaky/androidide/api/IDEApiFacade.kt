package com.itsaky.androidide.api

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.commands.AddDependencyCommand
import com.itsaky.androidide.api.commands.AddStringResourceCommand
import com.itsaky.androidide.api.commands.GetBuildOutputCommand
import com.itsaky.androidide.api.commands.HighOrderCreateFileCommand
import com.itsaky.androidide.api.commands.HighOrderReadFileCommand
import com.itsaky.androidide.api.commands.ListFilesCommand
import com.itsaky.androidide.api.commands.UpdateFileCommand
import com.itsaky.androidide.projects.builder.BuildResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The single, clean entry point for the AI agent to interact with the IDE.
 * This Facade translates simple requests into executable Commands.
 */
object IDEApiFacade {
    fun createFile(path: String, content: String): ToolResult {
        val command = HighOrderCreateFileCommand(path, content)
        return command.execute()
    }

    fun readFile(path: String) = HighOrderReadFileCommand(path).execute()

    fun listFiles(path: String, recursive: Boolean) = ListFilesCommand(path, recursive).execute()

    suspend fun runApp(): ToolResult {
        val activity = ActionContextProvider.getActivity()
            ?: return ToolResult.failure("No active IDE window to launch the app.")

        val action = ActionsRegistry.getInstance()
            .findAction(ActionItem.Location.EDITOR_TOOLBAR, "ide.editor.build.quickRun")
            ?: return ToolResult.failure("Launch App action is not available.")

        val actionData = ActionData.create(activity)

        return suspendCancellableCoroutine { continuation ->
            val listener = java.util.function.Consumer<BuildResult> { result ->
                // The callback now handles different outcomes
                when {
                    result.isSuccess && result.launchResult != null && result.launchResult.isSuccess -> {
                        // This is the true success case: Build AND Launch worked.
                        continuation.resume(ToolResult.success("App built and launched successfully on the device."))
                    }

                    result.isSuccess -> {
                        // This case means the build worked, but the launch failed or didn't happen.
                        val launchError =
                            result.launchResult?.message ?: "Launch failed for an unknown reason."
                        continuation.resume(ToolResult.failure("Build was successful, but the app failed to launch: $launchError"))
                    }

                    else -> {
                        // This is a build failure.
                        continuation.resume(ToolResult.failure("Build failed: ${result.message}"))
                    }
                }
            }

//            activity.addOneTimeBuildResultListener(listener)

            (ActionsRegistry.getInstance() as? DefaultActionsRegistry)?.executeAction(
                action,
                actionData
            )
                ?: continuation.resume(ToolResult.failure("Failed to get action registry instance."))
        }
    }

    fun addDependency(dependencyString: String, buildFilePath: String): ToolResult {
        val finalPath = if (buildFilePath.isEmpty()) "app/build.gradle.kts" else buildFilePath
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