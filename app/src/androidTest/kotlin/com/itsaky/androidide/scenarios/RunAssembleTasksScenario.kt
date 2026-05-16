package com.itsaky.androidide.scenarios

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.R
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByDescription
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeParentByText
import com.kaspersky.kaspresso.testcases.api.scenario.Scenario
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

private const val RUN_ASSEMBLE_TAG = "RunAssembleTasks"

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
            if (dismiss.waitForExists(3_000)) {
                dismiss.click()
                d.waitForIdle()
            }
        }

        step("Open Run tasks dialog") {
            val d = device.uiDevice
            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            val runTasksDescription = targetContext.getString(R.string.cd_toolbar_run_gradle_tasks)
            val toolbar = d.findObject(UiSelector().resourceIdMatches(".*:id/editor_appBarLayout"))
            check(toolbar.waitForExists(10_000)) { "Editor toolbar not found" }
            clickFirstAccessibilityNodeByDescription(runTasksDescription)
            d.waitForIdle()

            val title = targetContext.getString(R.string.title_run_tasks)
            check(d.findObject(UiSelector().text(title)).waitForExists(10_000)) {
                "Run tasks dialog did not open"
            }
        }

        step("Filter assemble tasks") {
            val d = device.uiDevice
            val search = d.findObject(UiSelector().className("android.widget.EditText"))
            check(search.waitForExists(10_000)) { "Run tasks search field not found" }
            search.setText("assemble")
            d.waitForIdle()
        }

        tasks.forEach { task ->
            step("Select Gradle task $task") {
                val d = device.uiDevice
                check(d.findObject(UiSelector().text(task)).waitForExists(20_000)) {
                    "Task not found in Run tasks dialog: $task"
                }
                clickFirstAccessibilityNodeParentByText(task)
                d.waitForIdle()
            }
        }

        step("Confirm and run selected Gradle tasks") {
            val d = device.uiDevice
            val runButton = d.findObject(UiSelector().resourceIdMatches(".*:id/exec"))
            check(runButton.waitForExists(10_000)) { "Run tasks execute button not found" }
            runButton.click()
            d.waitForIdle()

            check(d.findObject(UiSelector().textContains(":app:assemble")).waitForExists(10_000)) {
                "Run tasks confirmation did not show selected tasks"
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
                if (now - lastLogAt > 10_000L) {
                    runAssembleLog("Still waiting for selected assemble tasks to finish")
                    lastLogAt = now
                }

                Thread.sleep(1_000)
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
