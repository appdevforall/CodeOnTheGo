package com.itsaky.androidide

import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectBuildTestWithKtsGradle : TestCase() {

    @get:Rule
    val activityRule = activityScenarioRule<SplashActivity>()

    @After
    fun cleanUp() {
        // Clean up after each test to ensure proper state for subsequent tests
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm clear ${BuildConfig.APPLICATION_ID} && pm reset-permissions ${BuildConfig.APPLICATION_ID}")
    }



    @Test
    fun test_projectBuild_emptyProject_java() {
        run {

            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the empty project",
                R.string.template_empty
            )
            selectJavaLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_emptyProject_kotlin() {
        run {

            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the empty project",
                R.string.template_empty
            )
            selectKotlinLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_baseProject_java() {
        run {

            step("Navigate to main screen") {
                flakySafely(timeoutMs = 30000) {
                    navigateToMainScreen()
                }
            }

            step("Click create project on home screen") {
                flakySafely(timeoutMs = 10000) {
                    clickCreateProjectHomeScreen()
                }
            }

            step("Select the basic project template") {
                flakySafely(timeoutMs = 10000) {
                    selectProjectTemplate(
                        "Select the basic project",
                        R.string.template_basic
                    )
                }
            }

            step("Select Java language") {
                flakySafely(timeoutMs = 10000) {
                    selectJavaLanguage()
                }
            }

            step("Click create project on settings screen") {
                flakySafely(timeoutMs = 10000) {
                    clickCreateProjectProjectSettings()
                }
            }

            step("Initialize project and cancel build") {
                flakySafely(timeoutMs = 15000) {
                    initializeProjectAndCancelBuild()
                }
            }
        }
    }

    @Test
    fun test_projectBuild_baseProject_kotlin() {
        run {

            step("Navigate to main screen") {
                // Ensure consistent start state with increased timeout
                flakySafely(timeoutMs = 30000) {
                    navigateToMainScreen()
                }
            }

            step("Click create project on home screen") {
                flakySafely(timeoutMs = 10000) {
                    clickCreateProjectHomeScreen()
                }
            }

            step("Select the basic project template") {
                flakySafely(timeoutMs = 10000) {
                    selectProjectTemplate(
                        "Select the basic project",
                        R.string.template_basic
                    )
                }
            }

            step("Select Kotlin language") {
                flakySafely(timeoutMs = 10000) {
                    selectKotlinLanguage()
                }
            }

            step("Click create project on settings screen") {
                flakySafely(timeoutMs = 10000) {
                    clickCreateProjectProjectSettings()
                }
            }

            step("Initialize project and cancel build") {
                flakySafely(timeoutMs = 10000) {
                    initializeProjectAndCancelBuild()
                }
            }
        }
    }

    @Test
    fun test_projectBuild_navigationDrawerProject_java() {
        run {

            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the navigation drawer project",
                R.string.template_navigation_drawer
            )
            selectJavaLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_navigationDrawerProject_kotlin() {
        run {
            
            step("Navigate to main screen") {
                // Ensure consistent start state with increased timeout for Firebase Test Lab
                flakySafely(timeoutMs = 900000) {
                    navigateToMainScreen()
                }
            }

            step("Click create project on home screen") {
                flakySafely(timeoutMs = 10000) {
                    clickCreateProjectHomeScreen()
                }
            }

            step("Select the navigation drawer project template") {
                flakySafely(timeoutMs = 10000) {
                    selectProjectTemplate(
                        "Select the navigation drawer project",
                        R.string.template_navigation_drawer
                    )
                }
            }

            step("Select Kotlin language") {
                flakySafely(timeoutMs = 10000) {
                    selectKotlinLanguage()
                }
            }

            step("Click create project on settings screen") {
                flakySafely(timeoutMs = 10000) {
                    clickCreateProjectProjectSettings()
                }
            }

            step("Initialize project and cancel build") {
                flakySafely(timeoutMs = 10000) {
                    initializeProjectAndCancelBuild()
                }
            }
        }
    }

    @Test
    fun test_projectBuild_bottomNavigationProject_java() {
        run {

            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the bottom navigation project",
                R.string.template_navigation_tabs
            )
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
            selectProjectTemplate(
                "Select the bottom navigation project",
                R.string.template_navigation_tabs
            )
            selectKotlinLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_tabbedActivityProject_java() {
        run {

            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the tabbed activity project",
                R.string.template_tabs
            )
            selectJavaLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_tabbedActivityProject_kotlin() {
        run {

            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the tabbed activity project",
                R.string.template_tabs
            )
            selectKotlinLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_noAnd2roidXProject_java() {
        run {

            navigateToMainScreen()
            clickCreateProjectHomeScreen()
            selectProjectTemplate(
                "Select the no AndroidX project",
                R.string.template_no_AndroidX
            )
            selectJavaLanguage()
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()
        }
    }

    @Test
    fun test_projectBuild_noAndroidXProject_kotlin() {
        run {

            step("Navigate to main screen") {
                flakySafely(timeoutMs = 30000) {
                    navigateToMainScreen()
                }
            }

            step("Click create project on home screen") {
                flakySafely(timeoutMs = 10000) {
                    clickCreateProjectHomeScreen()
                }
            }

            step("Select the no AndroidX project template") {
                flakySafely(timeoutMs = 10000) {
                    selectProjectTemplate(
                        "Select the no AndroidX project",
                        R.string.template_no_AndroidX
                    )
                }
            }

            step("Select Kotlin language") {
                flakySafely(timeoutMs = 10000) {
                    selectKotlinLanguage()
                }
            }

            step("Click create project on settings screen") {
                flakySafely(timeoutMs = 10000) {
                    clickCreateProjectProjectSettings()
                }
            }

            step("Initialize project and cancel build") {
                flakySafely(timeoutMs = 15000) {
                    initializeProjectAndCancelBuild()
                }
            }
        }
    }

// TODO: to be uncommented out when the compose project template is fixed
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