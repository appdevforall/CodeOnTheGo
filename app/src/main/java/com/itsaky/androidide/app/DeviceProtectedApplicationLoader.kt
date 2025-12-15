package com.itsaky.androidide.app

import android.os.StrictMode
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.appdevforall.codeonthego.computervision.di.computerVisionModule
import com.itsaky.androidide.BuildConfig
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.di.coreModule
import com.itsaky.androidide.di.pluginModule
import com.itsaky.androidide.events.AppEventsIndex
import com.itsaky.androidide.events.EditorEventsIndex
import com.itsaky.androidide.events.LspApiEventsIndex
import com.itsaky.androidide.events.LspJavaEventsIndex
import com.itsaky.androidide.events.ProjectsApiEventsIndex
import com.itsaky.androidide.handlers.CrashEventSubscriber
import com.itsaky.androidide.syntax.colorschemes.SchemeAndroidIDE
import com.itsaky.androidide.utils.FeatureFlags
import com.termux.shared.reflection.ReflectionUtils
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.sentry.Sentry
import io.sentry.SentryReplayOptions.SentryReplayQuality
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import moe.shizuku.manager.ShizukuSettings
import org.greenrobot.eventbus.EventBus
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess
import kotlinx.coroutines.*



/**
 * @author Akash Yadav
 */
internal object DeviceProtectedApplicationLoader : ApplicationLoader, DefaultLifecycleObserver,
	KoinComponent {

	private val logger = LoggerFactory.getLogger(DeviceProtectedApplicationLoader::class.java)

	private val crashEventSubscriber = CrashEventSubscriber()
	val analyticsManager: IAnalyticsManager by inject()

	override fun load(app: IDEApplication) {
		logger.info("Loading device protected storage context components...")

        // Enable StrictMode for debug builds
        if (BuildConfig.DEBUG) {
            CoroutineScope(Dispatchers.Main).launch {
                val reprieve = withContext(Dispatchers.IO) {
                    FeatureFlags.isReprieveEnabled()
                }

                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectAll()
                        .apply { if (reprieve) penaltyLog() else penaltyDeath() }
                        .build()
                )
                StrictMode.setVmPolicy(
                    StrictMode.VmPolicy.Builder()
                        .detectAll()
                        .apply { if (reprieve) penaltyLog() else penaltyDeath() }
                        .build()
                )
            }
        }

        startKoin {
			androidContext(app)
			modules(coreModule, pluginModule, computerVisionModule)
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

		initializeAnalytics()
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
		exception: Throwable
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