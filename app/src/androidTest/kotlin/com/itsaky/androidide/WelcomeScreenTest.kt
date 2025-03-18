package com.itsaky.androidide

import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.screens.OnboardingScreen
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.After
import org.junit.Rule
import org.junit.Test

class WelcomeScreenTest : TestCase() {

    @get:Rule
    val activityRule = activityScenarioRule<SplashActivity>()

    @After
    fun cleanUp() {
        // Wrap the reset-permissions command with '|| true' so that any failure doesn't propagate.
        val command = "pm clear ${BuildConfig.APPLICATION_ID} && (pm reset-permissions ${BuildConfig.APPLICATION_ID} || true)"
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    }

    @Test
    fun test_welcomeScreen_itemsAppearCorrectly() {
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