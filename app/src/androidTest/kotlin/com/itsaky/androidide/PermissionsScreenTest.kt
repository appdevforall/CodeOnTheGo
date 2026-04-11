package com.itsaky.androidide

import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.helper.advancePastWelcomeScreen
import com.itsaky.androidide.helper.grantAllRequiredPermissionsThroughOnboardingUi
import com.itsaky.androidide.helper.passPermissionsInfoSlideWithPrivacyDialog
import com.itsaky.androidide.screens.PermissionScreen
import com.itsaky.androidide.utils.PermissionsHelper
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionsScreenTest : TestCase() {

    @get:Rule
    val activityRule = activityScenarioRule<SplashActivity>()

    @After
    fun cleanUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm clear ${BuildConfig.APPLICATION_ID} && pm reset-permissions ${BuildConfig.APPLICATION_ID}",
        )
    }

    @Test
    fun test_permissionsScreen_greenCheckMarksAppearCorrectly() = run {
        step("Wait for app to start") {
            device.uiDevice.waitForIdle()
        }

        advancePastWelcomeScreen()

        passPermissionsInfoSlideWithPrivacyDialog()

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val required = PermissionsHelper.getRequiredPermissions(targetContext)

        step("Verify items on the Permission Screen") {
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

        step("Confirm Android reports all required permissions granted") {
            flakySafely(timeoutMs = 3_000) {
                assertTrue(PermissionsHelper.areAllPermissionsGranted(targetContext))
            }
        }

        step("Confirm that all grant actions are complete (buttons disabled)") {
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
    }
}
