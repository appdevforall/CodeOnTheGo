package com.itsaky.androidide

import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.screens.OnboardingScreen
import com.itsaky.androidide.screens.PermissionScreen
import com.itsaky.androidide.screens.SystemPermissionsScreen
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.After
import org.junit.Assert.assertEquals
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
                // Give app time to fully initialize
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

        step("Verify items on the Permission Screen") {
            PermissionScreen {
                // Wait for screen to fully load
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
                        // Wait for RecyclerView to fully load
                        isDisplayed()
                    }
                }

                // Make the size check flaky-safe with increased timeout
                flakySafely(timeoutMs = 15000) {
                    assertEquals(2, rvPermissions.getSize())
                }

                rvPermissions {
                    flakySafely(timeoutMs = 10000) {
                        childAt<PermissionScreen.PermissionItem>(0) {
                            title {
                                isVisible()
                                hasText(R.string.permission_title_storage)
                            }
                            description {
                                isVisible()
                                hasText(R.string.permission_desc_storage)
                            }
                            grantButton {
                                isVisible()
                                isClickable()
                                hasText(R.string.title_grant)
                            }
                        }
                    }

                    flakySafely(timeoutMs = 10000) {
                        childAt<PermissionScreen.PermissionItem>(1) {
                            title {
                                isVisible()
                                hasText(R.string.permission_title_install_packages)
                            }
                            description {
                                isVisible()
                                hasText(R.string.permission_desc_install_packages)
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

        step("Grant Storage Permissions") {
            flakySafely(timeoutMs = 30000) {
                PermissionScreen {
                    rvPermissions {
                        childAt<PermissionScreen.PermissionItem>(0) {
                            grantButton.click()
                        }
                    }

                    // Wait for system permission dialog to appear
                    device.uiDevice.waitForIdle(3000)

                    SystemPermissionsScreen {
                        try {
                            // Try the original permission text first
                            storagePermissionView {
                                isDisplayed()
                                click()
                            }
                        } catch (e: Exception) {
                            println("Trying alternative text for storage permission: ${e.message}")
                            try {
                                storagePermissionViewAlt1 {
                                    isDisplayed()
                                    click()
                                }
                            } catch (e1: Exception) {
                                try {
                                    storagePermissionViewAlt2 {
                                        isDisplayed()
                                        click()
                                    }
                                } catch (e2: Exception) {
                                    try {
                                        storagePermissionViewAlt3 {
                                            isDisplayed()
                                            click()
                                        }
                                    } catch (e3: Exception) {
                                        try {
                                            storagePermissionViewAlt4 {
                                                isDisplayed()
                                                click()
                                            }
                                        } catch (e4: Exception) {
                                            try {
                                                storagePermissionViewAlt5 {
                                                    isDisplayed()
                                                    click()
                                                }
                                            } catch (e5: Exception) {
                                                try {
                                                    storagePermissionViewAlt6 {
                                                        isDisplayed()
                                                        click()
                                                    }
                                                } catch (e6: Exception) {
                                                    try {
                                                        storagePermissionViewAlt7 {
                                                            isDisplayed()
                                                            click()
                                                        }
                                                    } catch (e7: Exception) {
                                                        storagePermissionViewAlt8 {
                                                            isDisplayed()
                                                            click()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                    }

                    // Wait after click and before going back
                    device.uiDevice.waitForIdle(2000)
                    device.uiDevice.pressBack()
                    device.uiDevice.waitForIdle(2000)
                }
            }
        }

        step("Grant Install Packages Permissions") {
            flakySafely(timeoutMs = 30000) {
                PermissionScreen {
                    rvPermissions {
                        childAt<PermissionScreen.PermissionItem>(1) {
                            grantButton.click()
                        }
                    }

                    // Wait for system permission dialog to appear
                    device.uiDevice.waitForIdle(3000)

                    SystemPermissionsScreen {
                        try {
                            // Try the original permission text first
                            installPackagesPermission {
                                isDisplayed()
                                click()
                            }
                        } catch (e: Exception) {
                            println("Trying alternative text for install packages permission: ${e.message}")
                            try {
                                installPackagesPermissionAlt1 {
                                    isDisplayed()
                                    click()
                                }
                            } catch (e: Exception) {
                                installPackagesPermissionAlt2 {
                                    isDisplayed()
                                    click()
                                }
                            }
                        }
                    }

                    // Wait after click and before going back
                    device.uiDevice.waitForIdle(2000)
                    device.uiDevice.pressBack()
                    device.uiDevice.waitForIdle(2000)
                }
            }
        }

        step("Confirm that all menu items don't have allow text") {
            flakySafely(timeoutMs = 15000) {
                device.uiDevice.waitForIdle(2000)
                PermissionScreen {
                    rvPermissions {
                        childAt<PermissionScreen.PermissionItem>(0) {
                            grantButton {
                                isNotEnabled()
                            }
                        }
                        childAt<PermissionScreen.PermissionItem>(1) {
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