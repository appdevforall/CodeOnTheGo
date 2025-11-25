/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.itsaky.androidide.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.StrictMode
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.blankj.utilcode.util.ThrowableUtils.getFullStackTrace
import com.google.android.material.color.DynamicColors
import com.itsaky.androidide.BuildConfig
import com.itsaky.androidide.activities.CrashHandlerActivity
import com.itsaky.androidide.activities.editor.IDELogcatReader
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.di.coreModule
import com.itsaky.androidide.di.pluginModule
import com.itsaky.androidide.editor.schemes.IDEColorSchemeProvider
import com.itsaky.androidide.eventbus.events.preferences.PreferenceChangeEvent
import com.itsaky.androidide.events.AppEventsIndex
import com.itsaky.androidide.events.EditorEventsIndex
import com.itsaky.androidide.events.LspApiEventsIndex
import com.itsaky.androidide.events.LspJavaEventsIndex
import com.itsaky.androidide.events.ProjectsApiEventsIndex
import com.itsaky.androidide.handlers.CrashEventSubscriber
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.preferences.internal.DevOpsPreferences
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.resources.localization.LocaleProvider
import com.itsaky.androidide.syntax.colorschemes.SchemeAndroidIDE
import com.itsaky.androidide.treesitter.TreeSitter
import com.itsaky.androidide.ui.themes.IDETheme
import com.itsaky.androidide.ui.themes.IThemeManager
import com.itsaky.androidide.utils.RecyclableObjectPool
import com.itsaky.androidide.utils.UrlManager
import com.itsaky.androidide.utils.VMUtils
import com.itsaky.androidide.utils.isAtLeastR
import com.itsaky.androidide.utils.isTestMode
import com.termux.app.TermuxApplication
import com.termux.shared.reflection.ReflectionUtils
import com.topjohnwu.superuser.Shell
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.ShizukuSettings
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.slf4j.LoggerFactory
import java.lang.Thread.UncaughtExceptionHandler
import kotlin.system.exitProcess

const val EXIT_CODE_CRASH = 1

class IDEApplication :
	TermuxApplication(),
	DefaultLifecycleObserver {
	private var uncaughtExceptionHandler: UncaughtExceptionHandler? = null
	private var ideLogcatReader: IDELogcatReader? = null
	private var pluginManager: PluginManager? = null
	private var currentActivity: android.app.Activity? = null
	private val crashEventSubscriber = CrashEventSubscriber()
	private val analyticsManager: IAnalyticsManager by inject()

	companion object {
		private val log = LoggerFactory.getLogger(IDEApplication::class.java)

		@JvmStatic
		@SuppressLint("StaticFieldLeak")
		lateinit var instance: IDEApplication
			private set

		init {
			Shell.setDefaultBuilder(
				Shell.Builder
					.create()
					.setFlags(Shell.FLAG_REDIRECT_STDERR),
			)
			HiddenApiBypass.setHiddenApiExemptions("")
			if (!VMUtils.isJvm() && !isTestMode()) {
				try {
					if (isAtLeastR()) {
						System.loadLibrary("adb")
					}

					TreeSitter.loadLibrary()
				} catch (e: UnsatisfiedLinkError) {
					log.warn("Failed to load native libraries", e)
				}
			}

			RecyclableObjectPool.DEBUG = BuildConfig.DEBUG
		}

		@JvmStatic
		fun getPluginManager(): PluginManager? = instance.pluginManager
	}

	/**
	 * Sets up the plugin service providers to integrate with AndroidIDE's actual systems.
	 */
	private fun setupPluginServices() {
		pluginManager?.let { manager ->
			manager.setActivityProvider(
				object : PluginManager.ActivityProvider {
					override fun getCurrentActivity(): android.app.Activity? = getCurrentActiveActivity()
				},
			)

			log.info("Plugin services configured successfully")
		}
	}

	/**
	 * Gets the current active activity from AndroidIDE.
	 * This method should return the currently visible activity.
	 */
	private fun getCurrentActiveActivity(): android.app.Activity? = currentActivity

	/**
	 * Called by activities when they become active/visible.
	 * This is used for plugin UI service integration.
	 */
	fun setCurrentActivity(activity: android.app.Activity?) {
		this.currentActivity = activity
		log.debug("Current activity set to: ${activity?.javaClass?.simpleName}")
	}

	@OptIn(DelicateCoroutinesApi::class)
	override fun onCreate() {
		instance = this
		uncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, th -> handleCrash(thread, th) }

		super<TermuxApplication>.onCreate()

		initializePluginSystem()

		startKoin {
			androidContext(this@IDEApplication)
			modules(coreModule, pluginModule)
		}

        SentryAndroid.init(this) { options ->
            options.environment = if (BuildConfig.DEBUG) "development" else "production"
        }

		ShizukuSettings.initialize(this)

		if (BuildConfig.DEBUG) {
			val builder = StrictMode.VmPolicy.Builder()
			StrictMode.setVmPolicy(builder.build())
			// TODO JMT
//            StrictMode.setVmPolicy(
//                StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy()).penaltyLog().detectAll()
//                    .build()
//            )
			if (DevOpsPreferences.dumpLogs) {
				startLogcatReader()
			}
		}

		EventBus
			.builder()
			.addIndex(AppEventsIndex())
			.addIndex(EditorEventsIndex())
			.addIndex(ProjectsApiEventsIndex())
			.addIndex(LspApiEventsIndex())
			.addIndex(LspJavaEventsIndex())
			.installDefaultEventBus(true)

		EventBus.getDefault().register(this)
		EventBus.getDefault().register(crashEventSubscriber)

		AppCompatDelegate.setDefaultNightMode(GeneralPreferences.uiMode)

		if (IThemeManager.getInstance().getCurrentTheme() == IDETheme.MATERIAL_YOU) {
			DynamicColors.applyToActivitiesIfAvailable(this)
		}

		EditorColorScheme.setDefault(SchemeAndroidIDE.newInstance(null))

		ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
		GlobalScope.launch {
			IDEColorSchemeProvider.init()
		}

		initializeAnalytics()
	}

	private fun initializeAnalytics() {
		try {
			ProcessLifecycleOwner.get().lifecycle.addObserver(this)
			analyticsManager.initialize()
			log.info("Firebase Analytics initialized successfully")
		} catch (e: Exception) {
			log.error("Failed to initialize Firebase Analytics", e)
		}
	}

	override fun onStart(owner: LifecycleOwner) {
		super.onStart(owner)
		analyticsManager.startSession()
	}

	override fun onStop(owner: LifecycleOwner) {
		super.onStop(owner)
		analyticsManager.endSession()
	}

	@OptIn(DelicateCoroutinesApi::class)
	private fun initializePluginSystem() {
		try {
			log.info("Initializing plugin system...")

			// Create a plugin logger adapter
			val pluginLogger =
				object : com.itsaky.androidide.plugins.PluginLogger {
					override val pluginId = "system"

					override fun debug(message: String) = log.debug(message)

					override fun debug(
						message: String,
						error: Throwable,
					) = log.debug(message, error)

					override fun info(message: String) = log.info(message)

					override fun info(
						message: String,
						error: Throwable,
					) = log.info(message, error)

					override fun warn(message: String) = log.warn(message)

					override fun warn(
						message: String,
						error: Throwable,
					) = log.warn(message, error)

					override fun error(message: String) = log.error(message)

					override fun error(
						message: String,
						error: Throwable,
					) = log.error(message, error)
				}

			pluginManager =
				PluginManager.getInstance(
					context = this,
					eventBus = EventBus.getDefault(),
					logger = pluginLogger,
				)

			// Set up plugin service providers
			setupPluginServices()

			// Load plugins asynchronously
			GlobalScope.launch {
				try {
					pluginManager?.loadPlugins()
					log.info("Plugin system initialized successfully")
				} catch (e: Exception) {
					log.error("Failed to load plugins", e)
				}
			}
		} catch (e: Exception) {
			log.error("Failed to initialize plugin system", e)
		}
	}

	private fun handleCrash(
		thread: Thread,
		th: Throwable,
	) {
		Log.d("CrashHandlerAstytooo", th.toString())
		writeException(th)

		Sentry.captureException(th)

		try {
			val intent = Intent()
			intent.action = CrashHandlerActivity.REPORT_ACTION
			intent.putExtra(CrashHandlerActivity.TRACE_KEY, getFullStackTrace(th))
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			startActivity(intent)
			if (uncaughtExceptionHandler != null) {
				uncaughtExceptionHandler!!.uncaughtException(thread, th)
			}

			exitProcess(EXIT_CODE_CRASH)
		} catch (error: Throwable) {
			log.error("Unable to show crash handler activity", error)
		}
	}

	fun showChangelog(context: Context? = null) {
		var version = BuildInfo.VERSION_NAME_SIMPLE
		if (!version.startsWith('v')) {
			version = "v$version"
		}
		val url = "${BuildInfo.REPO_URL}/releases/tag/$version"
		UrlManager.openUrl(url, null, context ?: currentActivity ?: this)
	}

	private fun startLogcatReader() {
		if (ideLogcatReader != null) {
			// already started
			return
		}

		log.info("Starting logcat reader...")
		ideLogcatReader = IDELogcatReader().also { it.start() }
	}

	private fun stopLogcatReader() {
		log.info("Stopping logcat reader...")
		ideLogcatReader?.stop()
		ideLogcatReader = null
	}

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
