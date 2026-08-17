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
package com.itsaky.androidide.services.builder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.itsaky.androidide.BuildConfig
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.analytics.gradle.BuildCompletedMetric
import com.itsaky.androidide.analytics.gradle.BuildStartedMetric
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.eventbus.events.BuildCompletedEvent
import com.itsaky.androidide.eventbus.events.BuildStartedEvent
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.lsp.java.debug.JdwpOptions
import com.itsaky.androidide.managers.ToolsManager
import com.itsaky.androidide.preferences.internal.BuildPreferences
import com.itsaky.androidide.preferences.internal.DevOpsPreferences
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.services.ToolingServerNotStartedException
import com.itsaky.androidide.services.builder.ToolingServerRunner.OnServerStartListener
import com.itsaky.androidide.tasks.ifCancelledOrInterrupted
import com.itsaky.androidide.tasks.runOnUiThread
import com.itsaky.androidide.tooling.api.ForwardingToolingApiClient
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_JDWP_ENABLED
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_LOG_SENDER_AAR
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_LOG_SENDER_ENABLED
import com.itsaky.androidide.tooling.api.IToolingApiClient
import com.itsaky.androidide.tooling.api.IToolingApiServer
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.BuildRunType
import com.itsaky.androidide.tooling.api.messages.ClientGradleBuildConfig
import com.itsaky.androidide.tooling.api.messages.GradleBuildParams
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.LogMessageParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.messages.result.BuildCancellationRequestResult
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
import com.itsaky.androidide.tooling.api.messages.result.BuildResult
import com.itsaky.androidide.tooling.api.messages.result.GradleWrapperCheckResult
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.models.ToolingServerMetadata
import com.itsaky.androidide.tooling.events.ProgressEvent
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.FeatureFlags
import com.itsaky.androidide.utils.ResourceUtils
import com.itsaky.androidide.utils.ZipUtils
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.koin.android.ext.android.inject
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Objects
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * A foreground service that handles interaction with the Gradle Tooling
 * API.
 *
 * @author Akash Yadav
 */
class GradleBuildService :
	Service(),
	BuildService,
	IToolingApiClient,
	ToolingServerRunner.Observer {
	private var mBinder: GradleServiceBinder? = null
	private var isToolingServerStarted = false

	// Volatile: written on the Tooling API's CompletableFuture pool, read cross-thread
	// by Quick Build's slot pre-check.
	@Volatile
	override var isBuildInProgress = false
		private set

	/**
	 * Gradle output captured while the editor's listener is suppressed, oldest line first. Bounded
	 * by [MAX_INTERNAL_OUTPUT_LINES]; guarded by itself, since it is written from the tooling
	 * API's thread and drained from the caller's.
	 */
	private val internalBuildOutput = ArrayDeque<String>()

	/**
	 * Whether an INTERNAL build is running - a build the user never asked for that goes through the
	 * same [executeTasks] path as a Standard Run, today Quick Build's proxy app build.
	 *
	 * Held only through [withInternalBuild]; see [InternalBuildBracket] for why a leaked acquire
	 * strands the toolbar on the Cancel-build label.
	 */
	private val internalBuild =
		InternalBuildBracket(
			// Outermost internal build: drop any tail a previous one left unread, so a failure
			// report quotes this build and not the last one.
			onFirstAcquire = { synchronized(internalBuildOutput) { internalBuildOutput.clear() } },
			// postValue, not setValue: the bracket releases on the tooling API's thread.
			onHeldChanged = { held -> _internalBuildInProgress.postValue(held) },
		)

	private val _internalBuildInProgress = MutableLiveData(false)

	/**
	 * Whether an internal build is running, for surfaces that show "a build is running" without
	 * offering to cancel it - the user cannot cancel a build they never started.
	 */
	val internalBuildInProgress: LiveData<Boolean>
		get() = _internalBuildInProgress

	/** [internalBuildInProgress] read synchronously, for a surface syncing its own state. */
	val isInternalBuildInProgress: Boolean
		get() = internalBuild.isHeld

	/**
	 * The raw flag says the Gradle slot is busy; this one says the USER has a build running.
	 * Every UI decider reads this; every concurrency guard keeps reading the raw flag.
	 */
	override val isUserVisibleBuildInProgress: Boolean
		get() = isBuildInProgress && !internalBuild.isHeld

	/**
	 * Notified of every Gradle output line while the editor's listener is suppressed, or null when
	 * nobody is watching.
	 *
	 * Suppression exists to keep the proxy app's build out of the EDITOR's build UI - the modal
	 * first-build notice, the auto-opened output sheet, the Run button relabelled to "Cancel
	 * build" - not to make a 90-second build look like a hang. A listener here gets the lines
	 * without any of that UI coming with them.
	 *
	 * Volatile: written from the main thread, read on the tooling API's thread.
	 */
	@Volatile
	private var internalBuildProgress: ((String) -> Unit)? = null

	/**
	 * Runs [block] as an INTERNAL build: the editor's build listener is suppressed for its duration
	 * and [progressListener] gets the output lines instead.
	 *
	 * There is no separate begin/end pair on purpose - a caller cannot separate the acquire from
	 * its release, so no early return, throw or cancellation can strand the editor's build UI with
	 * the Run button reading "Cancel build".
	 *
	 * @param progressListener called per output line on the tooling API's thread, so it must be
	 *   cheap and non-blocking; a throwing listener is logged and dropped, and it is cleared
	 *   however [block] returns.
	 * @return whatever [block] returns.
	 */
	suspend fun <T> withInternalBuild(
		progressListener: ((String) -> Unit)? = null,
		block: suspend () -> T,
	): T =
		internalBuild.hold {
			internalBuildProgress = progressListener
			try {
				block()
			} finally {
				internalBuildProgress = null
			}
		}

	/**
	 * The editor's build listener, or null while an internal build is running. Every dispatch
	 * to [eventListener] goes through here: keying off the BUILD would need per-build
	 * identity, which [logOutput] and [onProgressEvent] simply do not carry.
	 *
	 * Only the LISTENER is suppressed. Analytics, the EventBus build events and the indexing
	 * hand-off still fire for internal builds - they are not user-visible surfaces, and
	 * consumers (e.g. the Kotlin language server) want them.
	 */
	private fun editorListener(): EventListener? = internalBuild.suppressWhileHeld(eventListener)

	/**
	 * We do not provide direct access to GradleBuildService instance to the
	 * Tooling API launcher as it may cause memory leaks. Instead, we create
	 * another client object which forwards all calls to us. So, when the
	 * service is destroyed, we release the reference to the service from this
	 * client.
	 */
	private var toolingApiClient: ForwardingToolingApiClient? = null
	private var toolingServerRunner: ToolingServerRunner? = null
	private var outputReaderJob: Job? = null
	private var notificationManager: NotificationManager? = null
	private var server: IToolingApiServer? = null
	private var eventListener: EventListener? = null
	private val analyticsManager: IAnalyticsManager by inject()

	private val buildSessionId = UUID.randomUUID().toString()
	private val buildId = AtomicLong(0)

	@Volatile
	private var tuningConfig: GradleTuningConfig? = null

	private val buildServiceScope =
		CoroutineScope(
			Dispatchers.Default + CoroutineName("GradleBuildService"),
		)

	private val isGradleWrapperAvailable: Boolean
		get() {
			val projectManager = ProjectManagerImpl.getInstance()
			val projectDir = projectManager.projectDirPath
			if (TextUtils.isEmpty(projectDir)) {
				return false
			}

			val projectRoot = Objects.requireNonNull(projectManager.projectDir)
			if (!projectRoot.exists()) {
				return false
			}

			val gradlew = File(projectRoot, "gradlew")
			val gradleWrapperJar = File(projectRoot, "gradle/wrapper/gradle-wrapper.jar")
			val gradleWrapperProps = File(projectRoot, "gradle/wrapper/gradle-wrapper.properties")
			return gradlew.exists() && gradleWrapperJar.exists() && gradleWrapperProps.exists()
		}

	private fun getBuildType(tasks: List<String>): String =
		tasks.firstOrNull()?.let { task ->
			when {
				task.contains("assembleDebug") -> "debug"
				task.contains("assembleRelease") -> "release"
				task.contains("clean") -> "clean"
				task.contains("build") -> "build"
				else -> "custom"
			}
		} ?: "unknown"

	override fun nextBuildId(runType: BuildRunType): BuildId =
		BuildId(
			buildSessionId = buildSessionId,
			buildId = buildId.incrementAndGet(),
			runType = runType,
		)

	companion object {
		private val log = LoggerFactory.getLogger(GradleBuildService::class.java)
		private val NOTIFICATION_ID = R.string.app_name
		private val SERVER_System_err = LoggerFactory.getLogger("ToolingApiErrorStream")

		/**
		 * How much of a suppressed internal build's output to keep for a failure report. Gradle
		 * puts the cause at the END of the stream, so a tail is the right shape; deep enough to
		 * hold the whole `FAILURE:` block after the configure chatter.
		 */
		private const val MAX_INTERNAL_OUTPUT_LINES = 200

		private const val ERROR_GRADLE_ENTERPRISE_PLUGIN = "gradle-enterprise-gradle-plugin"
		private const val ERROR_COULD_NOT_FIND_GRADLE = "Could not find com.gradle"

		private const val MESSAGE_SCAN_REQUIRES_PLUGIN =
			"The --scan option requires the Gradle Enterprise plugin."
		private const val MESSAGE_OPTION_DISABLED = "This option has been disabled."
		private const val MESSAGE_EXCEPTION_SCAN_DISABLED =
			"Disabled --scan option due to missing Gradle Enterprise plugin"
	}

	override fun onCreate() {
		notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
		showNotification(getString(R.string.build_status_idle), false)
		Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, this)
	}

	override fun isToolingServerStarted(): Boolean = isToolingServerStarted && server != null

	private fun showNotification(
		message: String,
		@Suppress("SameParameterValue") isProgress: Boolean,
	) {
		log.info("Showing notification to user...")
		createNotificationChannels()
		startForeground(NOTIFICATION_ID, buildNotification(message, isProgress))
	}

	private fun createNotificationChannels() {
		val buildNotificationChannel =
			NotificationChannel(
				BaseApplication.NOTIFICATION_GRADLE_BUILD_SERVICE,
				getString(R.string.title_gradle_service_notification_channel),
				NotificationManager.IMPORTANCE_LOW,
			)
		NotificationManagerCompat
			.from(this)
			.createNotificationChannel(buildNotificationChannel)
	}

	private fun buildNotification(
		message: String,
		isProgress: Boolean,
	): Notification {
		val ticker = getString(R.string.title_gradle_service_notification_ticker)
		val title = getString(R.string.title_gradle_service_notification)
		val launch = packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
		val intent = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT)
		val builder =
			Notification
				.Builder(this, BaseApplication.NOTIFICATION_GRADLE_BUILD_SERVICE)
				.setSmallIcon(R.drawable.ic_cogo_notification)
				.setTicker(ticker)
				.setWhen(System.currentTimeMillis())
				.setContentTitle(title)
				.setContentText(message)
				.setContentIntent(intent)

		if (isProgress) {
			builder.setProgress(100, 0, true)
		}
		return builder.build()
	}

	override fun onStartCommand(
		intent: Intent,
		flags: Int,
		startId: Int,
	): Int {
		// No point in restarting the service if it really gets killed.
		return START_NOT_STICKY
	}

	override fun onDestroy() {
		buildServiceScope.cancel()
		mBinder?.release()
		mBinder = null

		log.info("Service is being destroyed. Dismissing the shown notification...")
		notificationManager!!.cancel(NOTIFICATION_ID)

		val lookup = Lookup.getDefault()
		lookup.unregister(BuildService.KEY_BUILD_SERVICE)

		server?.also { server ->
			try {
				log.info("Shutting down Tooling API server...")
				// send the shutdown request but do not wait for the server to respond
				// the service should not block the onDestroy call in order to avoid timeouts
				// the tooling server must release resources and exit automatically
				IDEApplication.instance.coroutineScope.launch(Dispatchers.IO) {
					// This might result in an `IOException: stream closed` if the tooling server
					// process exited before we had a chance to send the shutdown request. Since
					// the server exits before we have a chance to communicate with it, the
					// OutputStream we use to send the request is closed as well, resulting in the
					// IOException.
					runCatching { server.shutdown().await() }
						.onFailure { err ->
							val actualCause = err.cause ?: err
							val message = actualCause.message?.lowercase() ?: ""
							if (message.contains("stream closed") || message.contains("broken pipe")) {
								log.info("Tooling API server stream closed during shutdown (expected)")
							} else {
								log.error("Failed to shutdown Tooling API server", err)
								Sentry.captureException(err)
							}
						}
				}
			} catch (e: Throwable) {
				if (e !is TimeoutException) {
					log.error("Failed to shutdown Tooling API server", e)
				}
			}
		}

		log.debug("Cancelling tooling server runner...")
		toolingServerRunner?.release()
		toolingServerRunner = null

		toolingApiClient?.client = null
		toolingApiClient = null
		isToolingServerStarted = false
	}

	override fun onBind(intent: Intent): IBinder? {
		if (mBinder == null) {
			mBinder = GradleServiceBinder(this)
		}
		return mBinder
	}

	override fun onListenerStarted(
		server: IToolingApiServer,
		errorStream: InputStream,
	) {
		startServerOutputReader(errorStream)
			.invokeOnCompletion { err ->
				log.info("tooling API server reader stopped: ${err?.message ?: "OK"}", err)
				outputReaderJob = null
			}

		this.server = server
		this.isToolingServerStarted = true
	}

	override fun onServerExited(exitCode: Int) {
		log.warn("Tooling API process terminated with exit code: {}", exitCode)
		stopForeground(STOP_FOREGROUND_REMOVE)
	}

	override fun getClient(): IToolingApiClient {
		if (toolingApiClient == null) {
			toolingApiClient = ForwardingToolingApiClient(this)
		}
		return toolingApiClient!!
	}

	override fun logMessage(params: LogMessageParams) {
		val logger = LoggerFactory.getLogger(params.tag)
		when (params.level) {
			'D' -> logger.debug(params.message)
			'W' -> logger.warn(params.message)
			'E' -> logger.error(params.message)
			'I' -> logger.info(params.message)
			else -> logger.trace(params.message)
		}
	}

	override fun logOutput(line: String) {
		val listener = editorListener()
		if (listener != null) {
			listener.onOutput(line)
			return
		}
		// Suppressed because an internal build is running. Keep a bounded tail anyway: if that
		// build FAILS this is the only copy of Gradle's reason, since the tooling API's own
		// failure is a bare enum. See takeInternalBuildOutput.
		synchronized(internalBuildOutput) {
			if (internalBuildOutput.size >= MAX_INTERNAL_OUTPUT_LINES) {
				internalBuildOutput.removeFirst()
			}
			internalBuildOutput.addLast(line)
		}
		internalBuildProgress?.let { report ->
			try {
				report(line)
			} catch (e: Exception) {
				log.warn("Internal build progress listener threw", e)
			}
		}
	}

	/**
	 * Takes and clears the current internal build's captured Gradle output.
	 *
	 * Draining rather than reading, so one failure's report can never be quoted against the next
	 * build.
	 *
	 * @return the captured lines, oldest first; empty when nothing was captured.
	 */
	fun takeInternalBuildOutput(): List<String> =
		synchronized(internalBuildOutput) {
			val captured = internalBuildOutput.toList()
			internalBuildOutput.clear()
			captured
		}

	override fun prepareBuild(buildInfo: BuildInfo): CompletableFuture<ClientGradleBuildConfig> =
		CompletableFuture.supplyAsync {
			updateNotification(getString(R.string.build_status_in_progress), true)

			val projectPath = ProjectManagerImpl.getInstance().projectDirPath ?: "unknown"
			val buildType = getBuildType(buildInfo.tasks)
			val isDebugBuild = buildType == "debug"

			val currentTuningConfig = tuningConfig
			var newTuningConfig: GradleTuningConfig? = null

			@Suppress("SimplifyBooleanWithConstants")
			val extraArgs =
				getGradleExtraArgs(enableJdwp = JdwpOptions.JDWP_ENABLED && isDebugBuild)

			var buildParams =
				if (FeatureFlags.isExperimentsEnabled) {
					runCatching {
						newTuningConfig =
							GradleBuildTuner.autoTune(
								device = DeviceInfo.buildDeviceProfile(applicationContext),
								build = BuildProfile(isDebugBuild),
								previousConfig = currentTuningConfig,
								analyticsManager = analyticsManager,
								buildId = buildInfo.buildId,
							)

						tuningConfig = newTuningConfig
						GradleBuildTuner
							.toGradleBuildParams(tuningConfig = newTuningConfig)
							.run {
								copy(gradleArgs = gradleArgs + extraArgs)
							}
					}.onFailure { err ->
						log.error("Failed to auto-tune Gradle build", err)
						Sentry.captureException(err)
					}.getOrDefault(null)
				} else {
					null
				}

			if (buildParams == null) {
				buildParams = GradleBuildParams(gradleArgs = extraArgs)
			}

			analyticsManager.trackBuildRun(
				metric =
					BuildStartedMetric(
						buildId = buildInfo.buildId,
						buildType = buildType,
						projectPath = projectPath,
						tuningConfig = newTuningConfig,
					),
			)

			EventBus
				.getDefault()
				.post(
					BuildStartedEvent(buildInfo),
				)

			editorListener()?.prepareBuild(buildInfo)

			return@supplyAsync ClientGradleBuildConfig(
				buildParams = buildParams,
			)
		}

	override fun onBuildSuccessful(result: BuildResult) {
		updateNotification(getString(R.string.build_status_sucess), false)

		dispatchBuildResult(result, true)
		editorListener()?.onBuildSuccessful(result.tasks)
	}

	override fun onBuildFailed(result: BuildResult) {
		updateNotification(getString(R.string.build_status_failed), false)

		dispatchBuildResult(result, false)
		editorListener()?.onBuildFailed(result.tasks)
	}

	private fun dispatchBuildResult(
		result: BuildResult,
		isSuccess: Boolean,
	) {
		val buildType = getBuildType(result.tasks)
		analyticsManager.trackBuildCompleted(
			metric =
				BuildCompletedMetric(
					buildId = result.buildId,
					isSuccess = isSuccess,
					buildType = buildType,
					buildResult = result,
				),
		)

		buildServiceScope.launch {
			ProjectManagerImpl
				.getInstance()
				.indexingServiceManager
				.onBuildCompleted()
		}

		EventBus
			.getDefault()
			.post(
				BuildCompletedEvent(
					result = result,
				),
			)
	}

	override fun onProgressEvent(event: ProgressEvent) {
		editorListener()?.onProgressEvent(event)
	}

	private fun getGradleExtraArgs(
		enableJdwp: Boolean = JdwpOptions.JDWP_ENABLED,
		enableLogSender: Boolean = DevOpsPreferences.logsenderEnabled,
	): List<String> {
		val extraArgs = ArrayList<String>()
		extraArgs.add("--init-script")
		extraArgs.add(Environment.INIT_SCRIPT.absolutePath)

		// Override the AAPT2 binary: the one downloaded from Maven is not built for Android.
		extraArgs.add("-Pandroid.aapt2FromMavenOverride=${Environment.AAPT2.absolutePath}")
		extraArgs.add("-P${PROPERTY_JDWP_ENABLED}=$enableJdwp")
		extraArgs.add("-P${PROPERTY_LOG_SENDER_ENABLED}=$enableLogSender")
		extraArgs.add("-P${PROPERTY_LOG_SENDER_AAR}=${Environment.LOGSENDER_AAR.absolutePath}")

		if (BuildPreferences.isStacktraceEnabled) {
			extraArgs.add("--stacktrace")
		}
		if (BuildPreferences.isInfoEnabled) {
			extraArgs.add("--info")
		}
		if (BuildPreferences.isDebugEnabled) {
			extraArgs.add("--debug")
		}
		if (BuildPreferences.isWarningModeAllEnabled) {
			extraArgs.add("--warning-mode")
			extraArgs.add("all")
		}
		if (BuildPreferences.isBuildCacheEnabled) {
			extraArgs.add("--build-cache")
		}
		if (BuildPreferences.isOfflineEnabled) {
			extraArgs.add("--offline")
		}
		if (BuildPreferences.isScanEnabled) {
			if (isGradleEnterprisePluginAvailable()) {
				extraArgs.add("--scan")
			} else {
				log.warn("Gradle Enterprise plugin is not available. The --scan option has been disabled for this build.")
			}
		}

		return extraArgs
	}

	override fun checkGradleWrapperAvailability(): CompletableFuture<GradleWrapperCheckResult> =
		if (isGradleWrapperAvailable) {
			CompletableFuture.completedFuture(
				GradleWrapperCheckResult(true),
			)
		} else {
			installWrapper()
		}

	/**
	 * Redirects start notifications to [listener], or drops them when it is null. A no-op until the
	 * tooling server runner exists.
	 *
	 * @param listener notified once the tooling server is up.
	 */
	internal fun setServerListener(listener: OnServerStartListener?) {
		if (toolingServerRunner != null) {
			toolingServerRunner!!.setListener(listener)
		}
	}

	private fun installWrapper(): CompletableFuture<GradleWrapperCheckResult> {
		eventListener?.also { eventListener ->
			eventListener.onOutput("-------------------- NOTE --------------------")
			eventListener.onOutput(getString(R.string.msg_installing_gradlew))
			eventListener.onOutput("----------------------------------------------")
		}
		return CompletableFuture.supplyAsync { doInstallWrapper() }
	}

	private fun doInstallWrapper(): GradleWrapperCheckResult {
		val extracted = File(Environment.TMP_DIR, "gradle-wrapper.zip")
		if (!ResourceUtils.copyFileFromAssets(
				ToolsManager.getCommonAsset("gradle-wrapper.zip"),
				extracted.absolutePath,
			)
		) {
			log.error("Unable to extract gradle-plugin.zip from IDE resources.")
			return GradleWrapperCheckResult(false)
		}
		try {
			val projectDir = ProjectManagerImpl.getInstance().projectDir
			val files = ZipUtils.unzipFile(extracted, projectDir)
			if (files.isNotEmpty()) {
				return GradleWrapperCheckResult(true)
			}
		} catch (e: IOException) {
			log.error("An error occurred while extracting Gradle wrapper", e)
		}
		return GradleWrapperCheckResult(false)
	}

	private fun updateNotification(
		message: String,
		isProgress: Boolean,
	) {
		runOnUiThread { doUpdateNotification(message, isProgress) }
	}

	private fun doUpdateNotification(
		message: String,
		isProgress: Boolean,
	) {
		(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
			NOTIFICATION_ID,
			buildNotification(message, isProgress),
		)
	}

	override fun metadata(): CompletableFuture<ToolingServerMetadata> {
		checkServerStarted()
		return server!!.metadata()
	}

	override fun initializeProject(params: InitializeProjectParams): CompletableFuture<InitializeResult> {
		checkServerStarted()
		Objects.requireNonNull(params)
		return try {
			performBuildTasks(server!!.initialize(params))
		} catch (_: ScanPluginMissingException) {
			log.info("Retrying initialization without --scan option...")
			initializeProject(params)
		}
	}

	override fun executeTasks(tasks: List<String>): CompletableFuture<TaskExecutionResult> =
		executeTasks(
			message =
				TaskExecutionMessage(
					tasks = tasks,
					buildId = nextBuildId(BuildRunType.TaskRun),
				),
		)

	override fun executeTasks(message: TaskExecutionMessage): CompletableFuture<TaskExecutionResult> {
		checkServerStarted()

		val future = performBuildTasks(server!!.executeTasks(message))

		return future.handle { result, exception ->
			if (exception != null) {
				val cause = exception.cause
				if (cause is ScanPluginMissingException) {
					log.info("Retrying build without --scan option...")
					return@handle executeTasks(message).get()
				}
				throw CompletionException(exception)
			}
			return@handle result
		}
	}

	override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> {
		checkServerStarted()
		return server!!.cancelCurrentBuild()
	}

	private fun <T> performBuildTasks(future: CompletableFuture<T>): CompletableFuture<T> {
		return CompletableFuture
			.runAsync(this::onPrepareBuildRequest)
			.handleAsync { _, _ ->
				try {
					return@handleAsync future.get()
				} catch (e: Throwable) {
					if (BuildPreferences.isScanEnabled &&
						(
							e.toString().contains(ERROR_GRADLE_ENTERPRISE_PLUGIN) ||
								e.toString().contains(ERROR_COULD_NOT_FIND_GRADLE)
						)
					) {
						BuildPreferences.isScanEnabled = false

						editorListener()?.onOutput(MESSAGE_SCAN_REQUIRES_PLUGIN)
						editorListener()?.onOutput(MESSAGE_OPTION_DISABLED)

						throw ScanPluginMissingException(MESSAGE_EXCEPTION_SCAN_DISABLED)
					}

					throw CompletionException(e)
				}
			}.handle(this::markBuildAsFinished)
	}

	/**
	 * Signals that `--scan` was requested without the Gradle Enterprise plugin, so the build should
	 * be retried without it.
	 *
	 * @param message what to report about the disabled option.
	 */
	class ScanPluginMissingException(
		message: String,
	) : Exception(message)

	private fun isGradleEnterprisePluginAvailable(): Boolean {
		val projectDir = ProjectManagerImpl.getInstance().projectDir ?: return false

		val settingsFiles =
			listOf(
				File(projectDir, "settings.gradle"),
				File(projectDir, "settings.gradle.kts"),
			)

		for (file in settingsFiles) {
			if (file.exists()) {
				try {
					val content = file.readText()
					if (content.contains("com.gradle.enterprise")) {
						return true
					}
				} catch (e: Exception) {
					log.error("Error reading settings file: ${file.name}", e)
				}
			}
		}

		return false
	}

	private fun onPrepareBuildRequest() {
		checkServerStarted()
		ensureTmpdir()
		if (isBuildInProgress) {
			logBuildInProgress()
			throw BuildInProgressException()
		}
		isBuildInProgress = true
	}

	@Throws(ToolingServerNotStartedException::class)
	private fun checkServerStarted() {
		if (!isToolingServerStarted()) {
			throw ToolingServerNotStartedException()
		}
	}

	private fun ensureTmpdir() {
		Environment.mkdirIfNotExists(Environment.TMP_DIR)
	}

	private fun logBuildInProgress() {
		log.warn("A build is already in progress!")
	}

	@Suppress("UNUSED_PARAMETER")
	private fun <T> markBuildAsFinished(
		result: T,
		throwable: Throwable?,
	): T {
		isBuildInProgress = false
		return result
	}

	/**
	 * Starts the tooling server if it is not up yet; otherwise tells [listener] about the running
	 * one straight away.
	 *
	 * @param listener notified once the server is available.
	 */
	internal fun startToolingServer(listener: OnServerStartListener?) {
		if (toolingServerRunner?.isStarted != true) {
			val envs = TermuxShellEnvironment().getEnvironment(this, false)
			toolingServerRunner = ToolingServerRunner(listener, this).also { it.startAsync(envs) }
			return
		}

		if (toolingServerRunner!!.isStarted && listener != null) {
			listener.onServerStarted(toolingServerRunner!!.pid!!)
		} else {
			setServerListener(listener)
		}
	}

	/**
	 * Installs the editor's build listener, wrapped so every callback arrives on the UI thread.
	 *
	 * @param eventListener the listener to install, or null to remove the current one.
	 * @return this service, for chaining.
	 */
	fun setEventListener(eventListener: EventListener?): GradleBuildService {
		if (eventListener == null) {
			this.eventListener = null
			return this
		}
		this.eventListener = wrap(eventListener)
		return this
	}

	private fun wrap(listener: EventListener?): EventListener? =
		if (listener == null) {
			null
		} else {
			object : EventListener {
				override fun prepareBuild(buildInfo: BuildInfo) {
					runOnUiThread { listener.prepareBuild(buildInfo) }
				}

				override fun onBuildSuccessful(tasks: List<String?>) {
					runOnUiThread { listener.onBuildSuccessful(tasks) }
				}

				override fun onProgressEvent(event: ProgressEvent) {
					runOnUiThread { listener.onProgressEvent(event) }
				}

				override fun onBuildFailed(tasks: List<String?>) {
					runOnUiThread { listener.onBuildFailed(tasks) }
				}

				override fun onOutput(line: String?) {
					runOnUiThread { listener.onOutput(line) }
				}
			}
		}

	private fun startServerOutputReader(input: InputStream): Job {
		outputReaderJob?.let { job ->
			if (job.isActive) {
				return job
			}
		}

		return buildServiceScope
			.launch(
				Dispatchers.IO + CoroutineName("ToolingServerErrorReader"),
			) {
				val reader = input.bufferedReader()
				try {
					reader.forEachLine { line ->
						SERVER_System_err.debug(line)
						if (!isActive) throw CancellationException()
					}
				} catch (e: Throwable) {
					e.ifCancelledOrInterrupted(suppress = true) {
						return@launch
					}

					// A dead reader only costs us the server's stderr log, so fail silently.
					log.error("Failed to read tooling server output", e)
				}
			}.also { job ->
				outputReaderJob = job
			}
	}

	/** Handles events received from a Gradle build. */
	interface EventListener {
		/**
		 * Called just before a build is started.
		 *
		 * @param buildInfo The information about the build to be executed.
		 * @see IToolingApiClient.prepareBuild
		 */
		fun prepareBuild(buildInfo: BuildInfo)

		/**
		 * Called when a build is successful.
		 *
		 * @param tasks The tasks that were run.
		 * @see IToolingApiClient.onBuildSuccessful
		 */
		fun onBuildSuccessful(tasks: List<String?>)

		/**
		 * Called when a progress event is received from the Tooling API server.
		 *
		 * @param event The event model describing the event.
		 */
		fun onProgressEvent(event: ProgressEvent)

		/**
		 * Called when a build fails.
		 *
		 * @param tasks The tasks that were run.
		 * @see IToolingApiClient.onBuildFailed
		 */
		fun onBuildFailed(tasks: List<String?>)

		/**
		 * Called when the output line is received.
		 *
		 * @param line The line of the build output.
		 */
		fun onOutput(line: String?)
	}
}
