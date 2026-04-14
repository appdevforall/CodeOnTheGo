package com.itsaky.androidide

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.helper.advancePastWelcomeScreen
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByText
import com.itsaky.androidide.helper.grantAllRequiredPermissionsThroughOnboardingUi
import com.itsaky.androidide.helper.waitForMainHomeOrEditorUi
import com.itsaky.androidide.resources.R as ResourcesR
import com.itsaky.androidide.screens.OnboardingScreen
import com.itsaky.androidide.screens.PermissionScreen
import com.itsaky.androidide.screens.PermissionsInfoScreen
import com.itsaky.androidide.utils.PermissionsHelper
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Single continuous E2E test that drives the app from first launch through
 * onboarding, project creation, builds, and beyond.
 *
 * The activity launches once and stays alive. Each stage is a Kaspresso
 * `step()` so failures report exactly which stage broke.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest : TestCase() {

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val acceptText: String
        get() = targetContext.getString(ResourcesR.string.privacy_disclosure_accept)

    private val learnMoreText: String
        get() = targetContext.getString(ResourcesR.string.privacy_disclosure_learn_more)

    private val dialogTitle: String
        get() = targetContext.getString(ResourcesR.string.privacy_disclosure_title)

    @Test
    fun test_endToEnd() = run {

        // ── Launch ──

        step("Launch app") {
            ActivityScenario.launch(SplashActivity::class.java)
            Thread.sleep(1000)
        }

        // ── Welcome Screen ──

        step("Verify welcome screen") {
            OnboardingScreen {
                greetingTitle.isVisible()
                greetingSubtitle.isVisible()
                nextButton {
                    isVisible()
                    isClickable()
                }
            }
        }

        advancePastWelcomeScreen()

        // ── Permissions Info Screen ──

        step("Verify privacy disclosure dialog") {
            val d = device.uiDevice
            val title = d.findObject(UiSelector().text(dialogTitle))
            assertTrue("Dialog title missing", title.waitForExists(2_000))
            assertTrue("Accept button missing", d.findObject(UiSelector().text(acceptText)).exists())
            assertTrue("Learn more button missing", d.findObject(UiSelector().text(learnMoreText)).exists())
        }

        step("Accept privacy disclosure") {
            clickFirstAccessibilityNodeByText(acceptText)
            device.uiDevice.waitForIdle()
        }

        step("Verify permissions info content") {
            flakySafely(timeoutMs = 2_000) {
                PermissionsInfoScreen {
                    introText { isVisible() }
                    permissionsList { isVisible() }
                }
            }
        }

        step("Verify NEXT button on permissions info") {
            OnboardingScreen.nextButton { isVisible(); isClickable() }
        }

        step("Verify privacy dialog does not reappear") {
            assertFalse(
                "Dialog should not reappear",
                device.uiDevice.findObject(UiSelector().text(dialogTitle)).exists(),
            )
        }

        // ── Permissions Screen ──

        step("Advance to permissions list") {
            val d = device.uiDevice
            val nextObj = d.findObject(UiSelector().descriptionContains("NEXT"))
            if (!nextObj.waitForExists(3_000)) {
                throw AssertionError("NEXT button not found on permissions info slide")
            }
            clickFirstAccessibilityNodeByText(
                searchText = "NEXT",
                errorLabel = "NEXT",
                matchBy = { node ->
                    val desc = node.contentDescription?.toString() ?: ""
                    val text = node.text?.toString() ?: ""
                    desc.contains("NEXT", ignoreCase = true) || text.contains("NEXT", ignoreCase = true)
                },
            )
            d.waitForIdle()
        }

        val required = PermissionsHelper.getRequiredPermissions(targetContext)

        step("Verify all permission items") {
            flakySafely(timeoutMs = 3_000) {
                PermissionScreen {
                    title { isVisible() }
                    subTitle { isVisible() }
                    rvPermissions {
                        isVisible()
                        isDisplayed()
                    }
                    assertEquals(required.size, rvPermissions.getSize())

                    rvPermissions {
                        required.forEachIndexed { index, item ->
                            childAt<PermissionScreen.PermissionItem>(index) {
                                title {
                                    isVisible()
                                    hasText(item.title)
                                }
                                description {
                                    isVisible()
                                    hasText(item.description)
                                }
                                grantButton {
                                    isVisible()
                                    isClickable()
                                    hasText(R.string.title_grant)
                                }
                            }
                        }
                    }
                }
            }
        }

        grantAllRequiredPermissionsThroughOnboardingUi()

        step("Confirm all permissions granted") {
            flakySafely(timeoutMs = 3_000) {
                assertTrue(PermissionsHelper.areAllPermissionsGranted(targetContext))
            }
        }

        step("Confirm all grant buttons disabled") {
            device.uiDevice.waitForIdle()
            PermissionScreen {
                rvPermissions {
                    required.indices.forEach { index ->
                        childAt<PermissionScreen.PermissionItem>(index) {
                            grantButton {
                                isNotEnabled()
                            }
                        }
                    }
                }
            }
        }

        step("Tap Finish installation") {
            // The button is in the gesture exclusion zone — use accessibility click
            clickFirstAccessibilityNodeByText("Finish installation")
        }

        step("Wait for IDE setup to complete") {
            waitForMainHomeOrEditorUi(
                device.uiDevice,
                maxWaitMs = 60_000L,
            )
        }

        // ── Future phases (project creation, builds, preferences) go here ──
    }
}
