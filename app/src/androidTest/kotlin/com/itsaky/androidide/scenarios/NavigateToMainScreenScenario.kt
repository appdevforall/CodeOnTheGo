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
            step("Debug and Grant Storage Permissions") {
                flakySafely(30000) {
                    PermissionScreen {
                        device.uiDevice.waitForIdle(5000)
                        println("=== PERMISSION DEBUG: Starting Storage Permission (Index 0) ===")
                        
                        rvPermissions {
                            println("DEBUG: Total permissions in RecyclerView: ${getSize()}")
                            
                            childAt<PermissionScreen.PermissionItem>(0) {
                                title {
                                    println("DEBUG: Permission at index 0 - checking title")
                                }
                                description {
                                    println("DEBUG: Permission at index 0 - checking description")
                                }
                                grantButton {
                                    println("DEBUG: Clicking grant button for permission at index 0")
                                    click()
                                }
                            }
                        }

                        println("DEBUG: Calling grantStoragePermissions helper")
                        grantStoragePermissions(device.uiDevice)
                        device.uiDevice.waitForIdle(2000)

                        println("DEBUG: Pressing back after storage permission")
                        device.uiDevice.pressBack()
                        device.uiDevice.waitForIdle(2000)
                        println("=== PERMISSION DEBUG: Storage Permission Complete ===")
                    }
                }
            }

            step("Debug and Grant Install Packages Permissions") {
                flakySafely(30000) {
                    PermissionScreen {
                        println("=== PERMISSION DEBUG: Starting Install Packages Permission (Index 1) ===")
                        
                        rvPermissions {
                            childAt<PermissionScreen.PermissionItem>(1) {
                                title {
                                    println("DEBUG: Permission at index 1 - checking title")
                                }
                                description {
                                    println("DEBUG: Permission at index 1 - checking description")
                                }
                                grantButton {
                                    println("DEBUG: Clicking grant button for permission at index 1")
                                    click()
                                }
                            }
                        }

                        device.uiDevice.waitForIdle(3000)

                        SystemPermissionsScreen {
                            try {
                                println("DEBUG: Trying main install packages permission")
                                installPackagesPermission {
                                    isDisplayed()
                                    click()
                                }
                            } catch (e: Exception) {
                                println("DEBUG: Main install packages permission failed: ${e.message}")
                                println("DEBUG: Trying alternative text for install packages permission")
                                try {
                                    installPackagesPermissionAlt1 {
                                        isDisplayed()
                                        click()
                                    }
                                } catch (e: Exception) {
                                    println("DEBUG: Alt1 install packages permission failed: ${e.message}")
                                    println("DEBUG: Trying second alternative text for install packages permission")
                                    installPackagesPermissionAlt2 {
                                        isDisplayed()
                                        click()
                                    }
                                }
                            }
                        }

                        device.uiDevice.waitForIdle(2000)
                        println("DEBUG: Pressing back after install packages permission")
                        device.uiDevice.pressBack()
                        device.uiDevice.waitForIdle(2000)
                        println("=== PERMISSION DEBUG: Install Packages Permission Complete ===")
                    }
                }
            }

            step("Debug and Grant Overlay Window permission") {
                flakySafely(30000) {
                    PermissionScreen {
                        println("=== PERMISSION DEBUG: Starting Overlay Permission (Index 2) ===")
                        
                        rvPermissions {
                            childAt<PermissionScreen.PermissionItem>(2) {
                                title {
                                    println("DEBUG: Permission at index 2 - checking title")
                                }
                                description {
                                    println("DEBUG: Permission at index 2 - checking description")
                                }
                                grantButton {
                                    println("DEBUG: Clicking grant button for permission at index 2")
                                    click()
                                }
                            }
                        }

                        println("DEBUG: Calling grantOverlayPermission helper")
                        grantOverlayPermission(device.uiDevice)

                        device.uiDevice.waitForIdle(2000)
                        println("DEBUG: Pressing back after overlay permission")
                        device.uiDevice.pressBack()
                        println("=== PERMISSION DEBUG: Overlay Permission Complete ===")
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