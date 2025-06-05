package com.itsaky.androidide

import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.screens.OnboardingScreen
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WelcomeScreenTest : TestCase() {

    @get:Rule
    val activityRule = activityScenarioRule<SplashActivity>()

    @After
    fun cleanUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm clear ${BuildConfig.APPLICATION_ID} && pm reset-permissions ${BuildConfig.APPLICATION_ID}")
    }


    @Test
    fun test_welcomeScreen_itemsAppearCorrectly() {
        // The SplashActivity immediately starts OnboardingActivity and finishes itself
        // This might cause a race condition, so we need to wait for OnboardingActivity to be fully shown
        Thread.sleep(1000) // Wait for activity transitions to complete

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