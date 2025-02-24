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
            flakySafely(120000) {
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
            }
        }
        step("Wait for the green button") {
            flakySafely(180000) {
                KView {
                    withText(R.string.msg_project_initialized)
                }.isVisible()
                flakySafely {
                    KView {
                        withParent {
                            KToolbar {
                                withId(R.id.editor_appBarLayout)
                            }
                        }
                        withId("ide.editor.build.quickRun".hashCode())
                    }.click()
                }
            }
        }
        step("Confirm that the install dialog appears and click cancel") {
            flakySafely(240000) {
                val installDialog =
                    device.uiDevice.findObject(UiSelector().text("Do you want to install this app?"))
                val cancelButton = device.uiDevice.findObject(UiSelector().text("Cancel"))
                if (installDialog.waitForExists(180000)) {
                    installDialog.exists()
                    cancelButton.click()
                } else {
                    throw AssertionError("Install dialog not found!")
                }
            }
        }
        step("Click back and confirm that the Close Project dialog appears") {
            device.uiDevice.pressBack()
            flakySafely {
                EditorScreen {
                    closeProjectDialog {
                        title {
                            hasText(R.string.title_confirm_project_close)
                        }
                        message {
                            hasText(R.string.msg_confirm_project_close)
                        }
                        positiveButton {
                            click()
                        }
                    }
                }
            }
        }
    }
}