
package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.plugins.services.BuildAndLaunchCallback
import com.itsaky.androidide.plugins.services.BuildStatusListener
import com.itsaky.androidide.plugins.services.GradleSyncCallback
import com.itsaky.androidide.plugins.services.GradleTaskInfo
import com.itsaky.androidide.plugins.services.GradleTaskResult
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.BuildRunType
import com.itsaky.androidide.tooling.api.messages.GradleBuildParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.messages.result.BuildCancellationRequestResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Implementation of IdeBuildService that provides access to Code On the Go's build system
 * status and operations for plugins that need to monitor or interact with builds.
 *
 * This is a singleton to ensure all plugins share the same build state and receive
 * consistent notifications from the main build system.
 */
class IdeBuildServiceImpl private constructor() : IdeBuildService {
	private val buildStatusListeners = CopyOnWriteArraySet<BuildStatusListener>()
	private var buildInProgress = false
	private var toolingServerStarted = false

	// Providers set by the app module
	private var runAppProvider: ((BuildAndLaunchCallback) -> Unit)? = null
	private var gradleSyncProvider: ((GradleSyncCallback) -> Unit)? = null
	private var buildOutputProvider: (() -> String?)? = null

	companion object {
		private val log = LoggerFactory.getLogger(IdeBuildServiceImpl::class.java)
		private const val BUILD_IN_PROGRESS_REASON = "another build is in progress"

		// What a plugin's run keeps of its output: the tail, where Gradle reports a failure.
		private const val MAX_OUTPUT_CHARS = 128 * 1024

		@Volatile
		private var instance: IdeBuildServiceImpl? = null

		fun getInstance(): IdeBuildServiceImpl =
			instance ?: synchronized(this) {
				instance ?: IdeBuildServiceImpl().also { instance = it }
			}

		/** The tasks of [build]'s root project and modules, in that order; empty before a sync. */
		internal fun tasksOf(build: GradleModels.GradleBuild?): List<GradleTaskInfo> {
			if (build == null) return emptyList()
			val projects = listOfNotNull(build.rootProject.takeIf { build.hasRootProject() }) + build.subProjectList
			// Sync puts the root in subProjectList too (RootModelBuilder maps every IDEA module).
			return projects.flatMap { it.taskList }.distinctBy { it.path }.map { it.toInfo() }
		}

		// Proto3 reads an unset optional string as "", which a plugin would take for a real value.
		private fun GradleModels.GradleTask.toInfo() =
			GradleTaskInfo(
				path = path,
				name = name,
				projectPath = projectPath,
				group = group.takeIf { hasGroup() && it.isNotBlank() },
				description = description.takeIf { hasDescription() && it.isNotBlank() },
			)
	}

	override fun isBuildInProgress(): Boolean = buildInProgress

	override fun isToolingServerStarted(): Boolean = toolingServerStarted

	override fun addBuildStatusListener(callback: BuildStatusListener) {
		buildStatusListeners.add(callback)
	}

	override fun removeBuildStatusListener(callback: BuildStatusListener) {
		buildStatusListeners.remove(callback)
	}

	/**
	 * Internal method to update build status (should be called by Code On the Go's build system)
	 */
	fun setBuildInProgress(inProgress: Boolean) {
		if (this.buildInProgress != inProgress) {
			this.buildInProgress = inProgress
			if (inProgress) {
				notifyBuildStarted()
			}
		}
	}

	/**
	 * Internal method to update tooling server status (should be called by Code On the Go's build system)
	 */
	fun setToolingServerStarted(started: Boolean) {
		this.toolingServerStarted = started
	}

	/**
	 * Internal method to notify listeners of build completion (should be called by Code On the Go's build system)
	 */
	fun notifyBuildFinished() {
		this.buildInProgress = false
		buildStatusListeners.forEach { listener ->
			try {
				listener.onBuildFinished()
			} catch (e: Exception) {
				// Ignore listener exceptions to prevent one bad listener from affecting others
			}
		}
	}

	/**
	 * Internal method to notify listeners of build failure (should be called by Code On the Go's build system)
	 */
	fun notifyBuildFailed(error: String?) {
		this.buildInProgress = false
		buildStatusListeners.forEach { listener ->
			try {
				listener.onBuildFailed(error)
			} catch (e: Exception) {
				// Ignore listener exceptions to prevent one bad listener from affecting others
			}
		}
	}

	private fun notifyBuildStarted() {
		buildStatusListeners.forEach { listener ->
			try {
				listener.onBuildStarted()
			} catch (e: Exception) {
				// Ignore listener exceptions to prevent one bad listener from affecting others
			}
		}
	}

	override fun executeTasks(vararg tasks: String): CompletableFuture<Boolean> =
		executeTasks(tasks.toList(), emptyList()).thenApply { it == GradleTaskResult.Success }

	override fun executeTasks(
		tasks: List<String>,
		arguments: List<String>,
	): CompletableFuture<GradleTaskResult> = startTasks(tasks, arguments).result

	/** Gradle output of the runs [startTasks] has in flight; fed by [onBuildOutput]. */
	private val outputCaptures = CopyOnWriteArraySet<BuildOutputCapture>()

	/**
	 * Called by Code On the Go's build system with each line Gradle prints. Only one build holds
	 * the slot at a time, so every line seen while a run is in flight is that run's.
	 */
	fun onBuildOutput(line: String) {
		outputCaptures.forEach { it.append(line.removeSuffix("\n")) }
	}

	/** Runs [tasks] like [executeTasks], and also gives the caller this run's output and a cancel for it. */
	internal fun startTasks(
		tasks: List<String>,
		arguments: List<String>,
	): GradleTaskRun {
		if (tasks.isEmpty()) return refuse(tasks, "no tasks were given")
		// Tasks are passed as command-line arguments, so "-I x" would be read as an option.
		tasks.firstOrNull { it.isBlank() || it.startsWith("-") }?.let { return refuse(tasks, "'$it' is not a task") }
		val buildService =
			Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
				?: return refuse(tasks, "build service is not registered")
		if (!buildService.isToolingServerStarted()) return refuse(tasks, "tooling server is not started")
		if (buildService.isBuildInProgress) return refuse(tasks, BUILD_IN_PROGRESS_REASON)

		val buildId = buildService.nextBuildId(BuildRunType.TaskRun)
		val message =
			TaskExecutionMessage(
				tasks = tasks,
				buildId = buildId,
				buildParams = GradleBuildParams(gradleArgs = arguments),
			)
		val capture = BuildOutputCapture(MAX_OUTPUT_CHARS)
		outputCaptures += capture
		val result =
			runCatching { buildService.executeTasks(message) }
				.getOrElse { CompletableFuture<TaskExecutionResult>().apply { completeExceptionally(it) } }
				.handle { result, error ->
					outputCaptures -= capture
					toGradleTaskResult(tasks, result, error)
				}
		return ToolingServerRun(result, capture, buildId, buildService)
	}

	private fun toGradleTaskResult(
		tasks: List<String>,
		result: TaskExecutionResult?,
		error: Throwable?,
	): GradleTaskResult {
		if (error != null) {
			log.error("Tasks {} failed", tasks, error)
			val cause = (error as? CompletionException)?.cause ?: error
			return GradleTaskResult.Failed(cause.message ?: cause.javaClass.simpleName)
		}
		return when {
			result == null -> GradleTaskResult.Failed(TaskExecutionResult.Failure.UNKNOWN.name)

			result.isSuccessful -> GradleTaskResult.Success

			result.failure == TaskExecutionResult.Failure.BUILD_CANCELLED -> GradleTaskResult.Cancelled

			// Another build took the slot between the check above and the service's own claim.
			result.failure == TaskExecutionResult.Failure.BUILD_IN_PROGRESS -> GradleTaskResult.Refused(BUILD_IN_PROGRESS_REASON)

			else -> GradleTaskResult.Failed((result.failure ?: TaskExecutionResult.Failure.UNKNOWN).name)
		}
	}

	private fun refuse(
		tasks: List<String>,
		reason: String,
	): GradleTaskRun {
		log.warn("Not executing tasks {}: {}", tasks, reason)
		return RefusedRun(reason)
	}

	private class RefusedRun(
		reason: String,
	) : GradleTaskRun {
		override val result: CompletableFuture<GradleTaskResult> = CompletableFuture.completedFuture(GradleTaskResult.Refused(reason))

		override fun output() = ""

		override fun cancel(): CompletableFuture<Boolean> = CompletableFuture.completedFuture(false)
	}

	private inner class ToolingServerRun(
		override val result: CompletableFuture<GradleTaskResult>,
		private val capture: BuildOutputCapture,
		private val buildId: BuildId,
		private val buildService: BuildService,
	) : GradleTaskRun {
		// A run refused at the slot claim captured the lines of the build that holds it.
		override fun output() = if (result.getNow(null) is GradleTaskResult.Refused) "" else capture.text()

		override fun cancel(): CompletableFuture<Boolean> =
			if (!result.isDone && buildService.currentBuildId == buildId) {
				cancelCurrentBuild(buildService)
			} else {
				CompletableFuture.completedFuture(false)
			}
	}

	override fun cancelBuild(): CompletableFuture<Boolean> {
		val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
		if (buildService == null || !buildService.isToolingServerStarted()) {
			return CompletableFuture.completedFuture(false)
		}
		return cancelCurrentBuild(buildService)
	}

	private fun cancelCurrentBuild(buildService: BuildService): CompletableFuture<Boolean> =
		runCatching { buildService.cancelCurrentBuild() }
			.getOrElse { CompletableFuture<BuildCancellationRequestResult>().apply { completeExceptionally(it) } }
			.handle { result, error ->
				if (error != null) log.error("Failed to cancel the running build", error)
				error == null && result?.wasEnqueued == true
			}

	override fun runApp(callback: BuildAndLaunchCallback) {
		runAppProvider?.invoke(callback)
			?: callback.onComplete(false, "Run app functionality not initialized")
	}

	override fun triggerGradleSync(callback: GradleSyncCallback) {
		gradleSyncProvider?.invoke(callback)
			?: callback.onComplete(false, "Gradle sync functionality not initialized")
	}

	override fun getBuildOutput(): String? = buildOutputProvider?.invoke()

	override fun getTasks(): List<GradleTaskInfo> = tasksOf(IProjectManager.getInstance().gradleBuild)

	/**
	 * Set the run app provider (should be called by Code On the Go's app module during initialization)
	 */
	fun setRunAppProvider(provider: (BuildAndLaunchCallback) -> Unit) {
		this.runAppProvider = provider
	}

	/**
	 * Set the gradle sync provider (should be called by Code On the Go's app module during initialization)
	 */
	fun setGradleSyncProvider(provider: (GradleSyncCallback) -> Unit) {
		this.gradleSyncProvider = provider
	}

	/**
	 * Set the build output provider (should be called by Code On the Go's app module during initialization)
	 */
	fun setBuildOutputProvider(provider: () -> String?) {
		this.buildOutputProvider = provider
	}
}
