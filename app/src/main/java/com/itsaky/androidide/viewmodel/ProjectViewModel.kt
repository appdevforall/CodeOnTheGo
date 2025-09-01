package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.builder.BuildService
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
        if (manager.projectInitialized && manager.cachedInitResult != null) {
            log.debug("Project already initialized. Using cached result.")
            _initState.value = TaskState.Success(manager.cachedInitResult!!)
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
                    throw InitializeException(result)
                }

                manager.cachedInitResult = result
                manager.setupProject() // I/O operation
                manager.notifyProjectUpdate()

                _initState.value = TaskState.Success(result)

            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }

                log.error("An error occurred initializing the project.", e)
                val failure = (e as? InitializeException)?.result?.failure
                _initState.value = TaskState.Error(failure, e)

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
    private class InitializeException(val result: InitializeResult?) :
        RuntimeException("Project initialization failed.")
}