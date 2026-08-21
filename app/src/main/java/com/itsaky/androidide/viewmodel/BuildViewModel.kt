package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.activities.editor.QuickBuildClobberConfirmation
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.models.ApkMetadata
import com.itsaky.androidide.models.InstallTaskRequest
import com.itsaky.androidide.models.installTaskRequestsIn
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

class BuildViewModel(
	private val projectManager: () -> IProjectManager = { IProjectManager.getInstance() },
) : ViewModel() {
	private val log = LoggerFactory.getLogger(BuildViewModel::class.java)

	private val _buildState = MutableStateFlow<BuildState>(BuildState.Idle)
	val buildState: StateFlow<BuildState> = _buildState

	/**
	 * The clobber confirmation this build's Run tap already settled (ADFA-4128), consumed once by
	 * the install. Held here rather than on the activity so a rotation mid-build does not lose it
	 * and re-ask; null means nobody asked, which makes the install fall back to asking.
	 */
	private var clobberAnswerAtTap: QuickBuildClobberConfirmation? = null

	/**
	 * Takes the tap's clobber answer, leaving nothing behind so a later build that never asked
	 * cannot inherit it.
	 */
	fun consumeClobberAnswerAtTap(): QuickBuildClobberConfirmation? = clobberAnswerAtTap.also { clobberAnswerAtTap = null }

	/**
	 * Builds the selected variant and hands the result to the installer.
	 *
	 * @param clobberAnswerAtTap what the Run tap's clobber check decided, so the install can tell
	 *   whether the answer has since changed and re-ask only then. Every build states its own,
	 *   defaulting to "nobody asked" - a build that inherited a previous tap's answer could skip a
	 *   confirmation that is genuinely owed.
	 * @param beforeBuild work that must finish BEFORE the build starts but AFTER the
	 *   in-progress reservation below - flushing unsaved editor buffers, so the build is of
	 *   what the user sees. It runs here rather than in the caller so three things hold: the
	 *   reserve-then-work race the guard below closes stays closed (caller-side, two taps can
	 *   both read Idle during a slow save on emulated storage), the build stays ordered against
	 *   anything else the caller issued, and the build runs in this ViewModel's scope rather
	 *   than one the caller's own teardown may already have cancelled.
	 *   Throwing aborts the build and lands in [BuildState.Error] - building stale on-disk
	 *   content is exactly what saving first is meant to prevent.
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
		clobberAnswerAtTap: QuickBuildClobberConfirmation? = null,
		beforeBuild: suspend () -> Unit = {},
		onTerminalState: ((BuildState) -> Unit)? = null,
	) {
		if (!claimBuildSlot(onTerminalState)) return
		this.clobberAnswerAtTap = clobberAnswerAtTap

		viewModelScope.launch {
			val reporter = RunReporter(onTerminalState)

			val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
			if (buildService == null) {
				reporter.finish(BuildState.Error("Build service not found."))
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
						reporter.finish(BuildState.AwaitingPluginInstall(cgpFile))
					} else {
						log.warn("Plugin built successfully but .cgp file not found")
						reporter.finish(
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

				reporter.finish(
					BuildState.AwaitingInstall(
						apkFile,
						launchInDebugMode,
						launchProfilerAfterInstall = launchProfilerAfterInstall,
					),
				)
			} catch (e: Exception) {
				if (e is CancellationException) {
					log.info("Build was cancelled by the user.")
					reporter.finish(BuildState.Idle)
				} else {
					log.error("Quick Run failed.", e)
					reporter.finish(BuildState.Error(e.message ?: "An unknown error occurred."))
				}
			}
		}
	}

	private inner class RunReporter(
		private val onTerminalState: ((BuildState) -> Unit)?,
	) {
		private var reported = false

		fun finish(state: BuildState) {
			_buildState.value = state
			if (!reported) {
				reported = true
				onTerminalState?.invoke(state)
			}
		}
	}

	private fun claimBuildSlot(onTerminalState: ((BuildState) -> Unit)?): Boolean {
		while (true) {
			val current = _buildState.value
			if (current is BuildState.InProgress) {
				log.warn("Build is already in progress. Ignoring new request.")
				onTerminalState?.invoke(BuildState.Error("A build is already in progress."))
				return false
			}
			if (_buildState.compareAndSet(current, BuildState.InProgress)) return true
		}
	}

	fun runTasks(
		tasks: List<String>,
		onTerminalState: ((BuildState) -> Unit)? = null,
	): Boolean {
		if (!claimBuildSlot(onTerminalState)) return false
		viewModelScope.launch {
			val reporter = RunReporter(onTerminalState)
			val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
			if (buildService == null) {
				reporter.finish(BuildState.Error("Build service not found."))
				return@launch
			}
			try {
				val result = withContext(Dispatchers.IO) { buildService.executeTasks(tasks) }.await()
				if (result == null || !result.isSuccessful) {
					throw RuntimeException("Task execution failed: ${result?.failure}")
				}
				val apkFile = withContext(Dispatchers.IO) { apkForInstallRequests(tasks) }
				if (apkFile == null) {
					reporter.finish(BuildState.Idle)
				} else {
					reporter.finish(BuildState.AwaitingInstall(apkFile, launchInDebugMode = false))
				}
			} catch (e: Exception) {
				if (e is CancellationException) {
					log.info("Build was cancelled by the user.")
					reporter.finish(BuildState.Idle)
				} else {
					log.error("Task run failed.", e)
					reporter.finish(BuildState.Error(e.message ?: "An unknown error occurred."))
				}
			}
		}
		return true
	}

	fun installsAnAppVariant(tasks: List<String>): Boolean = installTaskRequestsIn(tasks).any { appVariantFor(it) != null }

	private fun appVariantFor(request: InstallTaskRequest): AndroidModels.AndroidVariant? =
		projectManager()
			.getAndroidAppModules()
			.filter { request.modulePath == null || it.path == request.modulePath }
			.firstNotNullOfOrNull { module ->
				module.variantList.firstOrNull { it.mainArtifact.assembleTaskName == request.assembleTaskName }
			}

	private fun apkForInstallRequests(tasks: List<String>): File? {
		val resolved = installTaskRequestsIn(tasks).mapNotNull { request -> appVariantFor(request)?.let { request to it } }
		val (request, variant) = resolved.firstOrNull() ?: return null
		if (resolved.size > 1) {
			log.warn("Several install tasks were requested; only {} is installed.", request)
		}
		val apkFile =
			ApkMetadata.findApkFile(variant.mainArtifact.assembleTaskOutputListingFile)
				?: throw RuntimeException("No APK found in output listing file.")
		if (!apkFile.exists()) {
			throw RuntimeException("APK file specified does not exist: $apkFile")
		}
		return apkFile
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
