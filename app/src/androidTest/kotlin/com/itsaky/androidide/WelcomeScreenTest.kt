package com.itsaky.androidide

import android.util.Log
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.screens.OnboardingScreen
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

class WelcomeScreenTest : TestCase() {

    @get:Rule
    val activityRule = activityScenarioRule<SplashActivity>()

    @After
    fun cleanUp() {
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm clear ${BuildConfig.APPLICATION_ID}")
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm reset-permissions ${BuildConfig.APPLICATION_ID}")
        } catch (e: Exception) {
            Log.e("WelcomeScreenTest", "Error during cleanup: ${e.message}")
        }
    }

    @Test
    fun test_welcomeScreen_itemsAppearCorrectly_UiAutomatorFileDump() = run {
        step("Manually dump views to file before checking") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dumpDir = context.cacheDir
            val dumpFile = File(dumpDir, "view_dump_BeforeCheck_${System.currentTimeMillis()}.xml")

            Log.d(
                "WelcomeScreenTest", "Attempting UI Automator dump to: ${dumpFile.absolutePath}"
            )
            try {
                device.uiDevice.dumpWindowHierarchy(dumpFile)
                Log.i(
                    "WelcomeScreenTest",
                    "Dump successful. Retrieve with: adb pull ${dumpFile.absolutePath}"
                )
            } catch (e: IOException) {
                Log.e("WelcomeScreenTest", "Failed to execute UI Automator dump to file", e)
            } catch (e: Exception) {
                Log.e("WelcomeScreenTest", "An unexpected error occurred during dump", e)
            }
        }

        step("Verify Onboarding Screen elements") {
            OnboardingScreen {
                greetingTitle.isVisible()
                greetingSubtitle.isVisible()
                nextButton {
                    isVisible()
                    isClickable()
                }
            }
        }
    }


    @Test
    fun test_welcomeScreen_itemsAppearCorrectly_UiAutomatorFileDumpOnFailure() = run {
        step("Attempt to verify Onboarding Screen elements") {
            try {
                OnboardingScreen {
                    greetingTitle.isVisible()
                    greetingSubtitle.isVisible()
                    nextButton {
                        isVisible()
                        isClickable()
                    }
                }
                Log.d("WelcomeScreenTest", "All assertions passed.")
            } catch (e: Exception) {
                Log.e("WelcomeScreenTest", "Assertion failed: ${e.message}", e)

                // Dump hierarchy on failure
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val dumpDir = context.cacheDir
                val dumpFile =
                    File(dumpDir, "view_dump_OnFailure_${System.currentTimeMillis()}.xml")

                Log.d(
                    "WelcomeScreenTest",
                    "Dumping hierarchy via UI Automator to: ${dumpFile.absolutePath}"
                )
                try {
                    device.uiDevice.dumpWindowHierarchy(dumpFile)
                    Log.i(
                        "WelcomeScreenTest",
                        "Dump successful on failure. Retrieve with: adb pull ${dumpFile.absolutePath}"
                    )
                } catch (dumpError: IOException) {
                    Log.e(
                        "WelcomeScreenTest",
                        "Also failed to dump views to file after failure",
                        dumpError
                    )
                } catch (dumpError: Exception) {
                    Log.e(
                        "WelcomeScreenTest",
                        "An unexpected error occurred during dump after failure",
                        dumpError
                    )
                }
                throw e
            }
        }
    }
}