package com.itsaky.androidide.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.R
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.models.ApkMetadata
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.models.BasicAndroidVariantMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

class BuildViewModel(private val application: Application) : AndroidViewModel(application) {

    private val log = LoggerFactory.getLogger(BuildViewModel::class.java)

    private val _buildState = MutableStateFlow<BuildState>(BuildState.Idle)
    val buildState: StateFlow<BuildState> = _buildState

    fun runQuickBuild(module: AndroidModule, variant: BasicAndroidVariantMetadata) {
        if (_buildState.value is BuildState.InProgress) {
            log.warn(application.getString(R.string.build_in_progress_warning))
            return
        }

        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            _buildState.value = BuildState.InProgress

            val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
            if (buildService == null) {
                _buildState.value =
                    BuildState.Error(context.getString(R.string.error_build_service_not_found))
                return@launch
            }

            try {
                val taskName = "${module.path}:${variant.mainArtifact.assembleTaskName}"
                val message = TaskExecutionMessage(tasks = listOf(taskName))
                val result = buildService.executeTasks(message).await()

                if (result == null || !result.isSuccessful) {
                    throw RuntimeException(context.getString(R.string.error_task_execution_failed))
                }

                val outputListingFile = variant.mainArtifact.assembleTaskOutputListingFile
                    ?: throw RuntimeException(context.getString(R.string.error_no_output_listing_file))

                val apkFile = ApkMetadata.findApkFile(outputListingFile)
                    ?: throw RuntimeException(context.getString(R.string.error_no_apk_in_output_listing))

                if (!apkFile.exists()) {
                    throw RuntimeException(context.getString(R.string.error_apk_not_exist, apkFile))
                }

                _buildState.value = BuildState.AwaitingInstall(apkFile)

            } catch (e: Exception) {
                if (e is CancellationException) {
                    log.info(context.getString(R.string.info_build_cancelled))
                    _buildState.value = BuildState.Idle
                } else {
                    log.error(context.getString(R.string.error_quick_run_failed), e)
                    _buildState.value =
                        BuildState.Error(e.message ?: context.getString(R.string.unknown_error))
                }
            }
        }
    }

    /** Call this after the installation attempt to reset the state. */
    fun installationAttempted() {
        if (_buildState.value is BuildState.AwaitingInstall) {
            _buildState.value = BuildState.Idle
        }
    }
}