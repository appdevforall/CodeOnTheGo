package com.itsaky.androidide.scenarios

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.R
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByDescription
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeParentByText
import com.kaspersky.kaspresso.testcases.api.scenario.Scenario
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

private const val RUN_ASSEMBLE_TAG = "RunAssembleTasks"
private const val SHORT_UI_TIMEOUT_MS = 2_000L
private const val DEFAULT_UI_TIMEOUT_MS = 3_000L
private const val INSTALLER_GONE_TIMEOUT_MS = 5_000L
private const val RUN_TASKS_DIALOG_TIMEOUT_MS = 10_000L
private const val TASK_SELECTION_TIMEOUT_MS = 20_000L
private const val EDITOR_TOOLBAR_TIMEOUT_MS = 30_000L
private const val BUILD_LOG_INTERVAL_MS = 10_000L
private const val POLL_INTERVAL_MS = 1_000L

private fun runAssembleLog(message: String) {
    Log.e(RUN_ASSEMBLE_TAG, message)
    System.err.println("$RUN_ASSEMBLE_TAG: $message")
}

class RunAssembleTasksScenario(
    private val tasks: List<String> = DEFAULT_ASSEMBLE_TASKS,
) : Scenario() {

    override val steps: TestContext<Unit>.() -> Unit = {
        step("Dismiss post-build overlays before running assemble tasks") {
            val d = device.uiDevice
            val dismiss = d.findObject(UiSelector().text("Dismiss"))
            if (dismiss.waitForExists(DEFAULT_UI_TIMEOUT_MS)) {
                dismiss.click()
                d.waitForIdle()
            }
            val installer = d.findObject(
                UiSelector().packageNameMatches(".*packageinstaller.*|.*permissioncontroller.*")
            )
            if (installer.exists()) {
                runAssembleLog("Package installer still visible before assemble tasks; dismissing")
                val cancel = d.findObject(UiSelector().textMatches("(?i)cancel"))
                if (cancel.waitForExists(SHORT_UI_TIMEOUT_MS)) {
                    cancel.click()
                } else {
                    d.pressBack()
                }
                runCatching { installer.waitUntilGone(INSTALLER_GONE_TIMEOUT_MS) }
                d.waitForIdle()
            }
        }

        step("Open Run tasks dialog") {
            val d = device.uiDevice
            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            val runTasksDescription = targetContext.getString(R.string.cd_toolbar_run_gradle_tasks)
            val toolbar = d.findObject(UiSelector().resourceIdMatches(".*:id/editor_appBarLayout"))
            check(toolbar.waitForExists(EDITOR_TOOLBAR_TIMEOUT_MS)) { "Editor toolbar not found" }
            clickFirstAccessibilityNodeByDescription(runTasksDescription)
            d.waitForIdle()

            val title = targetContext.getString(R.string.title_run_tasks)
            check(d.findObject(UiSelector().text(title)).waitForExists(RUN_TASKS_DIALOG_TIMEOUT_MS)) {
                "Run tasks dialog did not open"
            }
        }

        step("Filter assemble tasks") {
            val d = device.uiDevice
            val search = d.findObject(UiSelector().className("android.widget.EditText"))
            check(search.waitForExists(RUN_TASKS_DIALOG_TIMEOUT_MS)) { "Run tasks search field not found" }
            search.setText("assemble")
            d.waitForIdle()
        }

        tasks.forEach { task ->
            step("Select Gradle task $task") {
                val d = device.uiDevice
                var taskNode = d.findObject(UiSelector().text(task))
                if (!taskNode.waitForExists(DEFAULT_UI_TIMEOUT_MS)) {
                    UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(task)
                    taskNode = d.findObject(UiSelector().text(task))
                }
                check(taskNode.waitForExists(TASK_SELECTION_TIMEOUT_MS)) {
                    "Task not found in Run tasks dialog: $task"
                }
                clickFirstAccessibilityNodeParentByText(task)
                d.waitForIdle()
            }
        }

        step("Confirm and run selected Gradle tasks") {
            val d = device.uiDevice
            val runButton = d.findObject(UiSelector().resourceIdMatches(".*:id/exec"))
            check(runButton.waitForExists(RUN_TASKS_DIALOG_TIMEOUT_MS)) { "Run tasks execute button not found" }
            runButton.click()
            d.waitForIdle()

            tasks.forEach { task ->
                check(d.findObject(UiSelector().textContains(task)).waitForExists(RUN_TASKS_DIALOG_TIMEOUT_MS)) {
                    "Run tasks confirmation missing selected task: $task"
                }
            }

            runButton.click()
            d.waitForIdle()
            runAssembleLog("Selected assemble tasks submitted")
        }

        step("Wait for selected Gradle tasks to finish") {
            runAssembleLog("Waiting for selected assemble tasks to finish")
            val d = device.uiDevice
            val success = d.findObject(UiSelector().textContains("Build completed successfully"))
            val gradleSuccess = d.findObject(UiSelector().textContains("BUILD SUCCESSFUL"))
            val failure = d.findObject(UiSelector().textContains("Build failed"))
            val gradleFailure = d.findObject(UiSelector().textContains("BUILD FAILED"))

            val deadline = System.currentTimeMillis() + BUILD_TIMEOUT_MS
            var lastLogAt = 0L
            var completed = false

            while (System.currentTimeMillis() < deadline && !completed) {
                if (success.exists() || gradleSuccess.exists()) {
                    completed = true
                    break
                }

                check(!failure.exists() && !gradleFailure.exists()) {
                    "Selected Gradle tasks failed"
                }

                val now = System.currentTimeMillis()
                if (now - lastLogAt > BUILD_LOG_INTERVAL_MS) {
                    runAssembleLog("Still waiting for selected assemble tasks to finish")
                    lastLogAt = now
                }

                Thread.sleep(POLL_INTERVAL_MS)
                d.waitForIdle()
            }

            check(completed) {
                "Timed out waiting for selected Gradle tasks to complete"
            }
            runAssembleLog("Selected assemble tasks finished; continuing to close project")
            d.waitForIdle()
        }
    }

    companion object {
        val DEFAULT_ASSEMBLE_TASKS = listOf(
            ":app:assemble",
            ":app:assembleAndroidTest",
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
            ":app:assembleDebugUnitTest",
            ":app:assembleRelease",
            ":app:assembleReleaseUnitTest",
            ":app:assembleUnitTest",
        )

        private const val BUILD_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
