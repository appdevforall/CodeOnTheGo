package com.itsaky.androidide

import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.helper.initializeProjectAndCancelBuild
import com.itsaky.androidide.helper.navigateToMainScreen
import com.itsaky.androidide.helper.ensureOnHomeScreenBeforeCreateProject
import com.itsaky.androidide.helper.selectProjectTemplate
import com.itsaky.androidide.screens.HomeScreen.clickCreateProjectHomeScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen.clickCreateProjectProjectSettings
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: **Kotlin DSL** templates only, **three** templates in **one** test so we do not
 * `pm clear` between runs ([navigateToMainScreen] and asset unpack stay done once).
 *
 * [com.itsaky.androidide.scenarios.EditorNewTemplateProjectScenario] exits the project after each
 * template (back → save and close) so the next iteration starts from home.
 *
 * For full matrix coverage (all templates × Java), add separate targeted tests or CI jobs — not
 * this path.
 */
@RunWith(AndroidJUnit4::class)
class ProjectBuildTestWithKtsGradle : TestCase() {

	companion object {
		private const val TEMPLATE_ITERATION_TIMEOUT_MS = 300_000L
	}

	@get:Rule
	val activityRule = activityScenarioRule<SplashActivity>()

	@Test
	fun test_projectBuild_kts_smoke_threeTemplatesKotlin() {
		run {
			val templates =
				listOf(
					"No Activity" to R.string.template_no_activity,
					"Empty Activity" to R.string.template_empty,
					"Basic Activity" to R.string.template_basic,
				)

			navigateToMainScreen()

			for ((label, templateRes) in templates) {
				step("KTS $label — create, init build, exit") {
					ensureOnHomeScreenBeforeCreateProject()
					clickCreateProjectHomeScreen()
					selectProjectTemplate("Select $label", templateRes)
					clickCreateProjectProjectSettings()
					flakySafely(timeoutMs = TEMPLATE_ITERATION_TIMEOUT_MS) {
						initializeProjectAndCancelBuild()
					}
				}
			}
		}
	}
}
