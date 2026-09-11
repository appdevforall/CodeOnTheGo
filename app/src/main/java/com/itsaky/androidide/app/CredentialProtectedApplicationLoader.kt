package com.itsaky.androidide.app

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.work.WorkManager
import com.google.android.material.color.DynamicColors
import com.itsaky.androidide.activities.CrashHandlerActivity
import com.itsaky.androidide.activities.editor.IDELogcatReader
import com.itsaky.androidide.api.BuildOutputProvider
import com.itsaky.androidide.editor.schemes.IDEColorSchemeProvider
import com.itsaky.androidide.eventbus.events.plugin.PluginCrashedEvent
import com.itsaky.androidide.eventbus.events.preferences.PreferenceChangeEvent
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.managers.ToolsManager
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.preferences.internal.DevOpsPreferences
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.resources.localization.LocaleProvider
import com.itsaky.androidide.ui.themes.IDETheme
import com.itsaky.androidide.ui.themes.IThemeManager
import com.itsaky.androidide.utils.EditorDecorationBridge
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.FeatureFlags
import com.itsaky.androidide.utils.FileUtil
import com.itsaky.androidide.utils.VMUtils
import io.sentry.Sentry
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * An [ApplicationLoader] which requires the credential protected storage
 * to be available initialization.
 *
 * Components that need to access the credential protected storage must be
 * initialized here.
 *
 * @author Akash Yadav
 */
internal object CredentialProtectedApplicationLoader : ApplicationLoader {
	private val logger = LoggerFactory.getLogger(CredentialProtectedApplicationLoader::class.java)

	private val _isLoaded = AtomicBoolean(false)
	private lateinit var application: IDEApplication

	var ideLogcatReader: IDELogcatReader? = null
		private set

	var pluginManager: PluginManager? = null
		private set

	val isLoaded: Boolean
		get() = _isLoaded.get()

	override suspend fun load(app: IDEApplication) {
		if (isLoaded) {
			logger.warn("Attempt to perform multiple loads of the application. Ignoring.")
			return
		}

		logger.info("Loading credential protected storage context components...")

		if (!isCredentialStorageReady(app)) {
			logger.error("Credential protected storage is not ready. Skipping credential protected initialization.")
			return
		}

		// Storage is confirmed accessible here, so it's safe to warm IDEApplication.cachedFilesDir
		// now for devices that were still locked (Direct Boot) when onCreate() ran its own warmup.
		// by lazy caches the value, not a failure, so swallowing errors here just means the first
		// real read pays the syscall - it never poisons the cache or blocks the retry.
		runCatching { withContext(Dispatchers.IO) { IDEApplication.cachedFilesDir } }
			.onFailure { logger.warn("Failed to warm cachedFilesDir; first read will hit disk", it) }

		if (!_isLoaded.compareAndSet(false, true)) {
			// Another call already claimed initialization (e.g. a concurrent retry after
			// user unlock); avoid running the rest of this method twice.
			logger.warn("Attempt to perform multiple loads of the application. Ignoring.")
			return
		}

		application = app

		try {
			initializeWorkManagerSafely(app)

			Environment.init(app)

			FeatureFlags.initialize()
			LeakCanaryConfig.applyFromFeatureFlags()

			if (!EventBus.getDefault().isRegistered(this)) {
				EventBus.getDefault().register(this)
			}

			// Load termux application
			TermuxApplicationLoader.load(app)

			if (DevOpsPreferences.dumpLogs) {
				startLogcatReader()
			}

			withContext(Dispatchers.Main) {
				AppCompatDelegate.setDefaultNightMode(GeneralPreferences.uiMode)

				if (IThemeManager.getInstance().getCurrentTheme() == IDETheme.MATERIAL_YOU) {
					DynamicColors.applyToActivitiesIfAvailable(app)
				}
			}

			initializePluginSystem()
			installPluginCrashLooperGuard()

			app.coroutineScope.launch(Dispatchers.IO) {
				// color schemes are stored in files
				// initialize scheme provider on the IO dispatcher
				IDEColorSchemeProvider.init()
			}

			if (!VMUtils.isJvm || VMUtils.isInstrumentedTest) {
				ToolsManager.init(app, null)
			}
		} catch (e: Throwable) {
			// Un-claim the load on failure/cancellation so a later retry (e.g. after user
			// unlock) can attempt initialization again instead of being stuck "loaded" with
			// some components never actually initialized.
			_isLoaded.set(false)
			throw e
		}
	}

	private fun isCredentialStorageReady(app: IDEApplication): Boolean {
		val userManager = app.getSystemService(UserManager::class.java)

		if (!userManager.isUserUnlocked) return false

		val filesDir = app.filesDir
		val noBackupDir = app.noBackupFilesDir

		if (!filesDir.exists()) filesDir.mkdirs()
		if (!noBackupDir.exists()) noBackupDir.mkdirs()

		return filesDir.exists() &&
			filesDir.isDirectory &&
			noBackupDir.exists() &&
			noBackupDir.isDirectory
	}

	private fun initializeWorkManagerSafely(app: IDEApplication) {
		try {
			WorkManager.getInstance(app)
		} catch (error: IllegalStateException) {
			// WorkManager.getInstance throws IllegalStateException if WorkManager is not
			// initialized properly (e.g. WorkManagerInitializer disabled/misconfigured).
			logger.error("Failed to get WorkManager instance after storage validation", error)
			Sentry.captureException(error)
		}
	}

	fun handleUncaughtException(
		thread: Thread,
		exception: Throwable,
	) {
		val pluginManager = PluginManager.getInstance()
		val pluginId =
			runCatching {
				pluginManager?.let { pm ->
					pm.crashTracker.findPluginForStackTrace(
						exception,
						pm.getLoadedPluginIds(),
					) { pm.getClassLoaderForPluginId(it) }
				}
			}.getOrNull()

		if (pluginId != null) {
			handlePluginCrash(pluginId, exception)
			return
		}

		writeException(exception)
		Sentry.captureException(exception)

		runCatching {
			val intent = Intent()
			intent.action = CrashHandlerActivity.REPORT_ACTION
			intent.putExtra(
				CrashHandlerActivity.TRACE_KEY,
				exception.stackTraceToString(),
			)
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			IDEApplication.instance.startActivity(intent)
		}.onFailure { error ->
			Sentry.captureException(error)
			logger.error("Unable to start crash handler activity", error)
		}

		IDEApplication.instance.uncaughtExceptionHandler?.uncaughtException(thread, exception)

		exitProcess(EXIT_CODE_CRASH)
	}

	private fun handlePluginCrash(
		pluginId: String,
		exception: Throwable,
	) {
		runCatching {
			writeException(exception)

			Sentry.withScope { scope ->
				scope.setTag("plugin_crash", "true")
				scope.setTag("plugin_id", pluginId)
				Sentry.captureException(exception)
			}

			val pluginManager = PluginManager.getInstance() ?: return
			val result = pluginManager.recordPluginCrash(pluginId)

			val wasDisabled = result is PluginManager.CrashResult.Disabled
			val crashCount =
				when (result) {
					is PluginManager.CrashResult.Recorded -> result.crashCount
					is PluginManager.CrashResult.Disabled -> pluginManager.crashTracker.getCrashCount(pluginId)
				}

			EventBus.getDefault().post(
				PluginCrashedEvent(pluginId, result.pluginName, crashCount, wasDisabled, exception.stackTraceToString()),
			)
			logger.warn("Plugin crash handled without killing process: {} (disabled={})", pluginId, wasDisabled)
		}.onFailure { e ->
			logger.error("Failed to handle plugin crash gracefully for: {}", pluginId, e)
		}
	}

	private var lastPluginCrashTime = 0L

	private fun installPluginCrashLooperGuard() {
		Handler(Looper.getMainLooper()).post {
			while (true) {
				try {
					Looper.loop()
					break
				} catch (e: Throwable) {
					val pluginId =
						runCatching {
							PluginManager.getInstance()?.let { pm ->
								pm.crashTracker.findPluginForStackTrace(
									e,
									pm.getLoadedPluginIds(),
								) { pm.getClassLoaderForPluginId(it) }
							}
						}.getOrNull()

					if (pluginId != null) {
						lastPluginCrashTime = System.currentTimeMillis()
						handlePluginCrash(pluginId, e)
					} else if (System.currentTimeMillis() - lastPluginCrashTime < COLLATERAL_CRASH_WINDOW_MS) {
						logger.warn("Suppressing collateral crash after recent plugin crash: {}", e.message)
					} else {
						handleUncaughtException(Thread.currentThread(), e)
						break
					}
				}
			}
		}
		logger.info("Plugin crash Looper guard installed on main thread")
	}

	private const val COLLATERAL_CRASH_WINDOW_MS = 3000L

	private fun writeException(throwable: Throwable?) =
		runCatching {
			// ignore errors
			File(FileUtil.getExternalStorageDir(), "idelog.txt")
				.writer()
				.buffered()
				.use { outputStream ->
					outputStream.write(throwable?.stackTraceToString() ?: "")
				}
		}

	private fun startLogcatReader() {
		if (ideLogcatReader != null) {
			// already started
			return
		}

		logger.info("Starting logcat reader...")
		ideLogcatReader = IDELogcatReader().also { it.start() }
	}

	private fun stopLogcatReader() {
		logger.info("Stopping logcat reader...")
		ideLogcatReader?.stop()
		ideLogcatReader = null
	}

	@OptIn(DelicateCoroutinesApi::class)
	private fun initializePluginSystem() {
		try {
			logger.info("Initializing plugin system...")

			// Create a plugin logger adapter
			val pluginLogger =
				object : PluginLogger {
					override val pluginId = "system"

					override fun debug(message: String) = logger.debug(message)

					override fun debug(
						message: String,
						error: Throwable,
					) = logger.debug(message, error)

					override fun info(message: String) = logger.info(message)

					override fun info(
						message: String,
						error: Throwable,
					) = logger.info(message, error)

					override fun warn(message: String) = logger.warn(message)

					override fun warn(
						message: String,
						error: Throwable,
					) = logger.warn(message, error)

					override fun error(message: String) = logger.error(message)

					override fun error(
						message: String,
						error: Throwable,
					) = logger.error(message, error)
				}

			pluginManager =
				PluginManager.getInstance(
					context = application,
					eventBus = EventBus.getDefault(),
					logger = pluginLogger,
				)

			// Set up plugin service providers
			setupPluginServices()
			setupPluginInflationErrorHandler()

			// Load plugins asynchronously
			GlobalScope.launch {
				try {
					pluginManager?.loadPlugins()
					EditorDecorationBridge.init()
					logger.info("Plugin system initialized successfully")
				} catch (e: Exception) {
					logger.error("Failed to load plugins", e)
				}
			}
		} catch (e: Exception) {
			Sentry.captureException(e)
			logger.error("Failed to initialize plugin system", e)
		}
	}

	/**
	 * Sets up the plugin service providers to integrate with AndroidIDE's actual systems.
	 */
	private fun setupPluginServices() {
		pluginManager?.let { manager ->
			manager.setActivityProvider { application.foregroundActivity }
			setupBuildServiceProviders()
			setupProjectManipulationProviders()
			logger.info("Plugin services configured successfully")
		}
	}

	@OptIn(DelicateCoroutinesApi::class)
	private fun setupBuildServiceProviders() {
		val buildServiceImpl =
			com.itsaky.androidide.plugins.manager.services.IdeBuildServiceImpl
				.getInstance()

		// Provide runApp functionality
		buildServiceImpl.setRunAppProvider { callback ->
			logger.info("runApp provider called")
			PluginRunAppCoordinator.runApp(application.foregroundActivity, callback)
		}

		// Provide gradle sync functionality
		buildServiceImpl.setGradleSyncProvider { callback ->
			GlobalScope.launch(Dispatchers.IO) {
				try {
					val buildService = Lookup.getDefault().lookup(com.itsaky.androidide.projects.builder.BuildService.KEY_BUILD_SERVICE)
					if (buildService == null) {
						callback.onComplete(false, "Build service not available")
						return@launch
					}

					logger.info("Triggering Gradle sync via generateDebugSources task")
					val result = buildService.executeTasks(listOf("generateDebugSources")).get()

					if (result == null || !result.isSuccessful) {
						val errorMsg = result?.failure?.toString() ?: "Unknown error"
						logger.error("Gradle sync failed: {}", errorMsg)
						callback.onComplete(false, "Gradle sync failed: $errorMsg")
					} else {
						logger.info("Gradle sync completed successfully")
						callback.onComplete(true, "Gradle sync completed")
					}
				} catch (e: Exception) {
					logger.error("Failed to sync gradle", e)
					callback.onComplete(false, "Error: ${e.message}")
				}
			}
		}

		// Provide build output: the real log, or null when there is none. No status text -- the
		// consumer cannot tell a message from a log, so anything non-empty reads as build output.
		// No BuildService lookup either: the session file outlives the tooling server, and reading it
		// after a crashed build is precisely when the log is needed.
		buildServiceImpl.setBuildOutputProvider {
			try {
				BuildOutputProvider.getBuildOutputContent()
			} catch (e: Exception) {
				logger.error("Failed to read build output", e)
				null
			}
		}
	}

	private fun setupProjectManipulationProviders() {
		val manipulationServiceImpl =
			com.itsaky.androidide.plugins.manager.services.IdeProjectManipulationServiceImpl
				.getInstance()

		// Provide dependency addition
		manipulationServiceImpl.setAddDependencyProvider { dependencyString, buildFilePath ->
			try {
				val buildFile = java.io.File(buildFilePath)
				if (!buildFile.exists() || !buildFile.isFile) {
					logger.warn("Build file not found: {}", buildFilePath)
					return@setAddDependencyProvider false
				}

				// Read the current file
				val content = buildFile.readText()

				// Check if dependency already exists
				if (content.contains(dependencyString, ignoreCase = false)) {
					logger.info("Dependency already present: {}", dependencyString)
					return@setAddDependencyProvider true
				}

				// Find the dependencies block and add the new dependency
				// Format: implementation("...") or api("...") etc.
				val lines = content.split("\n").toMutableList()
				var dependenciesBlockIndex = -1
				var insertIndex = -1

				// Find the dependencies { block
				for (i in lines.indices) {
					if (lines[i].contains("dependencies")) {
						dependenciesBlockIndex = i
						// Find the closing brace
						for (j in i + 1 until lines.size) {
							if (lines[j].trim().startsWith("}") && !lines[j].trim().startsWith("}")) {
								continue
							}
							if (lines[j].trim() == "}" || lines[j].trim().startsWith("}")) {
								insertIndex = j
								break
							}
						}
						break
					}
				}

				if (dependenciesBlockIndex >= 0 && insertIndex > dependenciesBlockIndex) {
					// Insert the new dependency before the closing brace
					val indentation = "    "
					val depLine = "${indentation}implementation(\"$dependencyString\")"
					lines.add(insertIndex, depLine)

					// Write back
					buildFile.writeText(lines.joinToString("\n"))
					logger.info("Successfully added dependency: {}", dependencyString)
					return@setAddDependencyProvider true
				} else {
					logger.warn("Could not find dependencies block in build file")
					return@setAddDependencyProvider false
				}
			} catch (e: Exception) {
				logger.error("Error adding dependency", e)
				false
			}
		}

		// Provide string resource addition
		manipulationServiceImpl.setAddStringResourceProvider { name, value ->
			// TODO: Implement string resource addition
			false
		}

		// Provide file deletion
		manipulationServiceImpl.setDeleteFileProvider { path ->
			// TODO: Implement file deletion
			false
		}
	}

	private fun setupPluginInflationErrorHandler() {
		PluginFragmentHelper.onPluginInflationError = { pluginId, error ->
			logger.error("Plugin layout inflation failed for: {}", pluginId, error)
			runCatching {
				val pm = PluginManager.getInstance() ?: return@runCatching
				val result = pm.recordPluginCrash(pluginId)
				val wasDisabled = result is PluginManager.CrashResult.Disabled
				val crashCount =
					when (result) {
						is PluginManager.CrashResult.Recorded -> result.crashCount
						is PluginManager.CrashResult.Disabled -> pm.crashTracker.getCrashCount(pluginId)
					}
				EventBus.getDefault().post(
					PluginCrashedEvent(pluginId, result.pluginName, crashCount, wasDisabled, error.stackTraceToString()),
				)
			}
		}
	}

	@Suppress("unused")
	@Subscribe(threadMode = ThreadMode.MAIN)
	fun onPrefChanged(event: PreferenceChangeEvent) {
		val enabled = event.value as? Boolean?
		if (event.key == DevOpsPreferences.KEY_DEVOPTS_DEBUGGING_DUMPLOGS) {
			if (enabled == true) {
				startLogcatReader()
			} else {
				stopLogcatReader()
			}
		} else if (event.key == GeneralPreferences.UI_MODE && GeneralPreferences.uiMode != AppCompatDelegate.getDefaultNightMode()) {
			AppCompatDelegate.setDefaultNightMode(GeneralPreferences.uiMode)
			EditorDecorationBridge.refresh()
		} else if (event.key == GeneralPreferences.SELECTED_LOCALE) {
			// Use empty locale list if the locale has been reset to 'System Default'
			val selectedLocale = GeneralPreferences.selectedLocale
			val localeListCompat =
				selectedLocale?.let {
					LocaleListCompat.create(LocaleProvider.getLocale(selectedLocale))
				} ?: LocaleListCompat.getEmptyLocaleList()

			AppCompatDelegate.setApplicationLocales(localeListCompat)
		}
	}
}
