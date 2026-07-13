package com.itsaky.androidide.api

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.projects.builder.BuildResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Simple result wrapper for API operations.
 */
data class ApiResult(
    val success: Boolean,
    val message: String = "",
    val data: String = ""
)

/**
 * The single, clean entry point for the AI agent to interact with the IDE.
 */
object IDEApiFacade {

    suspend fun runApp(): ApiResult {
        val activity = ActionContextProvider.getActivity()
            ?: return ApiResult(false, "No active IDE window to launch the app.")

        val action = ActionsRegistry.getInstance()
            .findAction(ActionItem.Location.EDITOR_TOOLBAR, "ide.editor.build.quickRun")
            ?: return ApiResult(false, "Launch App action is not available.")

        val actionData = ActionData.create(activity)

        return suspendCancellableCoroutine { continuation ->
            val listener = java.util.function.Consumer<BuildResult> { result ->
                when {
                    result.isSuccess && result.launchResult != null && result.launchResult.isSuccess -> {
                        continuation.resume(ApiResult(true, "App built and launched successfully on the device."))
                    }
                    result.isSuccess -> {
                        val launchError =
                            result.launchResult?.message ?: "Launch failed for an unknown reason."
                        continuation.resume(ApiResult(false, "Build was successful, but the app failed to launch: $launchError"))
                    }
                    else -> {
                        continuation.resume(ApiResult(false, "Build failed: ${result.message}"))
                    }
                }
            }

            continuation.invokeOnCancellation { activity.removeOneTimeBuildResultListener(listener) }

            val registry = ActionsRegistry.getInstance() as? DefaultActionsRegistry
            if (registry == null) {
                continuation.resume(ApiResult(false, "Failed to get action registry instance."))
                return@suspendCancellableCoroutine
            }

            activity.addOneTimeBuildResultListener(listener)

            try {
                registry.executeAction(action, actionData)
            } catch (t: Throwable) {
                activity.removeOneTimeBuildResultListener(listener)
                if (continuation.isActive) {
                    continuation.resume(
                        ApiResult(false, "Failed to launch the app: ${t.message}")
                    )
                }
            }
        }
    }
}
