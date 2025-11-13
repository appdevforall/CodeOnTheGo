package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.activities.editor.BaseEditorActivity
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.projects.serial.ProtoProject
import com.itsaky.androidide.services.builder.GradleBuildService
import com.itsaky.androidide.services.builder.gradleDistributionParams
import com.itsaky.androidide.tooling.api.messages.AndroidInitializationParams
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.CACHE_READ_ERROR
import com.itsaky.androidide.tooling.api.messages.result.isSuccessful
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

    private val _initState = MutableStateFlow<TaskState>(TaskState.Idle)

    val initState: StateFlow<TaskState> = _initState

    /**
     * The main function to initialize (sync) the project.
     */
    fun initializeProject(buildVariants: Map<String, String>) {
        if (_initState.value is TaskState.InProgress) {
            log.warn("Initialization is already in progress. Ignoring new request.")
            return
        }

        val manager = ProjectManagerImpl.getInstance()

        // Check for a valid cached result before starting a new initialization
		val cachedResult = manager.cachedInitResult
        if (manager.projectInitialized && cachedResult is InitializeResult.Success) {
            log.debug("Project already initialized. Using cached result.")
            _initState.value = TaskState.Success(cachedResult)
            return
        }

        viewModelScope.launch {
            _initState.value = TaskState.InProgress

            val buildService = Lookup.getDefault()
                .lookup(BuildService.KEY_BUILD_SERVICE) as? GradleBuildService

            try {
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

                log.debug("Sending init request to tooling server...")
                val params = createProjectInitParams(projectDir, buildVariants)

                val result = buildService.initializeProject(params).await()

                if (result == null || !result.isSuccessful) {
                    throw InitializeException(result as InitializeResult.Failure)
                }

				result as InitializeResult.Success

				val gradleBuildResult = ProtoProject.readGradleBuild(result.cacheFile)
				if (gradleBuildResult.isFailure) {
					log.error("Failed to read project cache", gradleBuildResult.exceptionOrNull())
					throw InitializeException(InitializeResult.Failure(CACHE_READ_ERROR))
				}

                manager.cachedInitResult = result
                manager.setup(gradleBuildResult.getOrThrow()) // I/O operation
                manager.notifyProjectUpdate()

                _initState.value = TaskState.Success(result)

            } catch (e: Exception) {
                if (e is CancellationException) {
                    // This is an expected cancellation, not an error.
                    log.info("Project initialization was cancelled.")
                    // Reset the state to Idle, as the process is finished.
                    _initState.value = TaskState.Idle
                } else {
                    // This is a real, unexpected error.
                    log.error("Failed to initialize project", e)
                    val failure = (e as? InitializeException)?.result?.failure
                    _initState.value = TaskState.Error(failure, e)
                }
            } finally {
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
    private class InitializeException(val result: InitializeResult.Failure?) :
        RuntimeException("Project initialization failed.")
}