package com.itsaky.androidide

import androidx.test.ext.junit.rules.activityScenarioRule
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.scenarios.InitializationProjectAndCancelingBuildScenario
import com.itsaky.androidide.scenarios.NavigateToMainScreenScenario
import com.itsaky.androidide.screens.HomeScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen
import com.itsaky.androidide.screens.TemplateScreen
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import io.github.kakaocup.kakao.spinner.KSpinnerItem
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
            step("Initialization the project and cancelling the build") {
                scenario(InitializationProjectAndCancelingBuildScenario())
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
            step("Initialization the project and cancelling the build") {
                scenario(InitializationProjectAndCancelingBuildScenario())
            }
        }
    }
}