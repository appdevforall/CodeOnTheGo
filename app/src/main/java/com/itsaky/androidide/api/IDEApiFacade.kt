package com.itsaky.androidide.api

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.commands.AddDependencyCommand
import com.itsaky.androidide.api.commands.AddStringResourceCommand
import com.itsaky.androidide.api.commands.DeleteFileCommand
import com.itsaky.androidide.api.commands.GetBuildOutputCommand
import com.itsaky.androidide.api.commands.HighOrderCreateFileCommand
import com.itsaky.androidide.api.commands.HighOrderReadFileCommand
import com.itsaky.androidide.api.commands.ListFilesCommand
import com.itsaky.androidide.api.commands.UpdateFileCommand
import com.itsaky.androidide.projects.builder.BuildResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The single, clean entry point for the AI agent to interact with the IDE.
 * This Facade translates simple requests into executable Commands.
 */
object IDEApiFacade {
    private const val BUILD_STATUS_POLL_MS = 2000L
    private const val BUILD_STATUS_MAX_ATTEMPTS = 60

    fun createFile(path: String, content: String): ToolResult {
        val command = HighOrderCreateFileCommand(path, content)
        return command.execute()
    }

    fun readFile(path: String) = HighOrderReadFileCommand(path).execute()

    fun listFiles(path: String, recursive: Boolean) = ListFilesCommand(path, recursive).execute()

    suspend fun triggerGradleSync(): ToolResult {
        val activity = ActionContextProvider.getActivity()
            ?: return ToolResult.failure("No active IDE window to sync the project.")

        val syncAction = ActionsRegistry.getInstance()
            .findAction(ActionItem.Location.EDITOR_TOOLBAR, "ide.editor.syncProject")
            ?: return ToolResult.failure("Project Sync action could not be found.")

        val actionData = ActionData.create(activity)

        return try {
            // Since this action is UI-related, ensure it runs on the main thread
            withContext(Dispatchers.Main) {
                (ActionsRegistry.getInstance() as? DefaultActionsRegistry)?.executeAction(
                    syncAction,
                    actionData
                )
            }

            // WAIT LOGIC IS NOW INSIDE THE TOOL
            // This prevents the agent from doing anything else until the sync is complete.
            while (isBuildRunning().data == "true") {
                delay(2000) // Check status every 2 seconds
            }

            // After waiting, return the final build output as the definitive result of the sync.
            getBuildOutput()

        } catch (e: Exception) {
            ToolResult.failure("Failed to trigger Gradle Sync: ${e.message}")
        }
    }

    suspend fun runApp(): ToolResult {
        val activity = ActionContextProvider.getActivity()
            ?: return ToolResult.failure("No active IDE window to launch the app.")

        val action = ActionsRegistry.getInstance()
            .findAction(ActionItem.Location.EDITOR_TOOLBAR, "ide.editor.build.quickRun")
            ?: return ToolResult.failure("Launch App action is not available.")

        val registry = ActionsRegistry.getInstance() as? DefaultActionsRegistry
            ?: return ToolResult.failure("Failed to get action registry instance.")

        val actionData = ActionData.create(activity)

        ensureBuildQueueIdle()?.let { return it }

        // This suspendCancellableCoroutine will now ALSO wait for the build to finish.
        return suspendCancellableCoroutine<ToolResult> { continuation ->
            val listener = java.util.function.Consumer<BuildResult> { result ->
                val outcome = when {
                    result.isSuccess && result.launchResult != null && result.launchResult.isSuccess ->
                        ToolResult.success("App built and launched successfully on the device.")

                    result.isSuccess -> {
                        val launchError =
                            result.launchResult?.message ?: "Launch failed for an unknown reason."
                        ToolResult.failure(
                            "Build was successful, but the app failed to launch: $launchError"
                        )
                    }

                    else -> ToolResult.failure("Build failed: ${result.message}")
                }

                if (!continuation.isCompleted) {
                    continuation.resume(outcome)
                }
            }

            activity.addOneTimeBuildResultListener(listener)
            continuation.invokeOnCancellation {
                activity.runOnUiThread { activity.removeBuildResultListener(listener) }
            }

            activity.runOnUiThread {
                try {
                    registry.executeAction(action, actionData)
                } catch (e: Exception) {
                    activity.removeBuildResultListener(listener)
                    if (!continuation.isCompleted) {
                        continuation.resume(
                            ToolResult.failure("Failed to launch the app: ${e.message}")
                        )
                    }
                }
            }
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

    fun deleteFile(path: String): ToolResult {
        val command = DeleteFileCommand(path)
        return command.execute()
    }

    fun isBuildRunning(): ToolResult {
        val activity = ActionContextProvider.getActivity()
            ?: return ToolResult.failure("No active IDE window.")

        // Access the EditorViewModel to check the true internal state
        val isInitializing = activity.editorViewModel.isInitializing
        val isBuilding = activity.editorViewModel.isBuildInProgress

        val buildIsActive = isInitializing || isBuilding

        // Return the state as data in the ToolResult
        return ToolResult.success(
            message = "Build status checked.",
            data = buildIsActive.toString()
        )
    }

    private suspend fun ensureBuildQueueIdle(): ToolResult? {
        var attempts = 0
        while (true) {
            val status = isBuildRunning()
            if (!status.success) {
                return status
            }
            val busy = status.data?.equals("true", ignoreCase = true) == true
            if (!busy) {
                return null
            }
            if (attempts++ >= BUILD_STATUS_MAX_ATTEMPTS) {
                return ToolResult.failure(
                    "Gradle build or sync is still running. Try again once it finishes."
                )
            }
            delay(BUILD_STATUS_POLL_MS)
        }
    }
}
