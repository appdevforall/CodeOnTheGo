package com.itsaky.androidide.helper

import android.content.Context
import android.util.Log
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.R

private const val TAG = "EditorTemplateUi"

/**
 * UiAutomator helpers for the template → editor flow: localized status text and toolbar quick run.
 */
object EditorTemplateProjectUiHelper {

	/**
	 * Polls until [R.string.msg_project_initialized] is visible, or returns false after [timeoutMs].
	 */
	fun waitForProjectInitializedLocalized(
		device: UiDevice,
		context: Context,
		timeoutMs: Long,
	): Boolean {
		val text = context.getString(R.string.msg_project_initialized)
		val prefix = text.take(12.coerceAtMost(text.length).coerceAtLeast(1))
		val deadline = System.currentTimeMillis() + timeoutMs
		var lastLog = 0L
		while (System.currentTimeMillis() < deadline) {
			val exact = device.findObject(UiSelector().text(text))
			if (exact.waitForExists(800) && exact.exists()) {
				val b = runCatching { exact.visibleBounds }.getOrNull()
				if (b != null && b.width() > 4 && b.height() > 4) {
					Log.i(TAG, "msg_project_initialized visible (exact match)")
					return true
				}
			}
			val partial = device.findObject(UiSelector().textContains(prefix))
			if (partial.waitForExists(400) && partial.exists()) {
				val b = runCatching { partial.visibleBounds }.getOrNull()
				if (b != null && b.width() > 4 && b.height() > 4) {
					Log.i(TAG, "msg_project_initialized visible (prefix match)")
					return true
				}
			}
			val now = System.currentTimeMillis()
			if (now - lastLog > 30_000L) {
				Log.i(TAG, "Still waiting for project initialized… ${(deadline - now) / 1000}s left")
				lastLog = now
			}
			device.waitForIdle(800)
		}
		Log.w(TAG, "Timeout waiting for project initialized (${timeoutMs}ms)")
		return false
	}

	/**
	 * Clicks the editor quick-run control using [R.string.cd_toolbar_quick_run] (and text fallbacks).
	 */
	fun clickQuickRunToolbar(
		device: UiDevice,
		context: Context,
		maxAttempts: Int = 5,
	): Boolean {
		val desc = context.getString(R.string.cd_toolbar_quick_run)
		repeat(maxAttempts) { attempt ->
			val selectors =
				listOf(
					UiSelector().description(desc),
					UiSelector().descriptionContains(desc),
					UiSelector().text(desc),
					UiSelector().textContains(desc),
				)
			for (sel in selectors) {
				val node = device.findObject(sel)
				if (node.waitForExists(2500) && node.exists() && node.isEnabled) {
					val clicked = runCatching { node.click() }.getOrDefault(false)
					if (clicked) {
						device.waitForIdle(2000)
						Log.i(TAG, "quick run clicked (attempt ${attempt + 1})")
						return true
					}
				}
			}
			Log.w(TAG, "quick run not found, retry ${attempt + 1}/$maxAttempts")
			device.waitForIdle(2000)
		}
		return false
	}
}
