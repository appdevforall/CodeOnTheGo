package com.itsaky.androidide

import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.screens.HomeScreen.clickCreateProjectHomeScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen.clickCreateProjectProjectSettings
import com.itsaky.androidide.screens.ProjectSettingsScreen.selectJavaLanguage
import com.itsaky.androidide.screens.ProjectSettingsScreen.selectKotlinLanguage
import com.itsaky.androidide.screens.ProjectSettingsScreen.uncheckKotlinScript
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ProjectBuildTestWithGroovyGradle : TestCase() {

    @get:Rule
    val activityRule = activityScenarioRule<SplashActivity>()

    @After
    fun cleanUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm clear ${BuildConfig.APPLICATION_ID} && pm reset-permissions ${BuildConfig.APPLICATION_ID}")
    }

    @Test
    fun test_projectBuild_emptyProject_java_groovyGradle() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the empty project",
                R.string.template_empty
            )
            selectJavaLanguage()
            uncheckKotlinScript()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_emptyProject_kotlin_groovyGradle() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the empty project",
                R.string.template_empty
            )
            selectKotlinLanguage()
            uncheckKotlinScript()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_baseProject_java_groovyGradle() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the basic project",
                R.string.template_basic
            )
            selectJavaLanguage()
            uncheckKotlinScript()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_baseProject_kotlin_groovyGradle() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the basic project",
                R.string.template_basic
            )
            selectKotlinLanguage()
            uncheckKotlinScript()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_navigationDrawerProject_java_groovyGradle() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the navigation drawer project",
                R.string.template_navigation_drawer
            )
            selectJavaLanguage()
            uncheckKotlinScript()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_navigationDrawerProject_kotlin_groovyGradle() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the navigation drawer project",
                R.string.template_navigation_drawer
            )
            selectKotlinLanguage()
            uncheckKotlinScript()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_bottomNavigationProject_java_groovyGradle() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the bottom navigation project",
                R.string.template_navigation_tabs
            )
            selectJavaLanguage()
            uncheckKotlinScript()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_bottomNavigationProject_kotlin_groovyGradle() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the bottom navigation project",
                R.string.template_navigation_tabs
            )
            selectKotlinLanguage()
            uncheckKotlinScript()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_tabbedActivityProject_java_groovyGradle() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the tabbed activity project",
                R.string.template_tabs
            )
            selectJavaLanguage()
            uncheckKotlinScript()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_tabbedActivityProject_kotlin_groovyGradle() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the tabbed activity project",
                R.string.template_tabs
            )
            selectKotlinLanguage()
            uncheckKotlinScript()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_noAndroidXProject_java_groovyGradle() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the no AndroidX project",
                R.string.template_no_AndroidX
            )
            selectJavaLanguage()
            uncheckKotlinScript()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_noAndroidXProject_kotlin_groovyGradle() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the no AndroidX project",
                R.string.template_no_AndroidX
            )
            selectKotlinLanguage()
            uncheckKotlinScript()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }
}