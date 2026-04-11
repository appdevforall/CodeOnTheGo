package com.itsaky.androidide.scenarios

import com.itsaky.androidide.helper.advancePastWelcomeScreen
import com.itsaky.androidide.helper.grantAllRequiredPermissionsThroughOnboardingUi
import com.itsaky.androidide.helper.logOnboardingNavigation
import com.itsaky.androidide.helper.passPermissionsInfoSlideWithPrivacyDialog
import com.itsaky.androidide.helper.waitForMainHomeOrEditorUi
import com.itsaky.androidide.screens.InstallToolsScreen
import com.itsaky.androidide.screens.PermissionScreen
import com.kaspersky.kaspresso.testcases.api.scenario.Scenario
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

class NavigateToMainScreenScenario : Scenario() {

    override val steps: TestContext<Unit>.() -> Unit = {
        logOnboardingNavigation("NavigateToMainScreenScenario: first step")

        advancePastWelcomeScreen()

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

        step("After Finish installation: optional AppIntro Done, then wait for IDE setup -> main UI") {
            logOnboardingNavigation(
                "Permissions Finish starts in-app IDE setup; AppIntro R.id.done is often absent — waiting for main UI",
            )
            runCatching {
                flakySafely(timeoutMs = 12_000) {
                    InstallToolsScreen.doneButton {
                        isVisible()
                        click()
                    }
                }
            }.fold(
                onSuccess = { logOnboardingNavigation("Clicked legacy AppIntro Done (optional)") },
                onFailure = {
                    logOnboardingNavigation(
                        "No AppIntro Done within 12s (expected): ${it.javaClass.simpleName} ${it.message}",
                    )
                },
            )
            waitForMainHomeOrEditorUi(device.uiDevice, maxWaitMs = 300_000L)
        }

        step("Decline runtime permission dialog if still shown") {
            runCatching {
                flakySafely(timeoutMs = 8000) {
                    device.permissions.denyViaDialog()
                }
            }
        }
    }
}
