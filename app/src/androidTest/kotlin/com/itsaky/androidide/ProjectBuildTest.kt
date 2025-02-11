package com.itsaky.androidide

import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.screens.EditorScreen
import com.itsaky.androidide.screens.HomeScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen
import com.itsaky.androidide.screens.TemplateScreen
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import io.github.kakaocup.kakao.common.views.KView
import io.github.kakaocup.kakao.spinner.KSpinnerItem
import io.github.kakaocup.kakao.toolbar.KToolbar
import org.junit.Rule
import org.junit.Test

class ProjectBuildTest : TestCase() {

    @get:Rule
    val activityRule = activityScenarioRule<SplashActivity>()

    @Test
    fun test_projectBuild_baseProject_java() {
        run {
            step("Initialize the app") {
                scenario(NavigateToMainScreenScenario())
            }
            step("Click create project") {
                flakySafely(60000) {
                    HomeScreen {
                        rvActions {
                            childAt<HomeScreen.ActionItem>(0) {
                                click()
                            }
                        }
                    }
                }
            }
            step("Select the basic project") {
                flakySafely(10000) {
                    TemplateScreen {
                        rvTemplates {
                            childWith<TemplateScreen.TemplateItem> {
                                withDescendant { withText(R.string.template_basic) }
                            } perform { click() }
                        }
                    }
                }
            }
            step("Select the java language") {
                ProjectSettingsScreen {
                    spinner {
                        isVisible()
                        open()

                        childAt<KSpinnerItem>(0) {
                            isVisible()
                            hasText("Java")
                            click()
                        }
                    }

                    createProjectButton {
                        isVisible()
                        click()
                    }
                }
            }
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
                flakySafely(120000) {
                    val installDialog =
                        device.uiDevice.findObject(UiSelector().text("Do you want to install this app?"))
                    val cancelButton = device.uiDevice.findObject(UiSelector().text("Cancel"))
                    if (installDialog.waitForExists(120000)) {
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

    @Test
    fun test_projectBuild_baseProject_kotlin() {
        run {
            step("Initialize the app") {
                scenario(NavigateToMainScreenScenario())
            }
            step("Click create project") {
                flakySafely(60000) {
                    HomeScreen {
                        rvActions {
                            childAt<HomeScreen.ActionItem>(0) {
                                click()
                            }
                        }
                    }
                }
            }
            step("Select the basic project") {
                flakySafely(10000) {
                    TemplateScreen {
                        rvTemplates {
                            childWith<TemplateScreen.TemplateItem> {
                                withDescendant { withText(R.string.template_basic) }
                            } perform { click() }
                        }
                    }
                }
            }
            step("Select the kotlin language") {
                flakySafely {
                    ProjectSettingsScreen {
                        spinner {
                            isVisible()
                            open()

                            childAt<KSpinnerItem>(1) {
                                isVisible()
                                hasText("Kotlin")
                                click()
                            }
                        }

                        createProjectButton {
                            isVisible()
                            click()
                        }
                    }
                }
            }
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
                flakySafely(120000) {
                    val installDialog =
                        device.uiDevice.findObject(UiSelector().text("Do you want to install this app?"))
                    val cancelButton = device.uiDevice.findObject(UiSelector().text("Cancel"))
                    if (installDialog.waitForExists(120000)) {
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
}