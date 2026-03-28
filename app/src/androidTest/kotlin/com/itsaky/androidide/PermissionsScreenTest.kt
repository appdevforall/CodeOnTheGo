package com.itsaky.androidide

import android.Manifest
import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.helper.grantDisplayOverOtherAppsUi
import com.itsaky.androidide.helper.grantInstallUnknownAppsUi
import com.itsaky.androidide.helper.grantPostNotificationsUi
import com.itsaky.androidide.helper.grantStorageManageAllFilesUi
import com.itsaky.androidide.helper.passPermissionsInfoSlideWithPrivacyDialog
import com.itsaky.androidide.screens.OnboardingScreen
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
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm clear ${BuildConfig.APPLICATION_ID} && pm reset-permissions ${BuildConfig.APPLICATION_ID}")
    }

    @Test
    fun test_permissionsScreen_greenCheckMarksAppearCorrectly() = run {
        step("Wait for app to start") {
            flakySafely(timeoutMs = 10000) {
                device.uiDevice.waitForIdle(5000)
            }
        }

        step("Click continue button on the Welcome Screen") {
            flakySafely(timeoutMs = 15000) {
                OnboardingScreen.nextButton {
                    isVisible()
                    isClickable()
                    click()
                }
            }
        }

        passPermissionsInfoSlideWithPrivacyDialog()

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val required = PermissionsHelper.getRequiredPermissions(targetContext)
        val appLabel =
            targetContext.applicationInfo.loadLabel(targetContext.packageManager).toString()

        step("Verify items on the Permission Screen") {
            PermissionScreen {
                flakySafely(timeoutMs = 10000) {
                    title {
                        isVisible()
                    }
                }

                flakySafely(timeoutMs = 8000) {
                    subTitle {
                        isVisible()
                    }
                }

                flakySafely(timeoutMs = 15000) {
                    rvPermissions {
                        isVisible()
                        isDisplayed()
                    }
                }

                flakySafely(timeoutMs = 15000) {
                    assertEquals(required.size, rvPermissions.getSize())
                }

                rvPermissions {
                    required.forEachIndexed { index, item ->
                        flakySafely(timeoutMs = 10000) {
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

        required.forEachIndexed { index, item ->
            step("Grant: ${targetContext.getString(item.title)}") {
                flakySafely(timeoutMs = 120_000) {
                    PermissionScreen {
                        rvPermissions {
                            childAt<PermissionScreen.PermissionItem>(index) {
                                grantButton {
                                    isVisible()
                                    click()
                                }
                            }
                        }
                    }
                    when (item.permission) {
                        Manifest.permission.POST_NOTIFICATIONS -> {
                            device.grantPostNotificationsUi()
                        }

                        Manifest.permission_group.STORAGE -> {
                            device.grantStorageManageAllFilesUi()
                        }

                        Manifest.permission.REQUEST_INSTALL_PACKAGES -> {
                            device.grantInstallUnknownAppsUi()
                        }

                        Manifest.permission.SYSTEM_ALERT_WINDOW -> {
                            device.grantDisplayOverOtherAppsUi(
                                listOf(
                                    appLabel,
                                    targetContext.getString(R.string.app_name),
                                    targetContext.packageName,
                                ),
                                targetContext,
                            )
                        }

                        else -> {
                            throw IllegalStateException("Unknown permission row: ${item.permission}")
                        }
                    }
                }
            }
        }

        step("Confirm Android reports all required permissions granted") {
            flakySafely(timeoutMs = 20_000) {
                assertTrue(
                    PermissionsHelper.areAllPermissionsGranted(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                    ),
                )
            }
        }

        step("Confirm every row shows granted state") {
            flakySafely(timeoutMs = 15_000) {
                device.uiDevice.waitForIdle(2000)
                PermissionScreen {
                    rvPermissions {
                        for (index in required.indices) {
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

        step("Finish installation") {
            flakySafely(timeoutMs = 20_000) {
                PermissionScreen {
                    finishInstallationButton {
                        isVisible()
                        isEnabled()
                        isClickable()
                        click()
                    }
                }
            }
        }
    }
}
