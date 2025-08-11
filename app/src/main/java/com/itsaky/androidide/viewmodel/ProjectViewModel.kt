package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.services.builder.GradleBuildService
import com.itsaky.androidide.services.builder.gradleDistributionParams
import com.itsaky.androidide.tooling.api.messages.AndroidInitializationParams
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Manages the state and logic for project-wide operations like initialization (syncing).
 */
class ProjectViewModel : ViewModel() {

    private val log = LoggerFactory.getLogger(ProjectViewModel::class.java)

    // Private mutable state that can be changed only within the ViewModel
    private val _initState = MutableStateFlow<TaskState>(TaskState.Idle)

    // Public immutable state for the UI to observe
    val initState: StateFlow<TaskState> = _initState

    /**
     * The main function to initialize (sync) the project.
     * This is the replacement for the logic previously in ProjectHandlerActivity.
     */
    fun initializeProject(buildVariants: Map<String, String>) {
        // Prevent starting a new sync if one is already in progress
        if (_initState.value is TaskState.InProgress) {
            log.warn("Initialization is already in progress. Ignoring new request.")
            return
        }

        viewModelScope.launch {
            // 1. Set state to InProgress to notify the UI
            _initState.value = TaskState.InProgress

            val manager = ProjectManagerImpl.getInstance()
            val buildService = com.itsaky.androidide.lookup.Lookup.getDefault()
                .lookup(com.itsaky.androidide.projects.builder.BuildService.KEY_BUILD_SERVICE) as? GradleBuildService

            try {
                // 2. Perform checks previously done in the Activity
                val projectDir = File(manager.projectPath)
                if (!projectDir.exists()) {
                    throw IllegalStateException("Project directory does not exist.")
                }

                if (buildService == null) {
                    throw IllegalStateException("Build service is not available.")
                }

                if (!buildService.isToolingServerStarted()) {
                    throw IllegalStateException("Tooling server is not available.")
                }

                // 3. Create parameters and execute the async task
                log.debug("Sending init request to tooling server...")
                val params = createProjectInitParams(projectDir, buildVariants)

                // Use .await() to bridge CompletableFuture to coroutines.
                // This requires the 'kotlinx-coroutines-jdk8' or 'kotlinx-coroutines-play-services' dependency.
                val result = buildService.initializeProject(params).await()

                if (result == null || !result.isSuccessful) {
                    throw InitializeException(result)
                }

                // 4. On success, process the result and update the state
                manager.cachedInitResult = result
                manager.setupProject() // I/O operation
                manager.notifyProjectUpdate()

                _initState.value = TaskState.Success(result)

            } catch (e: Exception) {
                if (e is CancellationException) {
                    // It's important to rethrow cancellation exceptions
                    throw e
                }

                log.error("An error occurred initializing the project.", e)
                val failure = (e as? InitializeException)?.result?.failure
                _initState.value = TaskState.Error(failure, e)

            } finally {
                // 5. Perform cleanup, regardless of success or failure
                buildService?.setServerListener(null)
            }
        }
    }

    private fun createProjectInitParams(
        projectDir: File,
        buildVariants: Map<String, String>
    ): InitializeProjectParams {
        return InitializeProjectParams(
            projectDir.absolutePath,
            gradleDistributionParams,
            createAndroidParams(buildVariants)
        )
    }

    private fun createAndroidParams(buildVariants: Map<String, String>): AndroidInitializationParams {
        return if (buildVariants.isEmpty()) {
            AndroidInitializationParams.DEFAULT
        } else {
            AndroidInitializationParams(buildVariants)
        }
    }

    /** Custom exception to wrap the InitializeResult on failure. */
    private class InitializeException(val result: InitializeResult?) :
        RuntimeException("Project initialization failed.")
}