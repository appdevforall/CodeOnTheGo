package com.itsaky.androidide.scenarios

import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.screens.InstallToolsScreen
import com.itsaky.androidide.screens.OnboardingScreen
import com.itsaky.androidide.screens.PermissionScreen
import com.itsaky.androidide.screens.SystemPermissionsScreen
import com.kaspersky.kaspresso.testcases.api.scenario.Scenario
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

class NavigateToMainScreenScenario : Scenario() {

    override val steps: TestContext<Unit>.() -> Unit = {
        step("Click continue button on the Welcome Screen") {
            OnboardingScreen.nextButton {
                isVisible()
                isClickable()
                click()
            }
        }
        val permissionsScreen = device.uiDevice.findObject(UiSelector().text("Permissions"))

        if (permissionsScreen.exists()) {
            step("Grant storage and install packages permissions") {
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
                        try {
                            installPackagesPermission {
                                click()
                            }
                        } catch (e: Exception) {
                            println("Trying alternative text for install packages permission")
                            try {
                                installPackagesPermissionAlt1 {
                                    click()
                                }
                            } catch (e1: Exception) {
                                println("Trying second alternative text for install packages permission")
                                installPackagesPermissionAlt2 {
                                    click()
                                }
                            }
                        }
                    }

                    device.uiDevice.pressBack()
                }
                OnboardingScreen.nextButton {
                    isVisible()
                    isClickable()
                    click()
                }
            }
        } else {
            println("skip permissions")
        }

        step("Click continue button on the Install Tools Screen") {
            flakySafely(120000) {
                device.uiDevice.waitForIdle(10000)
                InstallToolsScreen.doneButton {
                    flakySafely(20000) {
                        isVisible()
                        isEnabled()
                        isClickable()
                        click()
                    }
                }
            }
        }

        step("Decline notifications permissions") {
            flakySafely(1000000) {
                device.permissions.isDialogVisible()
                device.permissions.denyViaDialog()
            }
        }
    }
}