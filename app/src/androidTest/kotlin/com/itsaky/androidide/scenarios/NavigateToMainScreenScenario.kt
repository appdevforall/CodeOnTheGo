package com.itsaky.androidide.scenarios

import com.itsaky.androidide.helper.advancePastWelcomeScreen
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByText
import com.itsaky.androidide.helper.grantAllRequiredPermissionsThroughOnboardingUi
import com.itsaky.androidide.helper.logOnboardingNavigation
import com.itsaky.androidide.helper.passPermissionsInfoSlideWithPrivacyDialog
import com.itsaky.androidide.helper.waitForMainHomeOrEditorUi
import com.itsaky.androidide.screens.InstallToolsScreen
import com.itsaky.androidide.screens.PermissionScreen
import com.kaspersky.kaspresso.testcases.api.scenario.Scenario
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector

/**
 * Navigates from whatever the current onboarding state is to the main screen.
 *
 * Each step checks whether it's needed before acting, so this scenario is
 * idempotent — safe to call whether onboarding is at the welcome screen,
 * the permissions list, or already on the main screen.
 */
class NavigateToMainScreenScenario : Scenario() {

    override val steps: TestContext<Unit>.() -> Unit = {
        logOnboardingNavigation("NavigateToMainScreenScenario: starting")

        val d = device.uiDevice
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName

        // If we're already on the main screen, skip everything
        step("Check if already on main screen") {
            d.waitForIdle()
            val getStarted = d.findObject(UiSelector().resourceIdMatches(".*:id/getStarted"))
            val editor = d.findObject(UiSelector().resourceIdMatches(".*:id/editor_appBarLayout"))
            if (getStarted.waitForExists(1_000) || editor.exists()) {
                logOnboardingNavigation("Already on main screen — skipping onboarding")
                return@step
            }
        }

        // If the welcome screen NEXT button is visible, advance past it
        step("Advance past welcome screen (if showing)") {
            val nextBtn = d.findObject(UiSelector().descriptionContains("NEXT"))
            val permissionTitle = d.findObject(UiSelector().resourceIdMatches(".*:id/onboarding_title"))
            // Only advance if we appear to be on the welcome slide (NEXT visible but not yet on permissions)
            if (nextBtn.waitForExists(1_000)) {
                // Check if we're on the welcome slide vs permissions info slide
                // The welcome slide has a greeting title, not the permissions title
                val greetingTitle = d.findObject(UiSelector().resourceIdMatches(".*:id/title").textContains("Code on the Go"))
                if (greetingTitle.exists()) {
                    logOnboardingNavigation("Welcome screen visible — advancing")
                    advancePastWelcomeScreen()
                }
            }
        }

        // If the privacy dialog is showing, accept it
        step("Accept privacy dialog (if showing)") {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val accept = ctx.getString(com.itsaky.androidide.resources.R.string.privacy_disclosure_accept)
            val btn = d.findObject(UiSelector().text(accept))
            if (btn.waitForExists(1_000)) {
                logOnboardingNavigation("Privacy dialog visible — accepting")
                clickFirstAccessibilityNodeByText(accept)
                d.waitForIdle()
            }
        }

        // If NEXT button is visible (permissions info slide), advance to permissions list
        step("Advance past permissions info (if showing)") {
            val nextBtn = d.findObject(UiSelector().descriptionContains("NEXT"))
            if (nextBtn.waitForExists(1_000)) {
                logOnboardingNavigation("Permissions info slide — clicking NEXT")
                clickFirstAccessibilityNodeByText(
                    searchText = "NEXT",
                    errorLabel = "NEXT",
                    matchBy = { node ->
                        val desc = node.contentDescription?.toString() ?: ""
                        val text = node.text?.toString() ?: ""
                        desc.contains("NEXT", ignoreCase = true) || text.contains("NEXT", ignoreCase = true)
                    },
                )
                d.waitForIdle()
            }
        }

        // If the permission list is showing, grant all permissions
        step("Grant permissions (if on permission list)") {
            val permScreen = d.findObject(UiSelector().resourceIdMatches(".*:id/onboarding_items"))
            if (permScreen.waitForExists(2_000)) {
                val ctx = InstrumentationRegistry.getInstrumentation().targetContext
                val grantText = ctx.getString(com.itsaky.androidide.R.string.title_grant)
                val grantBtn = d.findObject(UiSelector().text(grantText))
                if (grantBtn.exists()) {
                    logOnboardingNavigation("Permission list visible with ungranted permissions — granting")
                    grantAllRequiredPermissionsThroughOnboardingUi()
                }
            }
        }

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
