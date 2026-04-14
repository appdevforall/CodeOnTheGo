package com.itsaky.androidide

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.helper.advancePastWelcomeScreen
import com.itsaky.androidide.resources.R as ResourcesR
import com.itsaky.androidide.screens.OnboardingScreen
import com.itsaky.androidide.screens.PermissionsInfoScreen
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionsInfoScreenTest : TestCase() {

    @get:Rule
    val activityRule = activityScenarioRule<SplashActivity>()

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val acceptText: String
        get() = targetContext.getString(
            ResourcesR.string.privacy_disclosure_accept,
        )

    private val learnMoreText: String
        get() = targetContext.getString(
            ResourcesR.string.privacy_disclosure_learn_more,
        )

    private val dialogTitle: String
        get() = targetContext.getString(
            ResourcesR.string.privacy_disclosure_title,
        )

    @After
    fun cleanUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm clear ${BuildConfig.APPLICATION_ID} && pm reset-permissions ${BuildConfig.APPLICATION_ID}",
        )
    }

    private fun accessibilityClickByText(text: String): Boolean {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation
            .rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        var result = false
        try {
            for (node in nodes) {
                if (!result && node.text?.toString().equals(text, ignoreCase = true)) {
                    result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                node.recycle()
            }
        } finally {
            root.recycle()
        }
        return result
    }

    @Test
    fun test_permissionsInfoScreen_displaysCorrectly() = run {
        step("Wait for app") {
            device.uiDevice.waitForIdle()
        }

        advancePastWelcomeScreen()

        step("Verify privacy disclosure dialog") {
            val d = device.uiDevice
            val title = d.findObject(UiSelector().text(dialogTitle))
            assertTrue("Dialog title missing", title.waitForExists(2_000))
            assertTrue("Accept button missing", d.findObject(UiSelector().text(acceptText)).exists())
            assertTrue("Learn more button missing", d.findObject(UiSelector().text(learnMoreText)).exists())
        }

        step("Accept privacy disclosure") {
            assertTrue("Click failed", accessibilityClickByText(acceptText))
            device.uiDevice.waitForIdle()
        }

        step("Verify permissions info content is visible") {
            flakySafely(timeoutMs = 2_000) {
                PermissionsInfoScreen {
                    introText { isVisible() }
                    permissionsList { isVisible() }
                }
            }
        }

        step("Next button present") {
            OnboardingScreen.nextButton { isVisible(); isClickable() }
        }
    }

    @Test
    fun test_privacyDialog_dismissedAfterAccept() = run {
        step("Wait for app") {
            device.uiDevice.waitForIdle()
        }

        advancePastWelcomeScreen()

        step("Accept privacy disclosure") {
            val btn = device.uiDevice.findObject(UiSelector().text(acceptText))
            assertTrue("Accept button missing", btn.waitForExists(2_000))
            assertTrue("Click failed", accessibilityClickByText(acceptText))
            device.uiDevice.waitForIdle()
        }

        step("Verify info content visible (dialog dismissed)") {
            flakySafely(timeoutMs = 2_000) {
                PermissionsInfoScreen { introText { isVisible() } }
            }
        }

        step("Verify dialog not shown again") {
            assertFalse(
                "Dialog should not reappear",
                device.uiDevice.findObject(UiSelector().text(dialogTitle)).exists(),
            )
        }
    }
}
