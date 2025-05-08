package com.itsaky.androidide

import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.helper.initializeProjectAndCancelBuild
import com.itsaky.androidide.helper.navigateToMainScreen
import com.itsaky.androidide.helper.selectProjectTemplate
import com.itsaky.androidide.screens.HomeScreen.clickCreateProjectHomeScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen.clickCreateProjectProjectSettings
import com.itsaky.androidide.screens.ProjectSettingsScreen.selectJavaLanguage
import com.itsaky.androidide.screens.ProjectSettingsScreen.selectKotlinLanguage
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ProjectBuildTestWithKtsGradle : TestCase() {

    @get:Rule
    val activityRule = activityScenarioRule<SplashActivity>()

    @After
    fun cleanUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm clear ${BuildConfig.APPLICATION_ID} && pm reset-permissions ${BuildConfig.APPLICATION_ID}")
    }


//    @Test
//    fun test_projectBuild_emptyProject_java() {
//        run {
//            navigateToMainScreen()
//            clickCreateProjectHomeScreen()
//            selectProjectTemplate(
//                "Select the empty project",
//                R.string.template_empty
//            )
//            selectJavaLanguage()
//            clickCreateProjectProjectSettings()
//            initializeProjectAndCancelBuild()
//        }
//    }

//    @Test
//    fun test_projectBuild_emptyProject_kotlin() {
//        run {
//            navigateToMainScreen()
//            clickCreateProjectHomeScreen()
//            selectProjectTemplate(
//                "Select the empty project",
//                R.string.template_empty
//            )
//            selectKotlinLanguage()
//            clickCreateProjectProjectSettings()
//            initializeProjectAndCancelBuild()
//        }
//    }
//
//    @Test
//    fun test_projectBuild_baseProject_java() {
//        run {
//            navigateToMainScreen()
//            clickCreateProjectHomeScreen()
//            selectProjectTemplate(
//                "Select the basic project",
//                R.string.template_basic
//            )
//            selectJavaLanguage()
//            clickCreateProjectProjectSettings()
//            initializeProjectAndCancelBuild()
//        }
//    }
//
    @Test
    fun test_projectBuild_baseProject_kotlin() {
        run {
            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the basic project",
                R.string.template_basic
            )
            selectKotlinLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }
//
//
//    @Test
//    fun test_projectBuild_navigationDrawerProject_java() {
//        run {
//            navigateToMainScreen()
//            clickCreateProjectHomeScreen()
//            selectProjectTemplate(
//                "Select the navigation drawer project",
//                R.string.template_navigation_drawer
//            )
//            selectJavaLanguage()
//            clickCreateProjectProjectSettings()
//            initializeProjectAndCancelBuild()
//        }
//    }
//
//    @Test
//    fun test_projectBuild_navigationDrawerProject_kotlin() {
//        run {
//            navigateToMainScreen()
//            clickCreateProjectHomeScreen()
//            selectProjectTemplate(
//                "Select the navigation drawer project",
//                R.string.template_navigation_drawer
//            )
//            selectKotlinLanguage()
//            clickCreateProjectProjectSettings()
//            initializeProjectAndCancelBuild()
//        }
//    }
//
//    @Test
//    fun test_projectBuild_bottomNavigationProject_java() {
//        run {
//            navigateToMainScreen()
//            clickCreateProjectHomeScreen()
//            selectProjectTemplate(
//                "Select the bottom navigation project",
//                R.string.template_navigation_tabs
//            )
//            selectJavaLanguage()
//            clickCreateProjectProjectSettings()
//            initializeProjectAndCancelBuild()
//        }
//    }
//
//    @Test
//    fun test_projectBuild_bottomNavigationProject_kotlin() {
//        run {
//            navigateToMainScreen()
//            clickCreateProjectHomeScreen()
//            selectProjectTemplate(
//                "Select the bottom navigation project",
//                R.string.template_navigation_tabs
//            )
//            selectKotlinLanguage()
//            clickCreateProjectProjectSettings()
//            initializeProjectAndCancelBuild()
//        }
//    }
//
//    @Test
//    fun test_projectBuild_tabbedActivityProject_java() {
//        run {
//            navigateToMainScreen()
//            clickCreateProjectHomeScreen()
//            selectProjectTemplate(
//                "Select the tabbed activity project",
//                R.string.template_tabs
//            )
//            selectJavaLanguage()
//            clickCreateProjectProjectSettings()
//            initializeProjectAndCancelBuild()
//        }
//    }
//
//    @Test
//    fun test_projectBuild_tabbedActivityProject_kotlin() {
//        run {
//            navigateToMainScreen()
//            clickCreateProjectHomeScreen()
//            selectProjectTemplate(
//                "Select the tabbed activity project",
//                R.string.template_tabs
//            )
//            selectKotlinLanguage()
//            clickCreateProjectProjectSettings()
//            initializeProjectAndCancelBuild()
//        }
//    }
//
//    @Test
//    fun test_projectBuild_noAndroidXProject_java() {
//        run {
//            navigateToMainScreen()
//            clickCreateProjectHomeScreen()
//            selectProjectTemplate(
//                "Select the no AndroidX project",
//                R.string.template_no_AndroidX
//            )
//            selectJavaLanguage()
//            clickCreateProjectProjectSettings()
//            initializeProjectAndCancelBuild()
//        }
//    }
//
//    @Test
//    fun test_projectBuild_noAndroidXProject_kotlin() {
//        run {
//            navigateToMainScreen()
//            clickCreateProjectHomeScreen()
//            selectProjectTemplate(
//                "Select the no AndroidX project",
//                R.string.template_no_AndroidX
//            )
//            selectKotlinLanguage()
//            clickCreateProjectProjectSettings()
//            initializeProjectAndCancelBuild()
//        }
//    }
//
//    @Test
//    fun test_projectBuild_composeProject() {
//        run {
//            navigateToMainScreen()
//            clickCreateProjectHomeScreen()
//            selectProjectTemplate(
//                "Select the no Compose project",
//                R.string.template_compose
//            )
//            clickCreateProjectProjectSettings()
//            initializeProjectAndCancelBuild()
//        }
//    }
}