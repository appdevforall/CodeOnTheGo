package org.appdevforall.codeonthego


import com.itsaky.androidide.R
import androidx.test.platform.app.InstrumentationRegistry
import org.appdevforall.codeonthego.helper.initializeProjectAndCancelBuild
import org.appdevforall.codeonthego.helper.navigateToMainScreen
import org.appdevforall.codeonthego.helper.selectProjectTemplate
import org.appdevforall.codeonthego.screens.HomeScreen.clickCreateProjectHomeScreen
import org.appdevforall.codeonthego.screens.ProjectSettingsScreen.clickCreateProjectProjectSettings
import org.appdevforall.codeonthego.screens.ProjectSettingsScreen.selectJavaLanguage
import org.appdevforall.codeonthego.screens.ProjectSettingsScreen.selectKotlinLanguage
import org.appdevforall.codeonthego.screens.ProjectSettingsScreen.uncheckKotlinScript
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.Test


class ProjectBuildTestWithGroovyGradle : TestCase() {

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