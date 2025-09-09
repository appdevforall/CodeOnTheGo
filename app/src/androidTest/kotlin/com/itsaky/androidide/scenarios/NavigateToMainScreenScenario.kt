package com.itsaky.androidide.scenarios

import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.helper.grantOverlayPermission
import com.itsaky.androidide.helper.grantStoragePermissions
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
            step("Grant Storage Permissions") {
                flakySafely(30000) {
                    PermissionScreen {
                        rvPermissions {
                            childAt<PermissionScreen.PermissionItem>(0) {
                                grantButton.click()
                            }
                        }

                        grantStoragePermissions(device.uiDevice)
                        device.uiDevice.waitForIdle(2000)

                        device.uiDevice.pressBack()
                        device.uiDevice.waitForIdle(2000)
                    }
                }
            }

            step("Grant Install Packages Permissions") {
                flakySafely(30000) {
                    PermissionScreen {
                        rvPermissions {
                            childAt<PermissionScreen.PermissionItem>(1) {
                                grantButton.click()
                            }
                        }

                        device.uiDevice.waitForIdle(3000)

                        SystemPermissionsScreen {
                            try {
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

                        device.uiDevice.waitForIdle(2000)
                        device.uiDevice.pressBack()
                        device.uiDevice.waitForIdle(2000)
                    }
                }
            }

            step("Grant Overlay Window permission") {
                flakySafely(30000) {
                    PermissionScreen {
                        rvPermissions {
                            childAt<PermissionScreen.PermissionItem>(2) {
                                grantButton.click()
                            }
                        }

                        grantOverlayPermission(device.uiDevice)

                        device.uiDevice.waitForIdle(2000)
                        device.uiDevice.pressBack()
                    }
                }
            }

            step("Navigate away from permissions screen") {
                flakySafely(20000) {
                    OnboardingScreen.nextButton {
                        isVisible()
                        isClickable()
                        click()
                    }
                }
            }
        } else {
            println("skip permissions")
        }

        step("Click continue button on the Install Tools Screen") {
            flakySafely(600000) {
                device.uiDevice.waitForIdle(30000)
                // Wait for tools installation to complete and reach last slide
                Thread.sleep(10000)
                println("Waiting for tools installation to complete...")
                InstallToolsScreen.doneButton {
                    flakySafely(500000) {
                        println("Waiting for DONE button to become visible and clickable...")
                        isVisible()
                        isClickable()
                        click()
                        println("DONE button clicked successfully")
                    }
                }
                // Wait for navigation to complete after clicking DONE
                device.uiDevice.waitForIdle(15000)
                println("Waiting for navigation to complete after DONE click...")
            }
        }

        step("Handle notifications permissions if they appear") {
            flakySafely(60000) {
                try {
                    if (device.permissions.isDialogVisible()) {
                        println("Notification permission dialog found, denying...")
                        device.permissions.denyViaDialog()
                    } else {
                        println("No notification permission dialog found")
                    }
                } catch (e: Exception) {
                    println("No notification permission dialog found, continuing...")
                }
            }
        }

        step("Wait for main screen to fully load") {
            flakySafely(60000) {
                device.uiDevice.waitForIdle(20000)
                println("Waiting for main screen to fully load...")
                // Give additional time for main screen to fully initialize
                Thread.sleep(10000)
                println("Main screen should now be loaded")
            }
        }
    }
}