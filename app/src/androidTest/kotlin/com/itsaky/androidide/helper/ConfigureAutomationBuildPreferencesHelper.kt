package com.itsaky.androidide.helper

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.preferences.internal.BuildPreferences
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

private const val TAG = "AutomationBuildPrefs"
private const val ARG_BUILD_OFFLINE = "androidide.build.offline"

/**
 * Optionally override the IDE Gradle offline preference for automation runs.
 *
 * When the instrumentation argument is omitted, the automation leaves the IDE
 * code default/current preference untouched. Pass `androidide.build.offline`
 * explicitly to force a deterministic online or offline mode for the run.
 */
fun TestContext<Unit>.configureAutomationBuildPreferences() {
    val args = InstrumentationRegistry.getArguments()
    val offlineModeArg = args.getString(ARG_BUILD_OFFLINE)

    step("Configure automation build preferences") {
        if (offlineModeArg == null) {
            Log.i(TAG, "Gradle offline mode arg '$ARG_BUILD_OFFLINE' omitted; leaving IDE preference unchanged")
            return@step
        }

        val offlineMode = checkNotNull(offlineModeArg.toBooleanStrictOrNull()) {
            "Instrumentation argument '$ARG_BUILD_OFFLINE' must be 'true' or 'false'"
        }

        BuildPreferences.isOfflineEnabled = offlineMode
        Log.i(TAG, "Gradle offline mode set to $offlineMode (arg '$ARG_BUILD_OFFLINE')")
    }
}
