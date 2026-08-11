package com.itsaky.androidide.helper

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.preferences.internal.StatPreferences
import com.itsaky.androidide.preferences.internal.TelemetryConsent
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.itsaky.androidide.resources.R as ResourcesR

private const val TAG = "PrivacyDisclosure"

private const val PRIVACY_DIALOG_APPEAR_TIMEOUT_MS = 10_000L
private const val PRIVACY_DIALOG_ABSENT_TIMEOUT_MS = 2_000L
private const val PRIVACY_FLAG_PERSIST_TIMEOUT_MS = 5_000L

/**
 * Verifies and accepts the telemetry consent dialog on the onboarding
 * permissions screen.
 *
 * The app shows the dialog only while the persisted telemetry consent is
 * [TelemetryConsent.UNSET], so the flow branches on that value instead of on
 * whether the dialog happened to render in time: a fresh install hard-asserts
 * the dialog appears and accepts it, while a rerun on a device that already
 * answered asserts it stays hidden.
 */
fun TestContext<Unit>.handlePrivacyDisclosure() {
	val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
	val dialogTitle = targetContext.getString(ResourcesR.string.privacy_disclosure_title)

	val expectDialog = StatPreferences.telemetryConsent == TelemetryConsent.UNSET

	if (expectDialog) {
		Log.i(TAG, "Telemetry consent unset; expecting dialog and accepting it")
		step("Verify and accept privacy disclosure") {
			val d = device.uiDevice
			val acceptText = targetContext.getString(ResourcesR.string.privacy_disclosure_accept)
			val declineText = targetContext.getString(ResourcesR.string.privacy_disclosure_decline)
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
				"Keep offline button missing",
				d.findObject(UiSelector().text(declineText)).exists(),
			)
			assertTrue(
				"Learn more button missing",
				d.findObject(UiSelector().text(learnMoreText)).exists(),
			)

			clickFirstAccessibilityNodeByText(acceptText)
			d.waitForIdle()

			// The accessibility click is dispatched asynchronously; retry until the
			// dialog's positive-button listener has persisted the consent.
			flakySafely(timeoutMs = PRIVACY_FLAG_PERSIST_TIMEOUT_MS) {
				assertTrue(
					"Accepting the disclosure did not persist the consent",
					StatPreferences.telemetryConsent == TelemetryConsent.GRANTED,
				)
			}
		}
	} else {
		Log.i(TAG, "Telemetry consent already answered; verifying dialog stays hidden")
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
