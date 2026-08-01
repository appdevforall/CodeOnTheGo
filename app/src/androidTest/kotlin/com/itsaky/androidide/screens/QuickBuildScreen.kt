package com.itsaky.androidide.screens

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByText
import com.itsaky.androidide.resources.R
import com.kaspersky.kaspresso.screens.KScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import org.junit.Assert.assertTrue

private const val FIRST_BUILD_NOTICE_TIMEOUT_MS = 3_000L
private const val DROPDOWN_ITEM_TIMEOUT_MS = 5_000L

/**
 * Page object for the Quick Build editor-toolbar surface (ADFA-4128):
 * the lightning-bolt status/indicator button (contentDescription `cd_quick_build`,
 * icon tone tracks the session status) and its long-press split-button dropdown
 * (Quick Build / Standard Run / Restart session / Help).
 *
 * The button is a toolbar action, not an inflated layout view, so lookups go through
 * UiAutomator rather than Kakao view matchers - same pattern as [ProjectSettingsScreen]'s
 * dropdown handling.
 */
object QuickBuildScreen : KScreen<QuickBuildScreen>() {
	override val layoutId: Int? = null
	override val viewClass: Class<*>? = null

	private val targetContext
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	/** Labels shown by the long-press split-button dropdown, in menu order. */
	private val dropdownItemLabels
		get() =
			listOf(
				targetContext.getString(R.string.quick_build_action_label),
				targetContext.getString(R.string.quick_build_menu_standard_run),
				targetContext.getString(R.string.quick_build_menu_restart_session),
				targetContext.getString(R.string.help),
			)

	private fun TestContext<Unit>.quickBuildButton(): UiObject =
		device.uiDevice.findObject(
			UiSelector().description(targetContext.getString(R.string.cd_quick_build)),
		)

	/** Dismisses the one-time first-build notice dialog if the editor shows it. */
	fun TestContext<Unit>.dismissFirstBuildNoticeIfShown() {
		step("Dismiss first-build notice if shown") {
			val d = device.uiDevice
			val okBtn = d.findObject(UiSelector().text("OK").className("android.widget.Button"))
			if (okBtn.waitForExists(FIRST_BUILD_NOTICE_TIMEOUT_MS)) {
				clickFirstAccessibilityNodeByText("OK")
				d.waitForIdle()
			}
		}
	}

	/**
	 * Asserts the Quick Build toolbar button (the session status indicator) is shown.
	 * Only present when experiments are enabled and the editor toolbar is populated.
	 */
	fun TestContext<Unit>.assertQuickBuildButtonShown(timeoutMs: Long) {
		step("Editor shows the Quick Build toolbar button") {
			assertTrue(
				"Quick Build toolbar button not found (experiments flag on, editor open)",
				quickBuildButton().waitForExists(timeoutMs),
			)
		}
	}

	/** Long-presses the button and asserts every split-button dropdown item is shown. */
	fun TestContext<Unit>.longPressOpensQuickBuildDropdown() {
		step("Long-press opens the split-button dropdown") {
			quickBuildButton().longClick()
			val d = device.uiDevice
			dropdownItemLabels.forEach { title ->
				assertTrue(
					"Dropdown item '$title' not shown after long-press",
					d.findObject(UiSelector().text(title)).waitForExists(DROPDOWN_ITEM_TIMEOUT_MS),
				)
			}
		}
	}

	/** Presses back and asserts the dropdown dismisses. */
	fun TestContext<Unit>.dismissQuickBuildDropdown() {
		step("Dropdown dismisses on back") {
			val d = device.uiDevice
			d.pressBack()
			assertTrue(
				"Dropdown did not dismiss on back",
				d
					.findObject(
						UiSelector().text(targetContext.getString(R.string.quick_build_menu_standard_run)),
					).waitUntilGone(DROPDOWN_ITEM_TIMEOUT_MS),
			)
		}
	}

	/** Taps the Quick Build toolbar button. */
	fun TestContext<Unit>.tapQuickBuildButton() {
		step("Tap the Quick Build toolbar button") {
			quickBuildButton().click()
			device.uiDevice.waitForIdle()
		}
	}

	/**
	 * Asserts the toolbar shows the stop affordance (contentDescription flips to
	 * `cd_toolbar_cancel_build` while the tone is BUILDING - behaviour 1: the running
	 * button IS the stop button).
	 */
	fun TestContext<Unit>.assertQuickBuildButtonShowsStop(timeoutMs: Long) {
		step("Toolbar shows the stop affordance") {
			assertTrue(
				"No 'Cancel build' toolbar affordance appeared after the Quick Build tap",
				device.uiDevice
					.findObject(
						UiSelector().description(targetContext.getString(R.string.cd_toolbar_cancel_build)),
					).waitForExists(timeoutMs),
			)
		}
	}

	/** Asserts the confirm-on-switch ("Replace the installed app?") dialog is shown. */
	fun TestContext<Unit>.assertClobberConfirmShown() {
		step("Clobber confirm dialog is shown") {
			assertTrue(
				"Quick Build clobber-confirm dialog not shown",
				device.uiDevice
					.findObject(
						UiSelector().text(targetContext.getString(R.string.quick_build_switch_to_quick_title)),
					).waitForExists(DROPDOWN_ITEM_TIMEOUT_MS),
			)
		}
	}

	/** Declines the clobber confirm via its Cancel button and asserts it goes away. */
	fun TestContext<Unit>.declineClobberConfirm() {
		step("Decline the clobber confirm") {
			val d = device.uiDevice
			val cancel = d.findObject(UiSelector().textMatches("(?i)cancel"))
			assertTrue("Cancel button not found on the clobber confirm", cancel.waitForExists(DROPDOWN_ITEM_TIMEOUT_MS))
			cancel.click()
			assertClobberConfirmGone()
		}
	}

	/** Accepts the clobber confirm via its destructive Replace button. */
	fun TestContext<Unit>.acceptClobberConfirm() {
		step("Accept the clobber confirm") {
			val d = device.uiDevice
			val replace =
				d.findObject(
					UiSelector().textMatches("(?i)" + targetContext.getString(R.string.quick_build_switch_confirm)),
				)
			assertTrue("Replace button not found on the clobber confirm", replace.waitForExists(DROPDOWN_ITEM_TIMEOUT_MS))
			replace.click()
			assertClobberConfirmGone()
		}
	}

	private fun TestContext<Unit>.assertClobberConfirmGone() {
		assertTrue(
			"Clobber confirm dialog did not dismiss",
			device.uiDevice
				.findObject(
					UiSelector().text(targetContext.getString(R.string.quick_build_switch_to_quick_title)),
				).waitUntilGone(DROPDOWN_ITEM_TIMEOUT_MS),
		)
	}
}
