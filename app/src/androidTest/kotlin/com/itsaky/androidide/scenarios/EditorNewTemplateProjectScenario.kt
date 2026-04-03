package com.itsaky.androidide.scenarios

import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.R
import com.itsaky.androidide.helper.EditorTemplateProjectUiHelper
import com.itsaky.androidide.helper.dismissFirstBuildNoticeIfShown
import com.itsaky.androidide.screens.EditorScreen
import com.itsaky.androidide.testing.InstrumentationStateProbe
import com.kaspersky.kaspresso.testcases.api.scenario.Scenario
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

/**
 * After opening a new project from a template: dismiss first-build dialog if shown, wait for
 * quick-run readiness, trigger quick run, then wait for deterministic build result and close.
 *
 * Onboarding / install is out of scope (handled by [NavigateToMainScreenScenario] before this).
 */
class EditorNewTemplateProjectScenario : Scenario() {

	companion object {
		private const val INIT_AND_QUICK_RUN_TIMEOUT_MS = 300_000L
		private const val PROJECT_INIT_TIMEOUT_MS = 300_000L
		private const val BUILD_RESULT_TIMEOUT_MS = 300_000L
	}

	override val steps: TestContext<Unit>.() -> Unit = {
		val ctx = InstrumentationRegistry.getInstrumentation().targetContext
		val d = device.uiDevice

		step("Dismiss first-build dialog if shown") {
			flakySafely(timeoutMs = 120_000) {
				dismissFirstBuildNoticeIfShown()
			}
		}

		step("Wait for project initialized, then quick run") {
			flakySafely(timeoutMs = INIT_AND_QUICK_RUN_TIMEOUT_MS) {
				InstrumentationStateProbe.reset()
				val seen =
					EditorTemplateProjectUiHelper.waitForProjectReadyForQuickRun(
						device = d,
						context = ctx,
						timeoutMs = PROJECT_INIT_TIMEOUT_MS,
					)
				check(seen) { "Project was not ready for quick run within timeout" }
				d.waitForIdle(500)
				val clicked = EditorTemplateProjectUiHelper.clickQuickRunToolbar(d, ctx)
				check(clicked) { "Could not click quick run on editor toolbar" }
				val buildOutcome =
					EditorTemplateProjectUiHelper.waitForBuildOutcome(
						device = d,
						context = ctx,
						timeoutMs = BUILD_RESULT_TIMEOUT_MS,
					)
				check(buildOutcome != EditorTemplateProjectUiHelper.BuildOutcome.TIMEOUT) {
					"Build result not observed within timeout"
				}
				check(buildOutcome != EditorTemplateProjectUiHelper.BuildOutcome.FAILURE) {
					"Build failed"
				}
				EditorTemplateProjectUiHelper.dismissInstallationFailedBannerIfShown(d, ctx)
			}
		}

		step("Close project from editor") {
			flakySafely(timeoutMs = 35_000) {
				d.waitForIdle(2000)
				d.pressBack()
				val first = runCatching {
					EditorScreen {
						closeProjectDialog {
							positiveButton {
								hasText(ctx.getString(R.string.save_and_close))
								click()
							}
						}
					}
				}
				if (first.isFailure) {
					d.waitForIdle(2000)
					d.pressBack()
					EditorScreen {
						closeProjectDialog {
							positiveButton {
								hasText(ctx.getString(R.string.save_and_close))
								click()
							}
						}
					}
				}
			}
		}
	}
}
