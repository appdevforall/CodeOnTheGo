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
import com.itsaky.androidide.tooling.api.messages.BuildRunType
import com.itsaky.androidide.tooling.api.messages.GradleBuildParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class BuildViewModel : ViewModel() {
	private val log = LoggerFactory.getLogger(BuildViewModel::class.java)

	private val _buildState = MutableStateFlow<BuildState>(BuildState.Idle)
	val buildState: StateFlow<BuildState> = _buildState

	/**
	 * @param beforeBuild work that must finish BEFORE the build starts but AFTER the
	 *   in-progress reservation below - flushing unsaved editor buffers, so the build is of
	 *   what the user sees. It runs here rather than in the caller so three things hold: the
	 *   reserve-then-work race the guard below closes stays closed (caller-side, two taps can
	 *   both read Idle during a slow save on emulated storage), the build stays ordered against
	 *   anything else the caller issued, and the build runs in this ViewModel's scope rather
	 *   than one the caller's own teardown may already have cancelled.
	 *   Throwing aborts the build and lands in [BuildState.Error] - building stale on-disk
	 *   content is exactly what saving first is meant to prevent.
	 */
	fun runQuickBuild(
		module: AndroidModule,
		variant: AndroidModels.AndroidVariant,
		launchInDebugMode: Boolean,
		launchProfilerAfterInstall: Boolean = false,
		gradleArgs: List<String> = emptyList(),
		beforeBuild: suspend () -> Unit = {},
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
				beforeBuild()

				val isPluginProject =
					withContext(Dispatchers.IO) {
						IProjectManager.getInstance().isPluginProject()
					}

				val taskName =
					if (isPluginProject) {
						if (variant.name.contains("debug", ignoreCase = true)) {
							":assemblePluginDebug"
						} else {
							":assemblePlugin"
						}
					} else {
						"${module.path}:${variant.mainArtifact.assembleTaskName}"
					}

				val message =
					TaskExecutionMessage(
						tasks = listOf(taskName),
						buildId = buildService.nextBuildId(BuildRunType.TaskRun),
						buildParams = GradleBuildParams(gradleArgs = gradleArgs),
					)

				val result =
					withContext(Dispatchers.IO) {
						buildService.executeTasks(message)
					}.await()

				if (result == null || !result.isSuccessful) {
					throw RuntimeException("Task execution failed: ${result.failure}")
				}

				if (isPluginProject) {
					val projectRoot = IProjectManager.getInstance().projectDirPath
					val cgpFile =
						withContext(Dispatchers.IO) { findPluginCgpFile(projectRoot, variant) }
					if (cgpFile != null) {
						_buildState.value = BuildState.AwaitingPluginInstall(cgpFile)
					} else {
						log.warn("Plugin built successfully but .cgp file not found")
						_buildState.value =
							BuildState.Error("Plugin built but output file (.cgp) not found in build/plugin")
					}
					return@launch
				}

				val outputListingFile = variant.mainArtifact.assembleTaskOutputListingFile

				val apkFile =
					withContext(Dispatchers.IO) {
						ApkMetadata.findApkFile(outputListingFile)
					} ?: throw RuntimeException("No APK found in output listing file.")

				val apkExists = withContext(Dispatchers.IO) { apkFile.exists() }
				if (!apkExists) {
					throw RuntimeException("APK file specified does not exist: $apkFile")
				}

				_buildState.value =
					BuildState.AwaitingInstall(
						apkFile,
						launchInDebugMode,
						launchProfilerAfterInstall = launchProfilerAfterInstall,
					)
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

	/** Call this after the error has been shown once, so a lifecycle replay does not re-flash it. */
	fun errorDisplayed() {
		if (_buildState.value is BuildState.Error) {
			_buildState.value = BuildState.Idle
		}
	}

	/** Call this after the plugin installation attempt to reset the state. */
	fun pluginInstallationAttempted() {
		if (_buildState.value is BuildState.AwaitingPluginInstall) {
			_buildState.value = BuildState.Idle
		}
	}

	private fun findPluginCgpFile(
		projectRoot: String,
		variant: AndroidModels.AndroidVariant,
	): File? {
		val pluginDir = File(projectRoot, "build/plugin")
		if (!pluginDir.exists()) return null

		val isDebug = variant.name.contains("debug", ignoreCase = true)
		return pluginDir
			.listFiles { file -> file.extension.equals(PLUGIN_ARCHIVE_EXTENSION, ignoreCase = true) }
			?.filter { it.name.contains("-debug") == isDebug }
			?.maxByOrNull { it.lastModified() }
	}
}
