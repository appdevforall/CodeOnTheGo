package com.itsaky.androidide.helper

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.preferences.internal.prefManager
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.itsaky.androidide.resources.R as ResourcesR

private const val TAG = "PrivacyDisclosure"

// Mirrors PermissionsFragment.KEY_PRIVACY_DISCLOSURE_SHOWN (private there).
// If the dialog unexpectedly appears on a rerun or is expected but absent,
// check that the fragment's key has not been renamed.
private const val KEY_PRIVACY_DISCLOSURE_SHOWN = "privacy.disclosure.shown"
private const val PRIVACY_DIALOG_APPEAR_TIMEOUT_MS = 10_000L
private const val PRIVACY_DIALOG_ABSENT_TIMEOUT_MS = 2_000L
private const val PRIVACY_FLAG_PERSIST_TIMEOUT_MS = 5_000L

/**
 * Verifies and dismisses the privacy disclosure dialog on the onboarding
 * permissions screen.
 *
 * The app shows the dialog only while the persisted
 * `privacy.disclosure.shown` flag is unset, so the flow
 * branches on that flag instead of on whether the dialog happened to render in
 * time: a fresh install hard-asserts the dialog appears and accepts it, while a
 * rerun on a device that already accepted asserts it stays hidden.
 */
fun TestContext<Unit>.handlePrivacyDisclosure() {
	val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
	val dialogTitle = targetContext.getString(ResourcesR.string.privacy_disclosure_title)

	val expectDialog =
		!prefManager.getBoolean(KEY_PRIVACY_DISCLOSURE_SHOWN, false)

	if (expectDialog) {
		Log.i(TAG, "Privacy disclosure flag unset; expecting dialog and accepting it")
		step("Verify and accept privacy disclosure") {
			val d = device.uiDevice
			val acceptText = targetContext.getString(ResourcesR.string.privacy_disclosure_accept)
			val learnMoreText =
				targetContext.getString(ResourcesR.string.privacy_disclosure_learn_more)

			assertTrue(
				"Dialog title missing",
				d
					.findObject(UiSelector().text(dialogTitle))
					.waitForExists(PRIVACY_DIALOG_APPEAR_TIMEOUT_MS),
			)
			assertTrue("Accept button missing", d.findObject(UiSelector().text(acceptText)).exists())
			assertTrue(
				"Learn more button missing",
				d.findObject(UiSelector().text(learnMoreText)).exists(),
			)

			clickFirstAccessibilityNodeByText(acceptText)
			d.waitForIdle()

			// The accessibility click is dispatched asynchronously; retry until the
			// dialog's positive-button listener has persisted the flag.
			flakySafely(timeoutMs = PRIVACY_FLAG_PERSIST_TIMEOUT_MS) {
				assertTrue(
					"Accepting the disclosure did not persist the shown flag",
					prefManager.getBoolean(KEY_PRIVACY_DISCLOSURE_SHOWN, false),
				)
			}
		}
	} else {
		Log.i(TAG, "Privacy disclosure already accepted (flag set); verifying dialog stays hidden")
	}

	step("Verify privacy dialog is not shown") {
		assertFalse(
			"Dialog should not be shown",
			device.uiDevice
				.findObject(UiSelector().text(dialogTitle))
				.waitForExists(PRIVACY_DIALOG_ABSENT_TIMEOUT_MS),
		)
	}
}
