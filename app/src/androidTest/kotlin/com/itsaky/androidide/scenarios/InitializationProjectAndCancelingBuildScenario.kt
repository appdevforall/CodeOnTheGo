package com.itsaky.androidide.scenarios

import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByDescription
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByText
import com.kaspersky.kaspresso.testcases.api.scenario.Scenario
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

class InitializationProjectAndCancelingBuildScenario : Scenario() {

    private fun TestContext<Unit>.clickToolbarButton(description: String, waitMs: Long = 10_000) {
        val d = device.uiDevice
        val btn = d.findObject(UiSelector().descriptionContains(description))
        check(btn.waitForExists(waitMs)) { "Toolbar button '$description' not found" }
        clickFirstAccessibilityNodeByDescription(description)
        d.waitForIdle()
    }

    override val steps: TestContext<Unit>.() -> Unit = {
        step("Dismiss first build notice and start init") {
            val d = device.uiDevice
            val okBtn = d.findObject(UiSelector().text("OK").className("android.widget.Button"))
            if (okBtn.waitForExists(3_000)) {
                clickFirstAccessibilityNodeByText("OK")
                d.waitForIdle()
            }
            // Wait for the editor UI to settle after dialog dismiss
            // before clicking the quick-run button
            val toolbar = d.findObject(
                UiSelector().resourceIdMatches(".*:id/editor_appBarLayout")
            )
            check(toolbar.waitForExists(3_000)) { "Editor toolbar not found" }
            d.waitForIdle()
            // The button may be "Sync project" or "Quick run" depending on state
            clickToolbarButton("Sync project")
        }

        step("Wait for project initialized") {
            val d = device.uiDevice
            val status = d.findObject(UiSelector().text("Project initialized"))
            check(status.waitForExists(120_000)) { "Project never initialized" }
            println("Project initialized")
            d.waitForIdle()
        }

        step("Click quick-run to build APK") {
            clickToolbarButton("Quick run")
        }

        step("Wait for APK install offer") {
            // After a successful build, the system package installer appears.
            // If it never appears, the build failed.
            val d = device.uiDevice
            val installer = d.findObject(
                UiSelector().packageNameMatches(".*packageinstaller.*|.*permissioncontroller.*")
            )
            check(installer.waitForExists(120_000)) {
                "APK install offer never appeared — build may have failed"
            }
            println("APK install offer appeared — build succeeded")
            // Dismiss it — we don't need to install
            d.pressBack()
            d.waitForIdle()
        }

        step("Close project") {
            val d = device.uiDevice
            d.pressBack()
            d.waitForIdle()

            val selector = UiSelector().text("Save files and close project")
            if (!d.findObject(selector).waitForExists(3_000)) {
                d.pressBack()
                d.waitForIdle()
                check(d.findObject(selector).waitForExists(3_000)) {
                    "Close project dialog not found"
                }
            }
            clickFirstAccessibilityNodeByText("Save files and close project")
            d.waitForIdle()
        }
    }
}