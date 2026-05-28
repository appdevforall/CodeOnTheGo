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
private const val DEFAULT_TOOLBAR_BUTTON_TIMEOUT_MS = 10_000L
private const val SHORT_UI_TIMEOUT_MS = 2_000L
private const val DEFAULT_UI_TIMEOUT_MS = 3_000L
private const val CLOSE_DIALOG_TIMEOUT_MS = 10_000L
private const val INSTALLER_GONE_TIMEOUT_MS = 5_000L
private const val POLL_INTERVAL_MS = 1_000L
private const val PROJECT_STATUS_LOG_INTERVAL_MS = 15_000L
private const val CLOSE_DIALOG_RETRY_DELAY_MS = 2_000L
private const val PROJECT_MENU_FALLBACK_X_OFFSET = 48
private const val PROJECT_MENU_FALLBACK_Y_OFFSET = 70
private const val PROJECT_MENU_FALLBACK_Y = 140
private const val SYSTEM_BACK_BUTTON_X_RATIO = 0.24f
private const val SYSTEM_BACK_BUTTON_BOTTOM_OFFSET = 40
private const val PRESS_BACK_RETRY_COUNT = 2
private const val SYSTEM_BACK_TAP_RETRY_COUNT = 3
private const val FALLBACK_CLOSE_RETRY_COUNT = 4

private fun closeProjectLog(message: String) {
    Log.e(CLOSE_PROJECT_TAG, message)
    System.err.println("$CLOSE_PROJECT_TAG: $message")
}

class InitializationProjectAndCancelingBuildScenario(
    private val closeProjectAfterBuild: Boolean = true,
) : Scenario() {

    private fun TestContext<Unit>.clickToolbarButton(
        description: String,
        waitMs: Long = DEFAULT_TOOLBAR_BUTTON_TIMEOUT_MS,
    ) {
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
            if (okBtn.waitForExists(DEFAULT_UI_TIMEOUT_MS)) {
                clickFirstAccessibilityNodeByText("OK")
                d.waitForIdle()
            }
            // Wait for the editor UI to settle after dialog dismiss
            // before clicking the quick-run button
            val toolbar = d.findObject(
                UiSelector().resourceIdMatches(".*:id/editor_appBarLayout")
            )
            check(toolbar.waitForExists(DEFAULT_UI_TIMEOUT_MS)) { "Editor toolbar not found" }
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
                    if (now - lastLogAt > PROJECT_STATUS_LOG_INTERVAL_MS) {
                        closeProjectLog("Waiting for project initialization or enabled Quick run")
                        lastLogAt = now
                    }
                    d.waitForIdle(SHORT_UI_TIMEOUT_MS)
                    Thread.sleep(POLL_INTERVAL_MS)
                }
            }

            check(initialized) { "Project never initialized" }
            closeProjectLog("Project initialized or Quick run available")
            d.waitForIdle()
        }

        step("Click quick-run to build APK") {
            clickToolbarButton("Quick run")
        }

        step("Wait for quick-run outcome") {
            val d = device.uiDevice
            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            val quickRunDescription = targetContext.getString(R.string.cd_toolbar_quick_run)
            val installer = d.findObject(
                UiSelector().packageNameMatches(".*packageinstaller.*|.*permissioncontroller.*")
            )
            val quickRun = d.findObject(UiSelector().descriptionContains(quickRunDescription))
            val success = d.findObject(UiSelector().textContains("Build completed successfully"))
            val gradleSuccess = d.findObject(UiSelector().textContains("BUILD SUCCESSFUL"))
            val failure = d.findObject(UiSelector().textContains("Build failed"))
            val gradleFailure = d.findObject(UiSelector().textContains("BUILD FAILED"))
            val quickRunFailure = d.findObject(
                UiSelector().textContains(targetContext.getString(R.string.error_quick_run_failed))
            )

            closeProjectLog("Waiting for quick-run installer or build success")
            val deadline = System.currentTimeMillis() + QUICK_RUN_TIMEOUT_MS
            var lastLogAt = 0L
            var sawQuickRunDisabled = false

            while (System.currentTimeMillis() < deadline) {
                if (installer.exists()) {
                    closeProjectLog("Quick-run installer appeared; dismissing")
                    val cancel = d.findObject(UiSelector().textMatches("(?i)cancel"))
                    if (cancel.waitForExists(SHORT_UI_TIMEOUT_MS)) {
                        cancel.click()
                    } else {
                        d.pressBack()
                    }
                    runCatching { installer.waitUntilGone(INSTALLER_GONE_TIMEOUT_MS) }
                    d.waitForIdle()
                    return@step
                }

                if (success.exists() || gradleSuccess.exists()) {
                    closeProjectLog("Quick-run build success detected without installer; continuing")
                    d.waitForIdle()
                    return@step
                }

                runCatching { quickRun.exists() && quickRun.isEnabled }.getOrNull()?.let { enabled ->
                    if (!enabled) {
                        sawQuickRunDisabled = true
                    } else if (sawQuickRunDisabled) {
                        closeProjectLog("Quick-run action re-enabled; continuing")
                        d.waitForIdle()
                        return@step
                    }
                }

                check(!failure.exists() && !gradleFailure.exists() && !quickRunFailure.exists()) {
                    "Quick-run build failed"
                }

                val now = System.currentTimeMillis()
                if (now - lastLogAt > PROJECT_STATUS_LOG_INTERVAL_MS) {
                    closeProjectLog("Still waiting for quick-run installer or build success")
                    lastLogAt = now
                }

                d.waitForIdle(SHORT_UI_TIMEOUT_MS)
                Thread.sleep(POLL_INTERVAL_MS)
            }

            error("Quick-run timed out waiting for installer or build success")
        }

        if (closeProjectAfterBuild) {
            scenario(CloseProjectScenario())
        }
    }

    companion object {
        private const val PROJECT_INIT_TIMEOUT_MS = 5 * 60 * 1000L
        private const val QUICK_RUN_TIMEOUT_MS = 10 * 60 * 1000L
    }

    class CloseProjectScenario : Scenario() {
        override val steps: TestContext<Unit>.() -> Unit = {
            step("Dismiss post-build overlays") {
                val d = device.uiDevice
                val dismiss = d.findObject(UiSelector().text("Dismiss"))
                if (dismiss.waitForExists(DEFAULT_UI_TIMEOUT_MS)) {
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
                val saveAndClose = targetContext.getString(R.string.save_and_close)

                fun findCloseDialogButton() =
                    listOf(
                        UiSelector().text(closeWithoutSaving),
                        UiSelector().textContains("without saving"),
                        UiSelector().text(saveAndClose),
                    ).firstNotNullOfOrNull { selector ->
                        d.findObject(selector).takeIf { it.waitForExists(SHORT_UI_TIMEOUT_MS) && it.exists() }
                    }

                fun findCloseProjectControl() =
                    listOf(
                        UiSelector().description(closeProject),
                        UiSelector().descriptionContains(closeProject),
                        UiSelector().text(closeProject),
                        UiSelector().textContains(closeProject),
                    ).firstNotNullOfOrNull { selector ->
                        d.findObject(selector).takeIf { it.waitForExists(SHORT_UI_TIMEOUT_MS) && it.exists() }
                    }

                fun tapVisibleProjectMenuFallback() {
                    val toolbar = d.findObject(UiSelector().resourceIdMatches(".*:id/editor_appBarLayout"))
                    val bounds = toolbar
                        .takeIf { it.waitForExists(DEFAULT_UI_TIMEOUT_MS) && it.exists() }
                        ?.visibleBounds
                    val x = bounds?.let { it.left + PROJECT_MENU_FALLBACK_X_OFFSET } ?: PROJECT_MENU_FALLBACK_X_OFFSET
                    val y = bounds?.let { it.top + PROJECT_MENU_FALLBACK_Y_OFFSET } ?: PROJECT_MENU_FALLBACK_Y
                    d.click(x, y)
                    d.waitForIdle()
                }

                fun tapSystemBackButton() {
                    d.click(
                        (d.displayWidth * SYSTEM_BACK_BUTTON_X_RATIO).toInt(),
                        d.displayHeight - SYSTEM_BACK_BUTTON_BOTTOM_OFFSET,
                    )
                    d.waitForIdle()
                }

                var closeDialogButton = findCloseDialogButton()
                repeat(PRESS_BACK_RETRY_COUNT) { attempt ->
                    if (closeDialogButton == null) {
                        closeProjectLog("pressBack attempt ${attempt + 1}/$PRESS_BACK_RETRY_COUNT")
                        d.pressBack()
                        d.waitForIdle()
                        Thread.sleep(CLOSE_DIALOG_RETRY_DELAY_MS)
                        closeDialogButton = findCloseDialogButton()
                        closeProjectLog(
                            "close dialog after pressBack attempt ${attempt + 1}: ${closeDialogButton != null}"
                        )
                    }
                }

                if (closeDialogButton != null) {
                    closeDialogButton.click()
                    d.waitForIdle()
                    return@step
                }

                repeat(SYSTEM_BACK_TAP_RETRY_COUNT) { attempt ->
                    if (closeDialogButton == null) {
                        closeProjectLog("system Back button tap attempt ${attempt + 1}/$SYSTEM_BACK_TAP_RETRY_COUNT")
                        tapSystemBackButton()
                        closeDialogButton = findCloseDialogButton()
                        closeProjectLog(
                            "close dialog after system Back tap attempt ${attempt + 1}: ${closeDialogButton != null}"
                        )
                    }
                }

                if (closeDialogButton != null) {
                    closeDialogButton.click()
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
                        d.findObject(selector).takeIf { it.waitForExists(SHORT_UI_TIMEOUT_MS) && it.exists() }
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
                    d.findObject(selector).takeIf { it.waitForExists(CLOSE_DIALOG_TIMEOUT_MS) && it.exists() }
                }

                if (closeButton != null) {
                    closeButton.click()
                } else {
                    var projectClosed = false
                    repeat(FALLBACK_CLOSE_RETRY_COUNT) { attempt ->
                        if (projectClosed) {
                            return@repeat
                        }

                        closeProjectLog("fallback pressBack attempt ${attempt + 1}/$FALLBACK_CLOSE_RETRY_COUNT")
                        d.pressBack()
                        d.waitForIdle()

                        val fallbackCloseButton = listOf(
                            UiSelector().text(closeWithoutSaving),
                            UiSelector().textContains("without saving"),
                            UiSelector().text(saveAndClose),
                        ).firstNotNullOfOrNull { selector ->
                            d.findObject(selector).takeIf { it.waitForExists(DEFAULT_UI_TIMEOUT_MS) && it.exists() }
                        }

                        if (fallbackCloseButton != null) {
                            fallbackCloseButton.click()
                            projectClosed = true
                        }

                        if (!projectClosed && attempt == FALLBACK_CLOSE_RETRY_COUNT - 1) {
                            error("Close project dialog not found")
                        }
                    }
                }
                d.waitForIdle()
            }
        }
    }
}
