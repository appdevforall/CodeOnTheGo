package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.models.ApkMetadata
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.projects.isPluginProject
import com.itsaky.androidide.projects.models.assembleTaskOutputListingFile
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class BuildViewModel : ViewModel() {
	private val log = LoggerFactory.getLogger(BuildViewModel::class.java)

	private val _buildState = MutableStateFlow<BuildState>(BuildState.Idle)
	val buildState: StateFlow<BuildState> = _buildState

	fun runQuickBuild(
		module: AndroidModule,
		variant: AndroidModels.AndroidVariant,
		launchInDebugMode: Boolean,
	) {
		if (_buildState.value is BuildState.InProgress) {
			log.warn("Build is already in progress. Ignoring new request.")
			return
		}

		viewModelScope.launch {
			_buildState.value = BuildState.InProgress

			val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
			if (buildService == null) {
				_buildState.value = BuildState.Error("Build service not found.")
				return@launch
			}

			try {
				val isPluginProject = IProjectManager.getInstance().isPluginProject()

				val taskName = if (isPluginProject) {
					if (variant.name.contains("debug", ignoreCase = true)) {
						":assemblePluginDebug"
					} else {
						":assemblePlugin"
					}
				} else {
					"${module.path}:${variant.mainArtifact.assembleTaskName}"
				}

				val message = TaskExecutionMessage(tasks = listOf(taskName))
				val result = buildService.executeTasks(message).await()

				if (result == null || !result.isSuccessful) {
					throw RuntimeException("Task execution failed.")
				}

				if (isPluginProject) {
					val projectRoot = IProjectManager.getInstance().projectDirPath
					val cgpFile = findPluginCgpFile(projectRoot, variant)
					if (cgpFile != null) {
						_buildState.value = BuildState.AwaitingPluginInstall(cgpFile)
					} else {
						log.warn("Plugin built successfully but .cgp file not found")
						_buildState.value = BuildState.Idle
					}
					return@launch
				}

				val outputListingFile = variant.mainArtifact.assembleTaskOutputListingFile

				val apkFile =
					ApkMetadata.findApkFile(outputListingFile)
						?: throw RuntimeException("No APK found in output listing file.")

				if (!apkFile.exists()) {
					throw RuntimeException("APK file specified does not exist: $apkFile")
				}

				_buildState.value = BuildState.AwaitingInstall(apkFile, launchInDebugMode)
			} catch (e: Exception) {
				if (e is CancellationException) {
					log.info("Build was cancelled by the user.")
					_buildState.value = BuildState.Idle
				} else {
					log.error("Quick Run failed.", e)
					_buildState.value = BuildState.Error(e.message ?: "An unknown error occurred.")
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

	/** Call this after the plugin installation attempt to reset the state. */
	fun pluginInstallationAttempted() {
		if (_buildState.value is BuildState.AwaitingPluginInstall) {
			_buildState.value = BuildState.Idle
		}
	}

	private fun findPluginCgpFile(projectRoot: String, variant: AndroidModels.AndroidVariant): File? {
		val pluginDir = File(projectRoot, "build/plugin")
		if (!pluginDir.exists()) return null

		val isDebug = variant.name.contains("debug", ignoreCase = true)
		return pluginDir.listFiles { file -> file.extension.equals("cgp", ignoreCase = true) }
			?.firstOrNull { it.name.contains("-debug") == isDebug }
	}
}
