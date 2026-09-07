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
	 * Builds the selected variant and hands the result to the installer.
	 *
	 * @param onTerminalState invoked exactly once with the state the run ends on. [buildState] is
	 *   a conflated flow whose terminal values are transient — the editor resets `AwaitingInstall`
	 *   to `Idle` the moment it takes the APK — so a caller that must not miss the outcome (a
	 *   plugin waiting on a callback) has to be told directly rather than observe the flow.
	 */
	fun runQuickBuild(
		module: AndroidModule,
		variant: AndroidModels.AndroidVariant,
		launchInDebugMode: Boolean,
		launchProfilerAfterInstall: Boolean = false,
		gradleArgs: List<String> = emptyList(),
		onTerminalState: ((BuildState) -> Unit)? = null,
	) {
		// Claim the slot before the coroutine is scheduled, and in one step: a check here and a set
		// inside the launched block let two callers both read a free state and both reach
		// executeTasks, running duplicate build-and-install flows.
		while (true) {
			val current = _buildState.value
			if (current is BuildState.InProgress) {
				log.warn("Build is already in progress. Ignoring new request.")
				onTerminalState?.invoke(BuildState.Error("A build is already in progress."))
				return
			}
			if (_buildState.compareAndSet(current, BuildState.InProgress)) {
				break
			}
		}

		viewModelScope.launch {
			var reported = false

			// Publishes a terminal state and notifies the caller once, from the one place that
			// knows the run is over. Called only on the main dispatcher, so the flag needs no lock.
			fun finish(state: BuildState) {
				_buildState.value = state
				if (!reported) {
					reported = true
					onTerminalState?.invoke(state)
				}
			}

			val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
			if (buildService == null) {
				finish(BuildState.Error("Build service not found."))
				return@launch
			}

			try {
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
						finish(BuildState.AwaitingPluginInstall(cgpFile))
					} else {
						log.warn("Plugin built successfully but .cgp file not found")
						finish(
							BuildState.Error("Plugin built but output file (.cgp) not found in build/plugin"),
						)
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

				finish(
					BuildState.AwaitingInstall(
						apkFile,
						launchInDebugMode,
						launchProfilerAfterInstall = launchProfilerAfterInstall,
					),
				)
			} catch (e: Exception) {
				if (e is CancellationException) {
					log.info("Build was cancelled by the user.")
					finish(BuildState.Idle)
				} else {
					log.error("Quick Run failed.", e)
					finish(BuildState.Error(e.message ?: "An unknown error occurred."))
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
