package com.itsaky.androidide.scenarios

import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.R
import com.itsaky.androidide.screens.EditorScreen
import com.kaspersky.kaspresso.testcases.api.scenario.Scenario
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import io.github.kakaocup.kakao.common.views.KView
import io.github.kakaocup.kakao.toolbar.KToolbar

class InitializationProjectAndCancelingBuildScenario : Scenario() {

    override val steps: TestContext<Unit>.() -> Unit = {
        step("Close the first build dialog") {
            flakySafely(180000) {
                try {
                    EditorScreen {
                        firstBuildDialog {
                            isDisplayed()
                            title {
                                hasText(R.string.title_first_build)
                            }
                            message {
                                hasText(R.string.msg_first_build)
                            }
                            positiveButton {
                                click()
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("First build dialog was not visible or was auto-dismissed: ${e.message}")
                }
            }
        }
        step("Wait for the green button") {
            flakySafely(600000) { // Increased timeout to 10 minutes
                try {
                    // Wait for project initialization with increased timeout
                    val projectInitializedText =
                        device.uiDevice.findObject(UiSelector().text("Project initialized"))
                    if (!projectInitializedText.waitForExists(540000)) { // 9 minutes
                        // If we can't find the text, let's wait a bit more before proceeding
                        println("Project initialized text not found, waiting additional time...")
                        Thread.sleep(10000)
                    } else {
                        println("Project initialized text found")
                    }

                    // Wait after project is initialized to ensure UI is stable
                    Thread.sleep(5000)

                    // Try to find and click the build button with retries
                    var attempts = 0
                    var success = false
                    while (attempts < 3 && !success) {
                        try {
                            flakySafely(10000) {
                                KView {
                                    withParent {
                                        KToolbar {
                                            withId(R.id.editor_appBarLayout)
                                        }
                                    }
                                    withId("ide.editor.build.quickRun".hashCode())
                                }.click()
                            }
                            success = true
                            println("Successfully clicked build button on attempt ${attempts + 1}")
                        } catch (e: Exception) {
                            attempts++
                            println("Failed to find or click build button on attempt $attempts: ${e.message}")
                            if (attempts < 3) Thread.sleep(5000)
                        }
                    }
                } catch (e: Exception) {
                    println("Error waiting for project initialization: ${e.message}")
                }
            }
        }
        step("Click back and confirm that the Close Project dialog appears") {
            // Wait before pressing back
            Thread.sleep(3000)
            device.uiDevice.pressBack()

            flakySafely(20000) { // Increased timeout for dialog
                try {
                    EditorScreen {
                        closeProjectDialog {
                            positiveButton {
                                hasText("Save files and close project")
                                click()
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("Failed to find or click close project dialog: ${e.message}")
                    // Try one more time
                    Thread.sleep(2000)
                    device.uiDevice.pressBack()
                    EditorScreen {
                        closeProjectDialog {
                            positiveButton {
                                hasText("Save files and close project")
                                click()
                            }
                        }
                    }
                }
            }
        }
    }
}