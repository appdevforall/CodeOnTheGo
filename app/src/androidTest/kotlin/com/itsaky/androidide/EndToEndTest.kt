package com.itsaky.androidide

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.helper.advancePastWelcomeScreen
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByText
import com.itsaky.androidide.helper.configureAutomationBuildPreferences
import com.itsaky.androidide.helper.ensureOnHomeScreenBeforeCreateProject
import com.itsaky.androidide.helper.grantAllRequiredPermissionsThroughOnboardingUi
import com.itsaky.androidide.helper.handlePrivacyDisclosure
import com.itsaky.androidide.helper.initializeProjectAndCancelBuild
import com.itsaky.androidide.helper.selectProjectTemplate
import com.itsaky.androidide.helper.waitForMainHomeOrEditorUi
import com.itsaky.androidide.screens.HomeScreen.clickCreateProjectHomeScreen
import com.itsaky.androidide.screens.OnboardingScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen.clickCreateProjectProjectSettings
import com.itsaky.androidide.screens.ProjectSettingsScreen.setProjectName
import com.itsaky.androidide.screens.PermissionScreen
import com.itsaky.androidide.utils.PermissionsHelper
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Single continuous E2E test that drives the app from first launch through
 * onboarding, project creation, builds, and beyond.
 *
 * The activity launches once and stays alive. Each stage is a Kaspresso
 * `step()` so failures report exactly which stage broke.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest : TestCase() {

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun test_endToEnd() = run {

        // ── Launch ──

        step("Launch app") {
            ActivityScenario.launch(SplashActivity::class.java)
            Thread.sleep(1000)
        }

        // ── Welcome Screen ──

        step("Verify welcome screen") {
            OnboardingScreen {
                greetingTitle.isVisible()
                greetingSubtitle.isVisible()
                nextButton {
                    isVisible()
                    isClickable()
                }
            }
        }

        advancePastWelcomeScreen()

        // ── Permissions Screen (with privacy disclosure dialog overlay) ──

        handlePrivacyDisclosure()

        val required = PermissionsHelper.getRequiredPermissions(targetContext)

        step("Verify all permission items") {
            flakySafely(timeoutMs = 3_000) {
                PermissionScreen {
                    title { isVisible() }
                    subTitle { isVisible() }
                    rvPermissions {
                        isVisible()
                        isDisplayed()
                    }
                    assertEquals(required.size, rvPermissions.getSize())

                    rvPermissions {
                        required.forEachIndexed { index, item ->
                            childAt<PermissionScreen.PermissionItem>(index) {
                                title {
                                    isVisible()
                                    hasText(item.title)
                                }
                                description {
                                    isVisible()
                                    hasText(item.description)
                                }
                                grantButton {
                                    isVisible()
                                    if (!item.isGranted && !item.isOptional) {
                                        isClickable()
                                        hasText(R.string.title_grant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        grantAllRequiredPermissionsThroughOnboardingUi()

        step("Confirm all permissions granted") {
            flakySafely(timeoutMs = 3_000) {
                assertTrue(PermissionsHelper.areAllPermissionsGranted(targetContext))
            }
        }

        step("Confirm all grant buttons disabled") {
            device.uiDevice.waitForIdle()
            PermissionScreen {
                rvPermissions {
                    required.indices.forEach { index ->
                        childAt<PermissionScreen.PermissionItem>(index) {
                            grantButton {
                                isNotEnabled()
                            }
                        }
                    }
                }
            }
        }

        step("Tap Finish installation") {
            // The button is in the gesture exclusion zone — use accessibility click
            clickFirstAccessibilityNodeByText("Finish installation")
        }

        step("Wait for IDE setup to complete") {
            waitForMainHomeOrEditorUi(device.uiDevice)
        }

        configureAutomationBuildPreferences()

        // ── Phase 2: Project creation + build for first 3 templates ──

        ensureOnHomeScreenBeforeCreateProject()

        data class TemplateConfig(
            val label: String,
            val templateResId: Int,
            val projectName: String,
        )

        val templates = listOf(
            TemplateConfig("No Activity", R.string.template_no_activity, "TestNoActivity"),
            TemplateConfig("Empty Activity", R.string.template_empty, "TestEmptyActivity"),
            TemplateConfig("Basic Activity", R.string.template_basic, "TestBasicActivity"),
        )

        for ((index, config) in templates.withIndex()) {
            step("Create+build template ${index + 1}/${templates.size}: ${config.label}") {
                clickCreateProjectHomeScreen()
            }
            selectProjectTemplate("Select ${config.label} template", config.templateResId)
            setProjectName(config.projectName)
            clickCreateProjectProjectSettings()
            initializeProjectAndCancelBuild()

            if (index < templates.lastIndex) {
                ensureOnHomeScreenBeforeCreateProject()
            }
        }

        // ── Future phases (preferences, more templates, etc.) go here ──
    }
}
