/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.tooling.impl

import com.itsaky.androidide.tooling.api.IToolingApiClient
import com.itsaky.androidide.tooling.api.IToolingApiServer
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.ClientGradleBuildConfig
import com.itsaky.androidide.tooling.api.messages.GradleDistributionParams
import com.itsaky.androidide.tooling.api.messages.GradleDistributionType
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.messages.result.BuildCancellationRequestResult
import com.itsaky.androidide.tooling.api.messages.result.BuildCancellationRequestResult.Reason.CANCELLATION_ERROR
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
import com.itsaky.androidide.tooling.api.messages.result.BuildResult
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.BUILD_CANCELLED
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.BUILD_FAILED
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.CONNECTION_CLOSED
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.CONNECTION_ERROR
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_DIRECTORY_INACCESSIBLE
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_NOT_DIRECTORY
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_NOT_FOUND
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_NOT_INITIALIZED
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.UNKNOWN
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.UNSUPPORTED_BUILD_ARGUMENT
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.UNSUPPORTED_CONFIGURATION
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.UNSUPPORTED_GRADLE_VERSION
import com.itsaky.androidide.tooling.api.models.ToolingServerMetadata
import com.itsaky.androidide.tooling.api.sync.ProjectSyncHelper
import com.itsaky.androidide.tooling.impl.sync.RootModelBuilder
import com.itsaky.androidide.tooling.impl.sync.RootProjectModelBuilderParams
import com.itsaky.androidide.tooling.impl.util.configureFrom
import com.itsaky.androidide.utils.StopWatch
import com.itsaky.androidide.utils.withStopWatch
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.BuildException
import org.gradle.tooling.CancellationTokenSource
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.UnsupportedVersionException
import org.gradle.tooling.exceptions.UnsupportedBuildArgumentException
import org.gradle.tooling.exceptions.UnsupportedOperationConfigurationException
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import org.jetbrains.annotations.VisibleForTesting
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Implementation for the Gradle Tooling API server.
 *
 * @author Akash Yadav
 */
internal class ToolingApiServerImpl(
	private val newDaemonWatcher: (onStarted: (Int) -> Unit, onExited: (Int) -> Unit) -> GradleDaemonWatcher =
		{ onStarted, onExited -> GradleDaemonWatcher(onStarted = onStarted, onExited = onExited) },
) : IToolingApiServer {
	private var client: IToolingApiClient? = null

	// Volatile because the reuse decision crosses threads: each initialize() runs on whichever
	// commonPool worker CompletableFuture.supplyAsync hands it, and nothing else in this class
	// establishes a happens-before edge between one call's writes and the next call's reads. A
	// stale null read here reintroduces ADFA-5589 intermittently.
	@Volatile
	private var connector: GradleConnector? = null

	@Volatile
	private var connection: ProjectConnection? = null

	@Volatile
	@VisibleForTesting
	internal var lastInitParams: InitializeProjectParams? = null

	/** The `distributionUrl` the wrapper named when [connection] was opened; null if not a wrapper. */
	@Volatile
	private var lastWrapperDistribution: String? = null

	@Suppress("ktlint:standard:backing-property-naming")
	private var _buildCancellationToken: CancellationTokenSource? = null

	private val cancellationTokenAccessLock = ReentrantLock(true)
	private var buildCancellationToken: CancellationTokenSource?
		get() = cancellationTokenAccessLock.withLock { _buildCancellationToken }
		set(value) = cancellationTokenAccessLock.withLock { _buildCancellationToken = value }

	/** Whether the project has been initialized or not. */
	@Volatile
	var isInitialized: Boolean = false
		private set

	/**
	 * Whether a build or project synchronization is in progress, as a CAS rather than a
	 * read-then-write.
	 *
	 * Same reason the fields above are @Volatile: every call runs on whichever commonPool worker
	 * CompletableFuture.supplyAsync hands it. As a plain Boolean, two concurrent executeTasks calls
	 * could both read false and both proceed -- two builds against one connection and one
	 * cancellation token -- and a worker could go on seeing a stale true and refuse every build for
	 * the life of the server. ([buildCancellationToken] needs nothing here; it already reads and
	 * writes under its own lock.)
	 */
	private val buildInProgress = AtomicBoolean(false)

	/** Whether the server has a live connection to Gradle. */
	val isConnected: Boolean
		get() = connector != null || connection != null

	companion object {
		private const val WRAPPER_PROPERTIES = "gradle/wrapper/gradle-wrapper.properties"

		private val log = LoggerFactory.getLogger(ToolingApiServerImpl::class.java)

		/**
		 * Whether [a] and [b] name the same connection: the same project directory, served by the
		 * same Gradle distribution. A connector is bound to those two and nothing else; every other
		 * field of [InitializeProjectParams] is per-call.
		 *
		 * `a == b` cannot stand in for this. [InitializeProjectParams] declares no `equals`, so that
		 * is reference equality between objects that arrive freshly deserialized on every call, and
		 * it answered false every time (ADFA-5589).
		 */
		@VisibleForTesting
		internal fun describesSameConnection(
			a: InitializeProjectParams?,
			b: InitializeProjectParams,
		): Boolean =
			a != null &&
				sameDirectory(a.directory, b.directory) &&
				a.gradleDistribution == b.gradleDistribution

		/**
		 * Whether [a] and [b] name the same directory.
		 *
		 * As paths, not as strings: `GradleConnector.forProjectDirectory` takes a [File], so a
		 * trailing separator -- or `/sdcard` against `/storage/emulated/0`, both live on Android --
		 * is one connector but two strings. Guessing wrong here only costs a needless reconnect.
		 */
		private fun sameDirectory(
			a: String,
			b: String,
		): Boolean {
			val first = File(a)
			val second = File(b)
			return first == second ||
				runCatching { first.canonicalFile == second.canonicalFile }.getOrDefault(false)
		}

		/**
		 * Whether a wrapper connection is still bound to the distribution the wrapper names.
		 *
		 * [GradleDistributionParams.WRAPPER] carries no version, so two wrapper params compare equal
		 * across a wrapper upgrade. The distribution is resolved from `gradle-wrapper.properties`
		 * inside `connect()` and frozen into the connection, and [Main.checkGradleWrapper] can
		 * rewrite that file earlier in this same initialize -- so without this the initialize that
		 * installs a new wrapper is exactly the one that reuses the connection bound to the old one.
		 */
		@VisibleForTesting
		internal fun wrapperStillMatches(
			params: InitializeProjectParams,
			recorded: String?,
			current: String? = wrapperDistributionUrl(params.directory),
		): Boolean =
			params.gradleDistribution.type != GradleDistributionType.GRADLE_WRAPPER ||
				recorded == current

		/** The `distributionUrl` named by [directory]'s Gradle wrapper, or null if unreadable. */
		@VisibleForTesting
		internal fun wrapperDistributionUrl(directory: String): String? =
			runCatching {
				File(directory, WRAPPER_PROPERTIES)
					.takeIf(File::isFile)
					?.inputStream()
					?.use { stream -> Properties().apply { load(stream) } }
					?.getProperty("distributionUrl")
			}.getOrNull()
	}

	/**
	 * Whether the connector already open can serve [params] without reconnecting.
	 *
	 * Not merely a slow path when false: reconnecting disconnects the open connector, and
	 * `GradleConnector.disconnect()` sends the running daemon `StopWhenIdle` (ADFA-5589).
	 */
	@VisibleForTesting
	internal fun canReuseConnector(params: InitializeProjectParams): Boolean =
		connector != null &&
			connection != null &&
			describesSameConnection(lastInitParams, params) &&
			wrapperStillMatches(params, lastWrapperDistribution)

	@VisibleForTesting
	internal fun getOrConnectProject(
		projectDir: File,
		forceConnect: Boolean = false,
		initParams: InitializeProjectParams? = null,
		gradleDist: GradleDistributionParams =
			initParams?.gradleDistribution
				?: GradleDistributionParams.WRAPPER,
	): Pair<GradleConnector, ProjectConnection> =
		withStopWatch("getOrConnectProject") {
			// Each field read once into a local. Four separate reads of two fields could pass the
			// null checks and then throw on the !!: the reconnect below nulls both before it
			// connects, so unlike before this fix they are null mid-flight on every reconnect, not
			// only at shutdown.
			val openConnector = connector
			val openConnection = connection
			if (!forceConnect && openConnector != null && openConnection != null) {
				return@withStopWatch openConnector to openConnection
			}

			if (forceConnect) {
				// The local, not the field. Re-reading it here is what the locals above exist to
				// avoid: a concurrent reconnect can install a new connector between the two reads,
				// and this would then disconnect that one and drop the one it meant to close.
				openConnector?.disconnect()
			}

			// Dropped before the connect, not after it. A connect that throws -- a bad installation
			// directory, an unreachable distribution -- would otherwise leave the disconnected pair in
			// place, and the next initialize whose params match would reuse a dead connection and fail
			// every build with CONNECTION_CLOSED until the server process restarts.
			this.connector = null
			this.connection = null
			this.lastWrapperDistribution = null

			val connector = GradleConnector.newConnector().forProjectDirectory(projectDir)
			setupConnectorForGradleInstallation(connector, gradleDist)

			val connection = connector.connect()

			this.connector = connector
			this.connection = connection
			this.lastWrapperDistribution =
				if (gradleDist.type == GradleDistributionType.GRADLE_WRAPPER) {
					wrapperDistributionUrl(projectDir.path)
				} else {
					null
				}

			connector to connection
		}

	override fun metadata(): CompletableFuture<ToolingServerMetadata> =
		CompletableFuture.supplyAsync {
			ToolingServerMetadata(ProcessHandle.current().pid().toInt())
		}

	override fun initialize(params: InitializeProjectParams): CompletableFuture<InitializeResult> {
		val start = System.currentTimeMillis()
		return runBuild(
			onFailure = { err ->
				log.error("Failed to initialize project", err)
				InitializeResult.Failure(
					notifyBuildFailure(params.buildId, emptyList(), start, err),
				)
			},
		) {
			doInitialize(params, start)
		}
	}

	@VisibleForTesting
	internal fun doInitialize(
		params: InitializeProjectParams,
		start: Long,
	): InitializeResult {
		log.debug("Received project initialization request with params: {}", params)

		if (params.gradleDistribution.type == GradleDistributionType.GRADLE_WRAPPER) {
			Main.checkGradleWrapper()
		}

		if (buildCancellationToken != null) {
			cancelCurrentBuild().get()
		}

		val projectDir = File(params.directory)
		val failureReason = validateProjectDirectory(projectDir)

		if (failureReason != null) {
			log.error("Cannot initialize project: {}", failureReason)
			return InitializeResult.Failure(failureReason)
		}

		val stopWatch = StopWatch("Connection to project")
		val isReinitializing = canReuseConnector(params)

		if (isReinitializing) {
			log.info("Project is being reinitialized")
			log.info("Reusing connector instance...")
		}

		val (_, connection) =
			getOrConnectProject(
				projectDir = projectDir,
				forceConnect = !isReinitializing,
				initParams = params,
			)

		lastInitParams = params

		// we're now ready to run Gradle tasks
		isInitialized = true

		val cacheFile = ProjectSyncHelper.cacheFileForProject(projectDir)
		val syncMetaFile = ProjectSyncHelper.syncMetaFileForProject(projectDir)

		if (params.needsGradleSync ||
			!ProjectSyncHelper.areSyncFilesReadable(projectDir) ||
			!ProjectSyncHelper.isSyncMetaVersionCurrent(syncMetaFile)
		) {
			val cancellationToken = GradleConnector.newCancellationTokenSource()
			buildCancellationToken = cancellationToken

			val buildInfo = BuildInfo(params.buildId, emptyList())
			val clientConfig = doPrepareBuild(buildInfo)

			val modelBuilderParams =
				RootProjectModelBuilderParams(
					projectConnection = connection,
					cancellationToken = cancellationToken.token(),
					projectCacheFile = cacheFile,
					projectSyncMetaFile = syncMetaFile,
					clientConfig = clientConfig,
				)

			try {
				RootModelBuilder.build(params, modelBuilderParams)
			} finally {
				// The sync path never cleared this on any outcome -- only shutdown() and an actual
				// Stop did. So after every sync a token for a finished build sat here: the next
				// Stop cancelled that dead source and answered wasEnqueued = true with no build
				// running, and the check at the top of this method cancelled it again on the next
				// initialize. The sibling of the same omission in executeTasks.
				buildCancellationToken = null
			}

			notifyBuildSuccess(
				BuildResult(
					tasks = emptyList(),
					buildId = params.buildId,
					durationMs = System.currentTimeMillis() - start,
				),
			)
		}

		stopWatch.log()
		return InitializeResult.Success(cacheFile)
	}

	private fun doPrepareBuild(buildInfo: BuildInfo): ClientGradleBuildConfig? {
		val clientConfig =
			runCatching {
				client?.prepareBuild(buildInfo)?.get(30, TimeUnit.SECONDS)
			}.onFailure { err ->
				log.error("An error occurred while preparing build", err)
				if (err is InterruptedException) {
					Thread.currentThread().interrupt()
				}
			}.getOrDefault(null)

		log.debug("got client config: {} (client={})", clientConfig, client)

		return clientConfig
	}

	@VisibleForTesting
	internal fun validateProjectDirectory(projectDirectory: File) =
		when {
			!projectDirectory.exists() -> PROJECT_NOT_FOUND
			!projectDirectory.isDirectory -> PROJECT_NOT_DIRECTORY
			!projectDirectory.canRead() -> PROJECT_DIRECTORY_INACCESSIBLE
			else -> null
		}

	override fun isServerInitialized(): CompletableFuture<Boolean> = CompletableFuture.supplyAsync { isInitialized }

	override fun executeTasks(message: TaskExecutionMessage): CompletableFuture<TaskExecutionResult> {
		val start = System.currentTimeMillis()
		return runBuild(
			onFailure = { error ->
				log.error("Failed to run tasks: {}", message.tasks, error)
				TaskExecutionResult(false, notifyBuildFailure(message.buildId, message.tasks, start, error))
			},
		) {
			if (!isServerInitialized().get()) {
				log.error("Cannot execute tasks: {}", PROJECT_NOT_INITIALIZED)
				return@runBuild TaskExecutionResult(false, PROJECT_NOT_INITIALIZED)
			}

			val lastInitParams = this.lastInitParams
			if (lastInitParams != null) {
				val projectDirectory = File(lastInitParams.directory)
				val failureReason = validateProjectDirectory(projectDirectory)
				if (failureReason != null) {
					log.error("Cannot execute tasks: {}", failureReason)
					return@runBuild TaskExecutionResult(isSuccessful = false, failureReason)
				}
			}

			log.debug("Received request to run tasks: {}", message)

			Main.checkGradleWrapper()

			val connection =
				checkNotNull(this.connection) {
					"ProjectConnection has not been initialized. Cannot execute tasks."
				}

			val builder = connection.newBuild()

			val buildInfo = BuildInfo(message.buildId, message.tasks)
			val clientConfig = doPrepareBuild(buildInfo)

			// System.in and System.out are used for communication between this server and the
			// client.
			val out = LoggingOutputStream()
			builder.setStandardInput("NoOp".byteInputStream())
			builder.setStandardError(out)
			builder.setStandardOutput(out)
			// Tasks go in the arguments, not forTasks, so a task option in buildParams binds to them.
			builder.configureFrom(clientConfig, message.buildParams, message.tasks)

			this.buildCancellationToken = GradleConnector.newCancellationTokenSource()
			builder.withCancellationToken(this.buildCancellationToken!!.token())

			try {
				builder.run()
			} finally {
				// On both paths. Only the success path cleared it, so every failed build left a
				// token behind for a source that was already finished. The next Stop then
				// cancelled that dead source and answered wasEnqueued = true while the live build
				// ran on, and [initialize] -- which cancels first whenever one is set -- paid for
				// a build that had ended long before.
				this.buildCancellationToken = null
			}

			notifyBuildSuccess(
				result =
					BuildResult(
						tasks = message.tasks,
						buildId = message.buildId,
						durationMs = System.currentTimeMillis() - start,
					),
			)
			TaskExecutionResult.SUCCESS
		}
	}

	private fun setupConnectorForGradleInstallation(
		connector: GradleConnector,
		params: GradleDistributionParams,
	) {
		when (params.type) {
			GradleDistributionType.GRADLE_WRAPPER -> {
				log.info("Using Gradle wrapper for build...")
			}

			GradleDistributionType.GRADLE_INSTALLATION -> {
				val file = File(params.value)
				if (!file.exists() || !file.isDirectory) {
					log.error("Specified Gradle installation does not exist: {}", params)
					return
				}

				log.info("Using Gradle installation: {}", file.canonicalPath)
				connector.useInstallation(file)
			}

			GradleDistributionType.GRADLE_VERSION -> {
				log.info("Using Gradle version '{}'", params.value)
				connector.useGradleVersion(params.value)
			}
		}
	}

	/**
	 * Finds the Gradle daemon and reports it to the client, so the memory chart can plot the process
	 * that actually holds the build's heap (ADFA-5514).
	 */
	private val lazyDaemonWatcher =
		lazy {
			newDaemonWatcher(
				{ pid -> client?.onGradleDaemonStarted(pid) },
				{ pid -> client?.onGradleDaemonExited(pid) },
			)
		}

	private val daemonWatcher by lazyDaemonWatcher

	/**
	 * Serialises constructing the watcher against stopping it.
	 *
	 * `shutdown` and `executeTasks` arrive as separate requests and run their bodies on the common
	 * pool, so a build submitted just before a shutdown can reach [startDaemonWatch] after shutdown
	 * has already asked whether a watcher exists. Without this, that build constructs a watcher, and
	 * its scheduler, that nothing will ever stop -- the leak this class's shutdown call exists to
	 * prevent, arriving by the one route the `isInitialized` check cannot see.
	 */
	private val daemonWatcherLock = Any()

	/** Guarded by [daemonWatcherLock]. */
	private var isDaemonWatcherShutdown = false

	/**
	 * Starts a daemon search for a build that is beginning, unless the server is shutting down.
	 *
	 * Only the construction is locked. `onBuildStarted` runs outside it, so a watcher stopped in
	 * between hits the rejection its own guard already handles rather than blocking a build thread.
	 */
	private fun startDaemonWatch() {
		val watcher =
			synchronized(daemonWatcherLock) {
				if (isDaemonWatcherShutdown) {
					return
				}
				daemonWatcher
			}
		watcher.onBuildStarted()
	}

	/**
	 * Tells the client a build failed, and answers with why.
	 *
	 * Both in one call on purpose. The classification and the notification used to be written
	 * separately at each failure site, which is how the notified [BuildResult] came to carry
	 * everything except the answer while the caller of the request got it (ADFA-5542). A site
	 * cannot now report a failure without saying which, or say one thing to the client and another
	 * to its caller.
	 */
	private fun notifyBuildFailure(
		buildId: BuildId,
		tasks: List<String>,
		startedAtMillis: Long,
		error: Throwable,
	): Failure {
		val failure = getTaskFailureType(error)
		client?.onBuildFailed(
			BuildResult(
				buildId = buildId,
				tasks = tasks,
				durationMs = System.currentTimeMillis() - startedAtMillis,
				failure = failure,
			),
		)
		return failure
	}

	private fun notifyBuildSuccess(result: BuildResult) {
		client?.onBuildSuccessful(result)
	}

	override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> {
		return CompletableFuture.supplyAsync {
			if (this.buildCancellationToken == null) {
				return@supplyAsync BuildCancellationRequestResult(
					wasEnqueued = false,
					failureReason = BuildCancellationRequestResult.Reason.NO_RUNNING_BUILD,
				)
			}

			try {
				this.buildCancellationToken!!.cancel()
				this.buildCancellationToken = null
			} catch (e: Exception) {
				val failureReason = CANCELLATION_ERROR
				failureReason.message = "${failureReason.message}: ${e.message}"
				return@supplyAsync BuildCancellationRequestResult(false, failureReason)
			}

			return@supplyAsync BuildCancellationRequestResult(true, null)
		}
	}

	override fun shutdown(): CompletableFuture<Void> =
		CompletableFuture.supplyAsync {
			log.info("Shutting down Tooling API Server...")

			// cancel running build, if any
			log.info("Cancelling running builds...")
			buildCancellationToken?.cancel()
			buildCancellationToken = null

			// Early, and deliberately not "late enough to report the daemon's exit".
			//
			// The leaked thread is the defect: unstopped, an in-flight poll chain goes on scanning
			// ProcessHandle.descendants() for up to a minute after the server is gone. Stopping it
			// here ends that at once, and means no later poll can report a daemon into an RPC
			// channel that is being torn down.
			//
			// Delivering the shutdown-time exit was tried and does not work. That report arrives
			// through handle.onExit().thenRun { scheduler.execute { ... } }, and onExit completes
			// on a process-reaper thread only once the OS has reaped the daemon -- strictly after
			// DefaultGradleConnector.close() returns. There is no point in this sequence where the
			// scheduler is still accepting work *and* the daemon has already been reaped, so the
			// report is not deliverable at shutdown whatever the ordering; keeping `client` alive
			// for it only widens the window in which a half-torn-down channel can be written to.
			// The client learns the daemon is gone when it reconnects, not from here.
			//
			// Through the lazy delegate rather than the property: touching the property would
			// construct a watcher, and its scheduler, only to shut it down again on a server that
			// never ran a build. The flag closes the other half of that: a build that reaches
			// startDaemonWatch after this point must not build one either. See daemonWatcherLock.
			val watcher =
				synchronized(daemonWatcherLock) {
					isDaemonWatcherShutdown = true
					if (lazyDaemonWatcher.isInitialized()) daemonWatcher else null
				}
			if (watcher != null) {
				log.info("Stopping the Gradle daemon watcher...")
				runCatching { watcher.shutdown() }
					.onFailure { log.warn("Could not stop the Gradle daemon watcher", it) }
			}

			val connection = this.connection
			val connector = this.connector
			this.connection = null
			this.connector = null

			// close connections asynchronously
			val connectionCloseFuture =
				CompletableFuture.runAsync {
					log.info("Closing connections...")
					connection?.close()
					connector?.disconnect()

					// Stop all daemons
					log.info("Stopping all Gradle Daemons...")
					DefaultGradleConnector.close()
				}

			// update the initialization flag before cancelling future
			this.isInitialized = false

			// cancelling this future will finish the Tooling API server process
			// see com.itsaky.androidide.tooling.impl.Main.main(String[])
			log.info("Cancelling awaiting future...")
			Main.future?.cancel(true)

			this.client = null
			this.buildCancellationToken = null
			this.lastInitParams = null
			this.lastWrapperDistribution = null

			// wait for connections to close
			connectionCloseFuture.get()

			log.info("Shutdown request completed.")
			null
		}

	/** Classifies [error]. Pure: it reads nothing and changes nothing. */
	@VisibleForTesting
	internal fun getTaskFailureType(error: Throwable): Failure =
		when (error) {
			is BuildException -> BUILD_FAILED
			is BuildCancelledException -> BUILD_CANCELLED
			is UnsupportedOperationConfigurationException -> UNSUPPORTED_CONFIGURATION
			is UnsupportedVersionException -> UNSUPPORTED_GRADLE_VERSION
			is UnsupportedBuildArgumentException -> UNSUPPORTED_BUILD_ARGUMENT
			is GradleConnectionException -> CONNECTION_ERROR
			is java.lang.IllegalStateException -> CONNECTION_CLOSED
			else -> UNKNOWN
		}

	private inline fun <T : Any?> supplyAsync(crossinline action: () -> T): CompletableFuture<T> =
		CompletableFuture.supplyAsync {
			action()
		}

	/**
	 * Runs [action] as the one build in progress, turning anything it throws into [onFailure]'s
	 * result rather than an exceptionally-completed future.
	 *
	 * The catch spans the whole action deliberately. Each caller used to guard only the part that
	 * talks to Gradle, so everything before it -- resolving a connection, checking the wrapper,
	 * preparing the build -- escaped as a raw exception, and the client got an ExecutionException
	 * where it expected a classified failure. That is one bug this file has now had twice, in two
	 * different statements on the same line, because the fix each time moved the throw instead of
	 * covering it.
	 *
	 * "Build already in progress" is left to throw: it is a caller error rather than a build
	 * outcome, and it is raised before this takes ownership of the flag.
	 */
	private inline fun <T : Any?> runBuild(
		crossinline onFailure: (Throwable) -> T,
		crossinline action: () -> T,
	): CompletableFuture<T> =
		supplyAsync {
			if (!buildInProgress.compareAndSet(false, true)) {
				log.error("Cannot run build, build is already in progress!")
				throw IllegalStateException("Build is already in progress")
			}

			startDaemonWatch()
			try {
				action()
			} catch (error: Throwable) {
				onFailure(error)
			} finally {
				buildInProgress.set(false)
			}
		}

	fun connect(client: IToolingApiClient) {
		this.client = client
	}
}
