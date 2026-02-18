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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Implementation for the Gradle Tooling API server.
 *
 * @author Akash Yadav
 */
internal class ToolingApiServerImpl : IToolingApiServer {
	private var client: IToolingApiClient? = null
	private var connector: GradleConnector? = null
	private var connection: ProjectConnection? = null
	private var lastInitParams: InitializeProjectParams? = null

	@Suppress("ktlint:standard:backing-property-naming")
	private var _buildCancellationToken: CancellationTokenSource? = null

	private val cancellationTokenAccessLock = ReentrantLock(true)
	private var buildCancellationToken: CancellationTokenSource?
		get() = cancellationTokenAccessLock.withLock { _buildCancellationToken }
		set(value) = cancellationTokenAccessLock.withLock { _buildCancellationToken = value }

	/** Whether the project has been initialized or not. */
	var isInitialized: Boolean = false
		private set

	/** Whether a build or project synchronization is in progress. */
	private var isBuildInProgress: Boolean = false

	/** Whether the server has a live connection to Gradle. */
	val isConnected: Boolean
		get() = connector != null || connection != null

	companion object {
		private val log = LoggerFactory.getLogger(ToolingApiServerImpl::class.java)
	}

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
			if (!forceConnect && connector != null && connection != null) {
				return@withStopWatch connector!! to connection!!
			}

			if (forceConnect) {
				connector?.disconnect()
			}

			val connector = GradleConnector.newConnector().forProjectDirectory(projectDir)
			setupConnectorForGradleInstallation(connector, gradleDist)

			val connection = connector.connect()

			this.connector = connector
			this.connection = connection

			connector to connection
		}

	override fun metadata(): CompletableFuture<ToolingServerMetadata> =
		CompletableFuture.supplyAsync {
			ToolingServerMetadata(ProcessHandle.current().pid().toInt())
		}

	override fun initialize(params: InitializeProjectParams): CompletableFuture<InitializeResult> {
		return runBuild {
			val start = System.currentTimeMillis()
			try {
				return@runBuild doInitialize(params)
			} catch (err: Throwable) {
				log.error("Failed to initialize project", err)
				notifyBuildFailure(
					BuildResult(
						tasks = emptyList(),
						buildId = params.buildId,
						durationMs = System.currentTimeMillis() - start,
					),
				)
				return@runBuild InitializeResult.Failure(getTaskFailureType(err))
			}
		}
	}

	@VisibleForTesting
	internal fun doInitialize(params: InitializeProjectParams): InitializeResult {
		log.debug("Received project initialization request with params: {}", params)

		val start = System.currentTimeMillis()
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
		val isReinitializing =
			connector != null && connection != null && params == lastInitParams

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

		if (params.needsGradleSync || !ProjectSyncHelper.areSyncFilesReadable(projectDir)) {
			val cancellationToken = GradleConnector.newCancellationTokenSource()
			buildCancellationToken = cancellationToken

			val buildInfo = BuildInfo(params.buildId, emptyList())
			val clientConfig = client?.prepareBuild(buildInfo)?.get()
			log.debug("doInitialize: got client config: {} (client={})", clientConfig, client)

			val modelBuilderParams =
				RootProjectModelBuilderParams(
					projectConnection = connection,
					cancellationToken = cancellationToken.token(),
					projectCacheFile = cacheFile,
					projectSyncMetaFile = syncMetaFile,
					clientConfig = clientConfig,
				)

			RootModelBuilder.build(params, modelBuilderParams)
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
		return runBuild {
			val start = System.currentTimeMillis()
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
			val clientConfig = client?.prepareBuild(buildInfo)?.get()
			log.debug("executeTasks: got client config: {} (client={})", clientConfig, client)

			// System.in and System.out are used for communication between this server and the
			// client.
			val out = LoggingOutputStream()
			builder.setStandardInput("NoOp".byteInputStream())
			builder.setStandardError(out)
			builder.setStandardOutput(out)
			builder.forTasks(*message.tasks.filter { it.isNotBlank() }.toTypedArray())
			builder.configureFrom(clientConfig, message.buildParams)

			this.buildCancellationToken = GradleConnector.newCancellationTokenSource()
			builder.withCancellationToken(this.buildCancellationToken!!.token())

			try {
				builder.run()
				this.buildCancellationToken = null
				notifyBuildSuccess(
					result =
						BuildResult(
							tasks = message.tasks,
							buildId = message.buildId,
							durationMs = System.currentTimeMillis() - start,
						),
				)
				return@runBuild TaskExecutionResult.SUCCESS
			} catch (error: Throwable) {
				log.error("Failed to run tasks: {}", message.tasks, error)
				notifyBuildFailure(
					result =
						BuildResult(
							tasks = message.tasks,
							buildId = message.buildId,
							durationMs = System.currentTimeMillis() - start,
						),
				)
				return@runBuild TaskExecutionResult(false, getTaskFailureType(error))
			}
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

	private fun notifyBuildFailure(result: BuildResult) {
		client?.onBuildFailed(result)
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

			// wait for connections to close
			connectionCloseFuture.get()

			log.info("Shutdown request completed.")
			null
		}

	private fun getTaskFailureType(error: Throwable): Failure =
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

	private inline fun <T : Any?> runBuild(crossinline action: () -> T): CompletableFuture<T> =
		supplyAsync {
			if (isBuildInProgress) {
				log.error("Cannot run build, build is already in progress!")
				throw IllegalStateException("Build is already in progress")
			}

			isBuildInProgress = true
			try {
				action()
			} finally {
				isBuildInProgress = false
			}
		}

	fun connect(client: IToolingApiClient) {
		this.client = client
	}
}
