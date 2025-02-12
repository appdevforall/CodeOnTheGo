package com.itsaky.androidide

import androidx.test.ext.junit.rules.activityScenarioRule
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.scenarios.InitializationProjectAndCancelingBuildScenario
import com.itsaky.androidide.scenarios.NavigateToMainScreenScenario
import com.itsaky.androidide.screens.HomeScreen.clickCreateProject
import com.itsaky.androidide.screens.ProjectSettingsScreen.selectJavaLanguage
import com.itsaky.androidide.screens.ProjectSettingsScreen.selectKotlinLanguage
import com.itsaky.androidide.screens.TemplateScreen.selectTemplate
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
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
            clickCreateProject()
            step("Select the basic project") {
                selectTemplate(R.string.template_basic)
            }
            selectJavaLanguage()
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
            clickCreateProject()
            step("Select the basic project") {
                selectTemplate(R.string.template_basic)
            }
            selectKotlinLanguage()
            step("Initialization the project and cancelling the build") {
                scenario(InitializationProjectAndCancelingBuildScenario())
            }
        }
    }

    @Test
    fun test_projectBuild_navigationDrawerProject_java() {
        run {
            step("Initialize the app") {
                scenario(NavigateToMainScreenScenario())
            }
            clickCreateProject()
            step("Select the navigation drawer project") {
                selectTemplate(R.string.template_navigation_drawer)
            }
            selectJavaLanguage()
            step("Initialization the project and cancelling the build") {
                scenario(InitializationProjectAndCancelingBuildScenario())
            }
        }
    }

    @Test
    fun test_projectBuild_navigationDrawerProject_kotlin() {
        run {
            step("Initialize the app") {
                scenario(NavigateToMainScreenScenario())
            }
            clickCreateProject()
            step("Select the navigation drawer project") {
                selectTemplate(R.string.template_navigation_drawer)
            }
            selectKotlinLanguage()
            step("Initialization the project and cancelling the build") {
                scenario(InitializationProjectAndCancelingBuildScenario())
            }
        }
    }
}