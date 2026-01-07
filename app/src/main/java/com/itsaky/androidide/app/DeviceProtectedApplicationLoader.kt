package com.itsaky.androidide.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.itsaky.androidide.BuildConfig
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.app.strictmode.StrictModeConfig
import com.itsaky.androidide.app.strictmode.StrictModeManager
import com.itsaky.androidide.di.coreModule
import com.itsaky.androidide.di.pluginModule
import com.itsaky.androidide.events.AppEventsIndex
import com.itsaky.androidide.events.EditorEventsIndex
import com.itsaky.androidide.events.LspApiEventsIndex
import com.itsaky.androidide.events.LspJavaEventsIndex
import com.itsaky.androidide.events.ProjectsApiEventsIndex
import com.itsaky.androidide.handlers.CrashEventSubscriber
import com.itsaky.androidide.syntax.colorschemes.SchemeAndroidIDE
import com.itsaky.androidide.ui.themes.IThemeManager
import com.itsaky.androidide.utils.FeatureFlags
import com.termux.shared.reflection.ReflectionUtils
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.sentry.Sentry
import io.sentry.SentryReplayOptions.SentryReplayQuality
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import org.greenrobot.eventbus.EventBus
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * @author Akash Yadav
 */
internal object DeviceProtectedApplicationLoader :
	ApplicationLoader,
	DefaultLifecycleObserver,
	KoinComponent {
	private val logger = LoggerFactory.getLogger(DeviceProtectedApplicationLoader::class.java)

	private val crashEventSubscriber = CrashEventSubscriber()
	val analyticsManager: IAnalyticsManager by inject()

	override suspend fun load(app: IDEApplication) {
		logger.info("Loading device protected storage context components...")

		runCatching {
			// try to initialize feature flags
			// this may fail when running in direct boot mode, so we wrap this
			// in runCatching and ignore errors, if any
			FeatureFlags.initialize()
		}

		// Enable StrictMode for debug builds
		StrictModeManager.install(
			StrictModeConfig(
				enabled = BuildConfig.DEBUG && !FeatureFlags.isPardonEnabled,
				isReprieveEnabled = FeatureFlags.isReprieveEnabled,
			),
		)

		startKoin {
			androidContext(app)
			modules(coreModule, pluginModule)
		}

		SentryAndroid.init(app) { options ->
			// Reduce replay quality to LOW to prevent OOM
			// This reduces screenshot compression to 10 and bitrate to 50kbps
			// (defaults to MEDIUM quality)
			options.sessionReplay.quality = SentryReplayQuality.LOW
			options.environment =
				if (BuildConfig.DEBUG) IDEApplication.SENTRY_ENV_DEV else IDEApplication.SENTRY_ENV_PROD
		}

		ShizukuSettings.initialize()

		EventBus
			.builder()
			.addIndex(AppEventsIndex())
			.addIndex(EditorEventsIndex())
			.addIndex(ProjectsApiEventsIndex())
			.addIndex(LspApiEventsIndex())
			.addIndex(LspJavaEventsIndex())
			.installDefaultEventBus(true)

		EventBus.getDefault().register(crashEventSubscriber)

		EditorColorScheme.setDefault(SchemeAndroidIDE.newInstance(null))

		ReflectionUtils.bypassHiddenAPIReflectionRestrictions()

		app.coroutineScope.launch(Dispatchers.IO) {
			// early-init theme manager since it may need to perform disk reads
			IThemeManager.getInstance()
		}

		withContext(Dispatchers.Main) {
			initializeAnalytics()
		}
	}

	private fun initializeAnalytics() {
		try {
			ProcessLifecycleOwner.get().lifecycle.addObserver(this)
			analyticsManager.initialize()
			logger.info("Firebase Analytics initialized successfully")
		} catch (e: Exception) {
			logger.error("Failed to initialize Firebase Analytics", e)
		}
	}

	fun handleUncaughtException(
		thread: Thread,
		exception: Throwable,
	) {
		// we can't write logs to files, nor we can show the crash handler
		// activity to the user. Just report to Sentry and exit.

		Sentry.captureException(exception)
		IDEApplication.instance.uncaughtExceptionHandler?.uncaughtException(thread, exception)
		exitProcess(EXIT_CODE_CRASH)
	}

	override fun onStart(owner: LifecycleOwner) {
		super.onStart(owner)
		analyticsManager.startSession()
	}

	override fun onStop(owner: LifecycleOwner) {
		super.onStop(owner)
		analyticsManager.endSession()
	}
}
