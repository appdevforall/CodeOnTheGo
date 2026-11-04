package com.itsaky.androidide.screens

import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiSelector
import com.kaspersky.kaspresso.screens.KScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import org.junit.Assert.assertTrue

private const val BANNER_SHOWN_TIMEOUT_MS = 5_000L
private const val BANNER_GONE_TIMEOUT_MS = 5_000L
private const val SWIPE_STEPS = 20

/**
 * Page object for the indefinite error Flashbar (the surface Quick Build's
 * `userMessages` flow renders through `flashError`, ADFA-4128). The bar draws OVER the
 * editor toolbar, so it must be dismissible three ways: the Dismiss action button, a tap
 * anywhere on the bar, and a swipe (see FlashbarActivityUtils.showFlashBar).
 *
 * The bar is a window overlay, not part of the activity layout, so lookups go through
 * UiAutomator by the message text.
 */
object ErrorBannerScreen : KScreen<ErrorBannerScreen>() {
	override val layoutId: Int? = null
	override val viewClass: Class<*>? = null

	private fun TestContext<Unit>.bannerMessage(message: String): UiObject = device.uiDevice.findObject(UiSelector().text(message))

	fun TestContext<Unit>.assertErrorBannerShown(message: String) {
		step("Error banner '$message' is shown") {
			assertTrue(
				"Indefinite error banner with message '$message' not shown",
				bannerMessage(message).waitForExists(BANNER_SHOWN_TIMEOUT_MS),
			)
		}
	}

	fun TestContext<Unit>.assertErrorBannerGone(
		message: String,
		how: String,
	) {
		step("Error banner dismissed via $how") {
			assertTrue(
				"Error banner did not dismiss via $how",
				bannerMessage(message).waitUntilGone(BANNER_GONE_TIMEOUT_MS),
			)
		}
	}

	/** Dismisses via the bar's Dismiss action button. */
	fun TestContext<Unit>.dismissErrorBannerViaButton(message: String) {
		step("Tap the Dismiss button") {
			val dismiss = device.uiDevice.findObject(UiSelector().textMatches("(?i)dismiss"))
			assertTrue("Dismiss button not shown on the error banner", dismiss.waitForExists(BANNER_SHOWN_TIMEOUT_MS))
			dismiss.click()
		}
		assertErrorBannerGone(message, "the Dismiss button")
	}

	/** Dismisses via a tap anywhere on the bar (here: on the message text). */
	fun TestContext<Unit>.dismissErrorBannerViaTapOnBar(message: String) {
		step("Tap the banner body") {
			bannerMessage(message).click()
		}
		assertErrorBannerGone(message, "a tap on the bar")
	}

	/**
	 * Dismisses via a horizontal swipe on the bar. A short swipe that the touch pipeline
	 * classifies as a tap also dismisses (tap-anywhere is enabled on the same bar), so this
	 * asserts "a swipe gesture gets rid of the bar", not which internal gesture path won.
	 */
	fun TestContext<Unit>.dismissErrorBannerViaSwipe(message: String) {
		step("Swipe the banner") {
			bannerMessage(message).swipeRight(SWIPE_STEPS)
		}
		assertErrorBannerGone(message, "a swipe")
	}
}
