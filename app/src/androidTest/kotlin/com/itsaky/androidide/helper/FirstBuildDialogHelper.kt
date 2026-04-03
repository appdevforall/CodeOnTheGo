package com.itsaky.androidide.helper

import android.util.Log
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.R
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.screens.EditorScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import org.hamcrest.Matchers.allOf

private const val TAG = "FirstBuildDialog"

/**
 * [com.itsaky.androidide.activities.editor.BaseEditorActivity.showFirstBuildNotice] shows a
 * non-cancelable Material dialog titled [R.string.title_first_build] with positive
 * [android.R.string.ok] only (no click listener on the button — tapping OK dismisses).
 * Gradle completion does **not** close it; tests must tap **OK**.
 *
 * In instrumented tests [com.itsaky.androidide.utils.isTestMode] is true and that dialog is not
 * shown, so this helper usually no-ops quickly.
 *
 * Material `MaterialButton` may not match legacy
 * `android:id/button1` / `Button` selectors, so we use Espresso [isDialog] + [withText] for the OK
 * label first, then UiAutomator fallbacks.
 */
fun TestContext<Unit>.dismissFirstBuildNoticeIfShown() {
	if (!GeneralPreferences.isFirstBuild) {
		Log.i(TAG, "Skipping first-build dismiss (isFirstBuild=false; dialog not expected)")
		return
	}

	val ctx = InstrumentationRegistry.getInstrumentation().targetContext
	val titleText = ctx.getString(R.string.title_first_build)
	val msgFirstLine = ctx.getString(R.string.msg_first_build).substringBefore('\n').trim()
	val okText = ctx.getString(android.R.string.ok)
	val d = device.uiDevice

	fun titleOrMessageOnScreen(): Boolean {
		val tExact = d.findObject(UiSelector().text(titleText))
		if (tExact.waitForExists(400) && tExact.exists()) return true
		val prefix = titleText.take(10.coerceAtMost(titleText.length).coerceAtLeast(1))
		if (d.findObject(UiSelector().textContains(prefix)).exists()) return true
		if (msgFirstLine.length >= 12) {
			val snippet = msgFirstLine.take(40)
			if (d.findObject(UiSelector().textContains(snippet)).exists()) return true
		}
		return false
	}

	fun materialOkVisible(): Boolean {
		val sel =
			UiSelector()
				.className("com.google.android.material.button.MaterialButton")
				.text(okText)
		val node = d.findObject(sel)
		return node.waitForExists(400) && node.exists()
	}

	fun legacyPositiveVisible(): Boolean {
		val b1 = d.findObject(UiSelector().resourceId("android:id/button1"))
		if (b1.waitForExists(400) && b1.exists()) return true
		val alt = d.findObject(UiSelector().resourceIdMatches(".*:id/button1"))
		return alt.waitForExists(400) && alt.exists()
	}

	fun positiveButtonVisible(): Boolean =
		materialOkVisible() || legacyPositiveVisible()

	/**
	 * Taps the dialog OK action. Prefer Espresso on the dialog window root — matches Material
	 * [MaterialAlertDialogBuilder] reliably.
	 */
	fun clickOkButton(): Boolean {
		val espressoOk =
			runCatching {
				onView(allOf(withText(okText), isDisplayed()))
					.inRoot(isDialog())
					.perform(click())
				Log.i(TAG, "dismissed via Espresso inRoot(isDialog) + withText(OK)")
				true
			}.getOrElse { err ->
				Log.d(TAG, "Espresso OK failed: ${err.message}")
				false
			}
		if (espressoOk) return true

		val selectors =
			listOf(
				UiSelector()
					.className("com.google.android.material.button.MaterialButton")
					.text(okText),
				UiSelector().resourceId("android:id/button1"),
				UiSelector().resourceIdMatches(".*:id/button1"),
				UiSelector().text(okText).clickable(true),
				UiSelector().textMatches("(?i)^ok$").clickable(true),
				UiSelector().className("android.widget.Button").text(okText),
			)
		for ((index, sel) in selectors.withIndex()) {
			val node = d.findObject(sel)
			if (node.waitForExists(2500) && node.exists() && node.isEnabled) {
				val clicked = runCatching { node.click() }.getOrDefault(false)
				if (clicked) {
					Log.i(TAG, "dismissed via UiAutomator strategyIndex=$index")
					return true
				}
			}
		}
		val kakao =
			runCatching {
				EditorScreen {
					firstBuildDialog {
						positiveButton { click() }
					}
				}
				true
			}.getOrDefault(false)
		if (kakao) Log.i(TAG, "dismissed via Kakao firstBuildDialog")
		return kakao
	}

	fun dialogStillShowing(): Boolean =
		titleOrMessageOnScreen() || positiveButtonVisible()

	val deadline = System.currentTimeMillis() + 120_000L
	while (System.currentTimeMillis() < deadline) {
		val hints = titleOrMessageOnScreen()
		val hasOk = positiveButtonVisible()

		if (!hints && !hasOk) {
			Thread.sleep(400)
			continue
		}

		clickOkButton()
		d.waitForIdle(2500)
		if (!dialogStillShowing()) return
		Thread.sleep(500)
	}

	if (dialogStillShowing()) {
		clickOkButton()
		d.waitForIdle(3000)
	}
	if (dialogStillShowing()) {
		throw AssertionError(
			"First-build dialog (title_first_build / OK) still visible — OK was not dismissed",
		)
	}
}
