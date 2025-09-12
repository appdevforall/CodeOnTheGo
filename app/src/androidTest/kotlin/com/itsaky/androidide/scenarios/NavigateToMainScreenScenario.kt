import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.helper.grantNotifications
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
        println("DEBUG: Checking if permissions screen exists: ${permissionsScreen.exists()}")

        if (permissionsScreen.exists()) {
            println("DEBUG: Permissions screen found, granting 4 permissions in order: Notification -> Storage -> Install Packages -> Overlay")

            step("Grant Notification Permissions") {
                flakySafely(15000) {
                    println("DEBUG: Granting Notification permission (index 0)")
                    PermissionScreen {
                        rvPermissions {
                            childAt<PermissionScreen.PermissionItem>(0) {
                                grantButton.click()
                            }
                        }

                        grantNotifications(device.uiDevice)
                        device.uiDevice.waitForIdle(1000)
                        device.uiDevice.pressBack()
                        device.uiDevice.waitForIdle(1000)
                    }
                    println("DEBUG: Notification permission granted")
                }
            }

            step("Grant Storage Permissions") {
                flakySafely(15000) {
                    println("DEBUG: Granting Storage permission (index 1)")
                    PermissionScreen {
                        rvPermissions {
                            childAt<PermissionScreen.PermissionItem>(1) {
                                grantButton.click()
                            }
                        }

                        grantStoragePermissions(device.uiDevice)
                        device.uiDevice.waitForIdle(1000)
                        device.uiDevice.pressBack()
                        device.uiDevice.waitForIdle(1000)
                    }
                    println("DEBUG: Storage permission granted")
                }
            }

            step("Grant Install Packages Permissions") {
                flakySafely(15000) {
                    println("DEBUG: Granting Install packages permission (index 2)")
                    PermissionScreen {
                        rvPermissions {
                            childAt<PermissionScreen.PermissionItem>(2) {
                                grantButton.click()
                            }
                        }

                        device.uiDevice.waitForIdle(2000)

                        SystemPermissionsScreen {
                            try {
                                installPackagesPermission {
                                    isDisplayed()
                                    click()
                                }
                            } catch (e: Exception) {
                                println("DEBUG: Trying alternative text for install packages permission")
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

                        device.uiDevice.waitForIdle(1000)
                        device.uiDevice.pressBack()
                        device.uiDevice.waitForIdle(1000)
                    }
                    println("DEBUG: Install packages permission granted")
                }
            }

            step("Grant Overlay Window permission") {
                flakySafely(15000) {
                    println("DEBUG: Granting Overlay permission (index 3)")
                    PermissionScreen {
                        rvPermissions {
                            childAt<PermissionScreen.PermissionItem>(3) {
                                grantButton.click()
                            }
                        }

                        grantOverlayPermission(device.uiDevice)
                        device.uiDevice.waitForIdle(1000)
                        device.uiDevice.pressBack()
                    }
                    println("DEBUG: Overlay permission granted")
                }
            }

            step("Navigate to Install Tools Screen") {
                flakySafely(timeoutMs = 15000) {
                    println("DEBUG: All 4 permissions granted, clicking next button to navigate")

                    OnboardingScreen.nextButton {
                        isVisible()
                        isClickable()
                        click()
                    }

                    device.uiDevice.waitForIdle(3000)

                    try {
                        InstallToolsScreen.doneButton {
                            println("DEBUG: Successfully navigated to InstallToolsScreen")
                            isDisplayed()
                        }
                    } catch (e: Exception) {
                        println("DEBUG: Navigation failed, still on permissions screen. Retrying...")

                        OnboardingScreen.nextButton {
                            click()
                        }

                        device.uiDevice.waitForIdle(3000)

                        InstallToolsScreen.doneButton {
                            println("DEBUG: Navigation successful on retry")
                            isDisplayed()
                        }
                    }
                }
            }
        } else {
            println("DEBUG: Permissions screen not found, skipping permissions")
        }

        step("Click continue button on the Install Tools Screen") {
            flakySafely(600000) {
                device.uiDevice.waitForIdle(10000)
                println("DEBUG: Waiting for tools installation to complete...")
                InstallToolsScreen.doneButton {
                    flakySafely(550000) {
                        println("DEBUG: Waiting for DONE button to become visible and clickable...")
                        isVisible()
                        isEnabled()
                        isClickable()
                        click()
                        println("DEBUG: DONE button clicked successfully")
                    }
                }
                device.uiDevice.waitForIdle(5000)
            }
        }

        step("Wait for main screen to fully load") {
            flakySafely(30000) {
                device.uiDevice.waitForIdle(10000)
                println("DEBUG: Waiting for main screen to fully load...")
                Thread.sleep(5000)
                println("DEBUG: Main screen should now be loaded")
            }
        }
    }
}
