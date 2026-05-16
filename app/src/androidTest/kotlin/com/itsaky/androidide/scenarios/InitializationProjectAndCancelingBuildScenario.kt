package com.itsaky.androidide.scenarios

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.R
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByDescription
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByText
import com.kaspersky.kaspresso.testcases.api.scenario.Scenario
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

private const val CLOSE_PROJECT_TAG = "CloseProjectScenario"

private fun closeProjectLog(message: String) {
    Log.e(CLOSE_PROJECT_TAG, message)
    System.err.println("$CLOSE_PROJECT_TAG: $message")
}

class InitializationProjectAndCancelingBuildScenario(
    private val closeProjectAfterBuild: Boolean = true,
) : Scenario() {

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
            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            val initializedText = targetContext.getString(R.string.msg_project_initialized)
            val quickRunDescription = targetContext.getString(R.string.cd_toolbar_quick_run)
            val deadline = System.currentTimeMillis() + PROJECT_INIT_TIMEOUT_MS
            var lastLogAt = 0L
            var initialized = false

            while (System.currentTimeMillis() < deadline && !initialized) {
                val status = d.findObject(UiSelector().text(initializedText))
                val quickRun = d.findObject(UiSelector().descriptionContains(quickRunDescription))

                initialized = status.exists() ||
                    runCatching { quickRun.exists() && quickRun.isEnabled }.getOrDefault(false)

                if (!initialized) {
                    val now = System.currentTimeMillis()
                    if (now - lastLogAt > 15_000L) {
                        closeProjectLog("Waiting for project initialization or enabled Quick run")
                        lastLogAt = now
                    }
                    d.waitForIdle(2_000)
                    Thread.sleep(1_000)
                }
            }

            check(initialized) { "Project never initialized" }
            closeProjectLog("Project initialized or Quick run available")
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

        if (closeProjectAfterBuild) {
            scenario(CloseProjectScenario())
        }
    }

    companion object {
        private const val PROJECT_INIT_TIMEOUT_MS = 5 * 60 * 1000L
    }

    class CloseProjectScenario : Scenario() {
        override val steps: TestContext<Unit>.() -> Unit = {
            step("Dismiss post-build overlays") {
                val d = device.uiDevice
                val dismiss = d.findObject(UiSelector().text("Dismiss"))
                if (dismiss.waitForExists(3_000)) {
                    clickFirstAccessibilityNodeByText("Dismiss")
                    d.waitForIdle()
                }
            }

            step("Close project") {
                closeProjectLog("Close project step entered")
                val d = device.uiDevice
                val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
                val openDrawer = targetContext.getString(R.string.cd_drawer_open)
                val closeDrawer = targetContext.getString(R.string.cd_drawer_close)
                val closeProject = targetContext.getString(R.string.title_close_project)
                val closeWithoutSaving = targetContext.getString(R.string.close_without_saving)
                val saveAndClose = "Save files and close project"

                fun findCloseDialogButton() =
                    listOf(
                        UiSelector().text(closeWithoutSaving),
                        UiSelector().textContains("without saving"),
                        UiSelector().text(saveAndClose),
                    ).firstNotNullOfOrNull { selector ->
                        d.findObject(selector).takeIf { it.waitForExists(2_000) && it.exists() }
                    }

                fun findCloseProjectControl() =
                    listOf(
                        UiSelector().description(closeProject),
                        UiSelector().descriptionContains(closeProject),
                        UiSelector().text(closeProject),
                        UiSelector().textContains(closeProject),
                    ).firstNotNullOfOrNull { selector ->
                        d.findObject(selector).takeIf { it.waitForExists(2_000) && it.exists() }
                    }

                fun tapVisibleProjectMenuFallback() {
                    val toolbar = d.findObject(UiSelector().resourceIdMatches(".*:id/editor_appBarLayout"))
                    val bounds = toolbar.takeIf { it.waitForExists(3_000) && it.exists() }?.visibleBounds
                    val x = bounds?.let { it.left + 48 } ?: 48
                    val y = bounds?.let { it.top + 70 } ?: 140
                    d.click(x, y)
                    d.waitForIdle()
                }

                fun tapSystemBackButton() {
                    d.click((d.displayWidth * 0.24f).toInt(), d.displayHeight - 40)
                    d.waitForIdle()
                }

                var closeDialogButton = findCloseDialogButton()
                repeat(2) { attempt ->
                    if (closeDialogButton == null) {
                        closeProjectLog("pressBack attempt ${attempt + 1}/2")
                        d.pressBack()
                        d.waitForIdle()
                        Thread.sleep(2_000)
                        closeDialogButton = findCloseDialogButton()
                        closeProjectLog("close dialog after pressBack attempt ${attempt + 1}: ${closeDialogButton != null}")
                    }
                }

                if (closeDialogButton != null) {
                    closeDialogButton?.click()
                    d.waitForIdle()
                    return@step
                }

                repeat(3) { attempt ->
                    if (closeDialogButton == null) {
                        closeProjectLog("system Back button tap attempt ${attempt + 1}/3")
                        tapSystemBackButton()
                        closeDialogButton = findCloseDialogButton()
                        closeProjectLog("close dialog after system Back tap attempt ${attempt + 1}: ${closeDialogButton != null}")
                    }
                }

                if (closeDialogButton != null) {
                    closeDialogButton?.click()
                    d.waitForIdle()
                    return@step
                }

                runCatching {
                    val drawer = listOf(
                        UiSelector().description(openDrawer),
                        UiSelector().description(closeDrawer),
                        UiSelector().descriptionContains(openDrawer),
                        UiSelector().descriptionContains(closeDrawer),
                    ).firstNotNullOfOrNull { selector ->
                        d.findObject(selector).takeIf { it.waitForExists(2_000) && it.exists() }
                    }

                    if (drawer != null) {
                        drawer.click()
                    } else {
                        clickFirstAccessibilityNodeByDescription(openDrawer)
                    }
                    d.waitForIdle()
                }

                var closeProjectControl = findCloseProjectControl()
                if (closeProjectControl == null) {
                    tapVisibleProjectMenuFallback()
                    closeProjectControl = findCloseProjectControl()
                }

                check(closeProjectControl != null) { "Close project control not found" }
                closeProjectControl.click()
                d.waitForIdle()

                val closeButton = listOf(
                    UiSelector().text(closeWithoutSaving),
                    UiSelector().textContains("without saving"),
                    UiSelector().text(saveAndClose),
                ).firstNotNullOfOrNull { selector ->
                    d.findObject(selector).takeIf { it.waitForExists(10_000) && it.exists() }
                }

                if (closeButton != null) {
                    closeButton.click()
                } else {
                    var projectClosed = false
                    repeat(4) { attempt ->
                        if (projectClosed) {
                            return@repeat
                        }

                        closeProjectLog("fallback pressBack attempt ${attempt + 1}/4")
                        d.pressBack()
                        d.waitForIdle()

                        val fallbackCloseButton = listOf(
                            UiSelector().text(closeWithoutSaving),
                            UiSelector().textContains("without saving"),
                            UiSelector().text(saveAndClose),
                        ).firstNotNullOfOrNull { selector ->
                            d.findObject(selector).takeIf { it.waitForExists(3_000) && it.exists() }
                        }

                        if (fallbackCloseButton != null) {
                            fallbackCloseButton.click()
                            projectClosed = true
                        }

                        if (!projectClosed && attempt == 3) {
                            error("Close project dialog not found")
                        }
                    }
                }
                d.waitForIdle()
            }
        }
    }
}
