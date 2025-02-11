package com.itsaky.androidide.scenarios

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
                    installPackagesPermission {
                        click()
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
        step("Click continue button on the Install Tools Screen") {
            InstallToolsScreen.doneButton {
                isVisible()
                isClickable()
                click()
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