
package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.plugins.services.BuildAndLaunchCallback
import com.itsaky.androidide.plugins.services.BuildStatusListener
import com.itsaky.androidide.plugins.services.GradleSyncCallback
import com.itsaky.androidide.plugins.services.GradleTaskResult
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.projects.builder.BuildService
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

		@Volatile
		private var instance: IdeBuildServiceImpl? = null

		fun getInstance(): IdeBuildServiceImpl =
			instance ?: synchronized(this) {
				instance ?: IdeBuildServiceImpl().also { instance = it }
			}
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
	): CompletableFuture<GradleTaskResult> {
		if (tasks.isEmpty()) return refuse(tasks, "no tasks were given")
		val buildService =
			Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
				?: return refuse(tasks, "build service is not registered")
		if (!buildService.isToolingServerStarted()) return refuse(tasks, "tooling server is not started")
		if (buildService.isBuildInProgress) return refuse(tasks, "another build is in progress")

		val message =
			TaskExecutionMessage(
				tasks = tasks,
				buildId = buildService.nextBuildId(BuildRunType.TaskRun),
				buildParams = GradleBuildParams(gradleArgs = arguments),
			)
		return runCatching { buildService.executeTasks(message) }
			.getOrElse { CompletableFuture<TaskExecutionResult>().apply { completeExceptionally(it) } }
			.handle { result, error -> toGradleTaskResult(tasks, result, error) }
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
			else -> GradleTaskResult.Failed((result.failure ?: TaskExecutionResult.Failure.UNKNOWN).name)
		}
	}

	private fun refuse(
		tasks: List<String>,
		reason: String,
	): CompletableFuture<GradleTaskResult> {
		log.warn("Not executing tasks {}: {}", tasks, reason)
		return CompletableFuture.completedFuture(GradleTaskResult.Refused(reason))
	}

	override fun cancelBuild(): CompletableFuture<Boolean> {
		val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
		if (buildService == null || !buildService.isToolingServerStarted()) {
			return CompletableFuture.completedFuture(false)
		}
		return runCatching { buildService.cancelCurrentBuild() }
			.getOrElse { CompletableFuture<BuildCancellationRequestResult>().apply { completeExceptionally(it) } }
			.handle { result, error ->
				if (error != null) log.error("Failed to cancel the running build", error)
				error == null && result?.wasEnqueued == true
			}
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
