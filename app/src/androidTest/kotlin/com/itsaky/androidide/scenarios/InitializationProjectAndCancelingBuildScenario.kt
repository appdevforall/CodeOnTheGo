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
            flakySafely(360000) {
                device.uiDevice.findObject(UiSelector().text("Project initialized"))
                    .waitForExists(360000)
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
        step("Click back and confirm that the Close Project dialog appears") {
            device.uiDevice.pressBack()
            flakySafely {
                EditorScreen {
                    closeProjectDialog {
                        positiveButton {
                            hasText("Yes")
                            click()
                        }
                    }
                }
            }
        }
    }
}