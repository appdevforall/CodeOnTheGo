package com.itsaky.androidide

import androidx.test.ext.junit.rules.activityScenarioRule
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.screens.EditorScreen
import com.itsaky.androidide.screens.HomeScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen
import com.itsaky.androidide.screens.TemplateScreen
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import io.github.kakaocup.kakao.common.views.KView
import org.junit.Rule
import org.junit.Test

class ProjectBuildTest : TestCase() {

    @get:Rule
    val activityRule = activityScenarioRule<SplashActivity>()

    @Test
    fun test_projectBuild_withJava_withBasicProject() {
        run {
            step("Initialize the app") {
                scenario(NavigateToMainScreenScenario())
            }
            step("Click create project") {
                flakySafely(1000000) {
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
                TemplateScreen {
                    rvTemplates {
                        childWith<TemplateScreen.TemplateItem> {
                            withDescendant { withText(R.string.template_basic) }
                        } perform { click() }
                    }
                }
            }
            step("Select the java language") {
                ProjectSettingsScreen {
                    languageField {
                        isVisible()
                        click()
                    }

                    KView { withText("Java") }.click()

                    createProjectButton {
                        isVisible()
                        click()
                    }
                }
            }
            step("Close the first build dialog") {
                flakySafely(10000000) {
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
        }
    }
}