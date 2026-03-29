package com.itsaky.androidide.scenarios

import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.R
import com.itsaky.androidide.helper.EditorTemplateProjectUiHelper
import com.itsaky.androidide.helper.dismissFirstBuildNoticeIfShown
import com.itsaky.androidide.screens.EditorScreen
import com.kaspersky.kaspresso.testcases.api.scenario.Scenario
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

/**
 * After opening a new project from a template: dismiss first-build dialog if shown, wait for
 * localized "project initialized" status, trigger quick run, then close the project.
 *
 * Onboarding / install is out of scope (handled by [NavigateToMainScreenScenario] before this).
 */
class EditorNewTemplateProjectScenario : Scenario() {

	override val steps: TestContext<Unit>.() -> Unit = {
		val ctx = InstrumentationRegistry.getInstrumentation().targetContext
		val d = device.uiDevice

		step("Dismiss first-build dialog if shown") {
			flakySafely(timeoutMs = 120_000) {
				dismissFirstBuildNoticeIfShown()
			}
		}

		step("Wait for project initialized, then quick run") {
			flakySafely(timeoutMs = 600_000) {
				val seen =
					EditorTemplateProjectUiHelper.waitForProjectInitializedLocalized(
						device = d,
						context = ctx,
						timeoutMs = 540_000L,
					)
				check(seen) { "Project initialized status not shown within timeout" }
				d.waitForIdle(3000)
				val clicked = EditorTemplateProjectUiHelper.clickQuickRunToolbar(d, ctx)
				check(clicked) { "Could not click quick run on editor toolbar" }
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
