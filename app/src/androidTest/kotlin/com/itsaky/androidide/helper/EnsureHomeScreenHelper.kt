package com.itsaky.androidide.helper

import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.BuildConfig
import com.itsaky.androidide.R
import com.itsaky.androidide.screens.EditorScreen
import com.itsaky.androidide.screens.HomeScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import org.hamcrest.Matchers

/**
 * `GeneralPreferences.autoOpenProjects` defaults to **true**, so after onboarding
 * [com.itsaky.androidide.activities.MainActivity] may open
 * [com.itsaky.androidide.activities.editor.EditorActivityKt] for the last project. Project-creation
 * tests expect the main home list (“Get started” + Create Project).
 */
fun TestContext<Unit>.ensureOnHomeScreenBeforeCreateProject() {
	val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
	val pkg = BuildConfig.APPLICATION_ID
	val editorAppBarId = "$pkg:id/editor_appBarLayout"
	val saveAndClose = targetContext.getString(R.string.save_and_close)

	step("Decline auto-open last project if confirmation dialog is shown") {
		runCatching {
			val title = targetContext.getString(R.string.title_confirm_open_project)
			val dialogTitle = device.uiDevice.findObject(UiSelector().text(title))
			if (dialogTitle.waitForExists(4000) && dialogTitle.exists()) {
				val noLabel = targetContext.getString(R.string.no)
				runCatching { device.uiDevice.findObject(UiSelector().text(noLabel)).click() }
				device.uiDevice.waitForIdle(2000)
			}
		}
	}

	step("Close restored project and return to home if editor is open") {
		flakySafely(timeoutMs = 60_000) {
			var closeAttempts = 0
			while (closeAttempts < 4) {
				closeAttempts++
				val appBar = device.uiDevice.findObject(UiSelector().resourceId(editorAppBarId))
				if (!appBar.waitForExists(2000) || !appBar.exists()) {
					break
				}
				device.uiDevice.pressBack()
				device.uiDevice.waitForIdle(2000)
				try {
					flakySafely(timeoutMs = 20_000) {
						EditorScreen {
							closeProjectDialog {
								positiveButton {
									hasText(saveAndClose)
									click()
								}
							}
						}
					}
				} catch (_: Throwable) {
					runCatching {
						device.uiDevice.findObject(UiSelector().text(saveAndClose)).click()
						device.uiDevice.waitForIdle(2000)
					}
				}
				device.uiDevice.waitForIdle(3000)
			}
		}
	}

	step("Assert home (Get started) is visible") {
		flakySafely(timeoutMs = 30_000) {
			HomeScreen {
				title {
					isVisible()
					withText(Matchers.equalToIgnoringCase(targetContext.getString(R.string.get_started)))
				}
			}
		}
	}
}
