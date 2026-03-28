package com.itsaky.androidide.scenarios

import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.helper.ensureOnHomeScreenBeforeCreateProject
import com.itsaky.androidide.helper.grantAllRequiredPermissionsThroughOnboardingUi
import com.itsaky.androidide.helper.passPermissionsInfoSlideWithPrivacyDialog
import com.itsaky.androidide.screens.InstallToolsScreen
import com.itsaky.androidide.screens.OnboardingScreen
import com.itsaky.androidide.screens.PermissionScreen
import com.kaspersky.kaspresso.testcases.api.scenario.Scenario
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

class NavigateToMainScreenScenario : Scenario() {

    override val steps: TestContext<Unit>.() -> Unit = {
        step("Click continue button on the Welcome Screen") {
            try {
                OnboardingScreen.nextButton {
                    click()
                }
            } catch (e: Exception) {
                val nextByDesc =
                    device.uiDevice.findObject(UiSelector().descriptionContains("NEXT"))
                val nextByText = device.uiDevice.findObject(UiSelector().textContains("Next"))
                val continueByText =
                    device.uiDevice.findObject(UiSelector().textContains("Continue"))
                when {
                    nextByDesc.exists() -> nextByDesc.click()
                    nextByText.exists() -> nextByText.click()
                    continueByText.exists() -> continueByText.click()
                    else -> println("Next/Continue button not found on onboarding: ${e.message}")
                }
            }
        }
        passPermissionsInfoSlideWithPrivacyDialog()

        step("Wait for onboarding permission list") {
            flakySafely(timeoutMs = 30_000) {
                PermissionScreen {
                    title {
                        isVisible()
                    }
                    rvPermissions {
                        isDisplayed()
                    }
                }
            }
        }

        grantAllRequiredPermissionsThroughOnboardingUi()

        step("Finish installation (leave permission screen)") {
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

        step("Decline runtime permission dialog if still shown") {
            runCatching {
                flakySafely(timeoutMs = 8000) {
                    device.permissions.denyViaDialog()
                }
            }
        }

        ensureOnHomeScreenBeforeCreateProject()
    }
}
