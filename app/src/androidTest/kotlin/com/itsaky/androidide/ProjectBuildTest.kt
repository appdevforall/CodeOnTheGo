package com.itsaky.androidide

import androidx.test.ext.junit.rules.activityScenarioRule
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.scenarios.InitializationProjectAndCancelingBuildScenario
import com.itsaky.androidide.scenarios.NavigateToMainScreenScenario
import com.itsaky.androidide.screens.HomeScreen.clickCreateProjectHomeScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen.clickCreateProjectProjectSettings
import com.itsaky.androidide.screens.ProjectSettingsScreen.selectJavaLanguage
import com.itsaky.androidide.screens.ProjectSettingsScreen.selectKotlinLanguage
import com.itsaky.androidide.screens.TemplateScreen.selectTemplate
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import org.junit.Rule
import org.junit.Test

class ProjectBuildTest : TestCase() {

    @get:Rule
    val activityRule = activityScenarioRule<SplashActivity>()

    @Test
    fun test_projectBuild_baseProject_java() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            step("Select the basic project") {
                selectTemplate(R.string.template_basic)
            }
            selectJavaLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_baseProject_kotlin() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            step("Select the basic project") {
                selectTemplate(R.string.template_basic)
            }
            selectKotlinLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_navigationDrawerProject_java() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            step("Select the navigation drawer project") {
                selectTemplate(R.string.template_navigation_drawer)
            }
            selectJavaLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_navigationDrawerProject_kotlin() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            step("Select the navigation drawer project") {
                selectTemplate(R.string.template_navigation_drawer)
            }
            selectKotlinLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_bottomNavigationProject_java() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            step("Select the navigation drawer project") {
                selectTemplate(R.string.template_navigation_tabs)
            }
            selectJavaLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_bottomNavigationProject_kotlin() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            step("Select the navigation drawer project") {
                selectTemplate(R.string.template_navigation_tabs)
            }
            selectKotlinLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    private fun TestContext<Unit>.navigateToMainScreen() {
        step("Initialize the app and navigate to the Main Screen") {
            scenario(NavigateToMainScreenScenario())
        }
    }

    private fun TestContext<Unit>.initializeProjectAndCancelBuild() {
        step("Initialization the project and cancelling the build") {
            scenario(InitializationProjectAndCancelingBuildScenario())
        }
    }
}