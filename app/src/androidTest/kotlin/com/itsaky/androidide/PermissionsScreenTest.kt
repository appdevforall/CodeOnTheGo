package com.itsaky.androidide

import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.ext.junit.rules.activityScenarioRule
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.screens.OnboardingScreen
import com.itsaky.androidide.screens.PermissionScreen
import com.itsaky.androidide.screens.SystemPermissionsScreen
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PermissionsScreenTest : TestCase() {

    @get:Rule
    val activityRule = activityScenarioRule<SplashActivity>()

    @Test
    fun test_permissionsScreen_greenCheckMarksAppearCorrectly() = run {
        step("Click continue button on the Welcome Screen") {
            OnboardingScreen.nextButton {
                isVisible()
                isClickable()
                click()
            }
        }
        step("Verify items on the Permission Screen") {
            PermissionScreen {
                title {
                    isVisible()
                }
                subTitle {
                    isVisible()
                }
                assertEquals(2, rvPermissions.getSize())
                rvPermissions {
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
        step("Grant permissions") {
            PermissionScreen {
                rvPermissions {
                    childAt<PermissionScreen.PermissionItem>(0) {
                        grantButton.click()
                    }
                }

                SystemPermissionsScreen {
                    storagePermissionView {
                        click()
                    }
                }

                device.uiDevice.pressBack()

                rvPermissions {
                    childAt<PermissionScreen.PermissionItem>(1) {
                        grantButton.click()
                    }
                }

                SystemPermissionsScreen {
                    installPackagesPermission {
                        click()
                    }
                }

                device.uiDevice.pressBack()
            }
        }
        step("Confirm that all menu items don't have allow text") {
            PermissionScreen {
                rvPermissions {
                    childAt<PermissionScreen.PermissionItem>(0) {
                        grantButton {
                            isNotEnabled()
                            hasEmptyText()
                        }
                    }
                    childAt<PermissionScreen.PermissionItem>(1) {
                        grantButton {
                            hasEmptyText()
                            isNotEnabled()
                        }
                    }
                }
            }
        }
    }
}