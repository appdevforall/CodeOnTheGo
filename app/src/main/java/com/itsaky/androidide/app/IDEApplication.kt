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

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.StrictMode
import android.view.Display
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Observer
import androidx.work.Operation
import androidx.work.WorkManager
import com.blankj.utilcode.util.ThrowableUtils.getFullStackTrace
import com.google.android.material.color.DynamicColors
import com.itsaky.androidide.BuildConfig
import com.itsaky.androidide.activities.CrashHandlerActivity
import com.itsaky.androidide.activities.SecondaryScreen
import com.itsaky.androidide.activities.editor.IDELogcatReader
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.editor.schemes.IDEColorSchemeProvider
import com.itsaky.androidide.eventbus.events.preferences.PreferenceChangeEvent
import com.itsaky.androidide.events.AppEventsIndex
import com.itsaky.androidide.events.EditorEventsIndex
import com.itsaky.androidide.events.LspApiEventsIndex
import com.itsaky.androidide.events.LspJavaEventsIndex
import com.itsaky.androidide.events.ProjectsApiEventsIndex
import com.itsaky.androidide.idetooltips.IDETooltipDao
import com.itsaky.androidide.idetooltips.IDETooltipDatabase
import com.itsaky.androidide.localHTTPServer.LocalServerUtil
import com.itsaky.androidide.preferences.internal.DevOpsPreferences
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.preferences.internal.StatPreferences
import com.itsaky.androidide.resources.localization.LocaleProvider
import com.itsaky.androidide.stats.StatUploadWorker
import com.itsaky.androidide.syntax.colorschemes.SchemeAndroidIDE
import com.itsaky.androidide.treesitter.TreeSitter
import com.itsaky.androidide.ui.themes.IDETheme
import com.itsaky.androidide.ui.themes.IThemeManager
import com.itsaky.androidide.utils.RecyclableObjectPool
import com.itsaky.androidide.utils.VMUtils
import com.itsaky.androidide.utils.flashError
import com.termux.app.TermuxApplication
import com.termux.shared.reflection.ReflectionUtils
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.lang.Thread.UncaughtExceptionHandler
import kotlin.system.exitProcess

class IDEApplication : TermuxApplication() {

    private var uncaughtExceptionHandler: UncaughtExceptionHandler? = null
    private var ideLogcatReader: IDELogcatReader? = null
    private var localServerUtil: LocalServerUtil? = null

    private val applicationScope = CoroutineScope(SupervisorJob())

    init {
        if (!VMUtils.isJvm()) {
            TreeSitter.loadLibrary()
        }

        RecyclableObjectPool.DEBUG = BuildConfig.DEBUG
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        instance = this
        uncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, th -> handleCrash(thread, th) }

        super.onCreate()

        if (BuildConfig.DEBUG) {
            val builder = StrictMode.VmPolicy.Builder()
            StrictMode.setVmPolicy(builder.build())
            //TODO JMT
//            StrictMode.setVmPolicy(
//                StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy()).penaltyLog().detectAll()
//                    .build()
//            )
            if (DevOpsPreferences.dumpLogs) {
                startLogcatReader()
            }

            checkForSecondDisplay()

            //Start the local HTTP server for CoGo tooltips
            val localServerUtil = LocalServerUtil()
            localServerUtil.startServer(6174)
        }

        EventBus.builder().addIndex(AppEventsIndex()).addIndex(EditorEventsIndex())
            .addIndex(ProjectsApiEventsIndex()).addIndex(LspApiEventsIndex())
            .addIndex(LspJavaEventsIndex()).installDefaultEventBus(true)

        EventBus.getDefault().register(this)

        AppCompatDelegate.setDefaultNightMode(GeneralPreferences.uiMode)

        if (IThemeManager.getInstance().getCurrentTheme() == IDETheme.MATERIAL_YOU) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        EditorColorScheme.setDefault(SchemeAndroidIDE.newInstance(null))

        ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
        GlobalScope.launch {
            IDEColorSchemeProvider.init()
        }

        idetooltipDao = IDETooltipDatabase.getDatabase(this).idetooltipDao()

        //Trigger a lightweight database access to force initialization
        applicationScope.launch {
            idetooltipDao.getCount()
        }
    }

    private fun handleCrash(thread: Thread, th: Throwable) {
        writeException(th)

        try {
            localServerUtil!!.stopServer()
            val intent = Intent()
            intent.action = CrashHandlerActivity.REPORT_ACTION
            intent.putExtra(CrashHandlerActivity.TRACE_KEY, getFullStackTrace(th))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            if (uncaughtExceptionHandler != null) {
                uncaughtExceptionHandler!!.uncaughtException(thread, th)
            }

            exitProcess(1)
        } catch (error: Throwable) {
            log.error("Unable to show crash handler activity", error)
        }
    }

    fun showChangelog() {
        val intent = Intent(Intent.ACTION_VIEW)
        var version = BuildInfo.VERSION_NAME_SIMPLE
        if (!version.startsWith('v')) {
            version = "v${version}"
        }
        intent.data = Uri.parse("${BuildInfo.REPO_URL}/releases/tag/${version}")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (th: Throwable) {
            log.error("Unable to start activity to show changelog", th)
            flashError("Unable to start activity")
        }
    }

    fun reportStatsIfNecessary() {

//        if (!StatPreferences.statOptIn) {
//            log.info("Stat collection is disabled.")
//            return
//        }
//
//        val constraints =
//            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
//        val request =
//            PeriodicWorkRequestBuilder<StatUploadWorker>(Duration.ofHours(24)).setInputData(
//                AndroidIDEStats.statData.toInputData()
//            ).setConstraints(constraints)
//                .addTag(StatUploadWorker.WORKER_WORK_NAME).build()
//
//        val workManager = WorkManager.getInstance(this)
//
//        log.info("reportStatsIfNecessary: Enqueuing StatUploadWorker...")
//        val operation = workManager.enqueueUniquePeriodicWork(
//            StatUploadWorker.WORKER_WORK_NAME,
//            ExistingPeriodicWorkPolicy.UPDATE, request
//        )
//
//        operation.state.observeForever(object : Observer<Operation.State> {
//            override fun onChanged(value: Operation.State) {
//                operation.state.removeObserver(this)
//                log.debug("reportStatsIfNecessary: WorkManager enqueue result: {}", value)
//            }
//        })
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
        if (event.key == StatPreferences.STAT_OPT_IN) {
            if (enabled == true) {
                reportStatsIfNecessary()
            } else {
                cancelStatUploadWorker()
            }
        } else if (event.key == DevOpsPreferences.KEY_DEVOPTS_DEBUGGING_DUMPLOGS) {
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
            val localeListCompat = selectedLocale?.let {
                LocaleListCompat.create(LocaleProvider.getLocale(selectedLocale))
            } ?: LocaleListCompat.getEmptyLocaleList()

            AppCompatDelegate.setApplicationLocales(localeListCompat)
        }
    }

    private fun cancelStatUploadWorker() {
        log.info("Opted-out of stat collection. Cancelling StatUploadWorker if enqueued...")
        val operation = WorkManager.getInstance(this)
            .cancelUniqueWork(StatUploadWorker.WORKER_WORK_NAME)
        operation.state.observeForever(object : Observer<Operation.State> {
            override fun onChanged(value: Operation.State) {
                operation.state.removeObserver(this)
                log.info("StatUploadWorker: Cancellation result state: {}", value)
            }
        })
    }

    private fun checkForSecondDisplay() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        var secondDisplay: Display? = null
        for (display in displays) {
            if (display.displayId != Display.DEFAULT_DISPLAY) {
                // This is a secondary display
                secondDisplay = display
            }
        }
        if (secondDisplay != null) {
            val presentation = SecondaryScreen(this, secondDisplay!!)
            presentation.show()
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(IDEApplication::class.java)

        @JvmStatic
        lateinit var instance: IDEApplication
            private set

        lateinit var idetooltipDao: IDETooltipDao
            private set
    }

}
