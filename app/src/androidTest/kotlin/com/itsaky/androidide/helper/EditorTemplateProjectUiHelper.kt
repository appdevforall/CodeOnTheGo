package com.itsaky.androidide.helper

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.R
import android.app.Instrumentation
import com.itsaky.androidide.testing.WaitProgressFormatter
import com.itsaky.androidide.testing.InstrumentationStateProbe
import com.itsaky.androidide.testing.ProbeBuildState
import com.itsaky.androidide.testing.ProbeProjectInitState

private const val TAG = "EditorTemplateUi"

/**
 * UiAutomator helpers for the template → editor flow: quick-run readiness + build result checks.
 */
object EditorTemplateProjectUiHelper {

	enum class BuildOutcome {
		SUCCESS,
		FAILURE,
		TIMEOUT,
	}

	/**
	 * Polls until quick-run is available and clickable, or returns false after [timeoutMs].
	 *
	 * This is a deterministic readiness condition for this scenario because quick-run can only be
	 * tapped when project initialization has completed enough for build execution.
	 */
	fun waitForProjectReadyForQuickRun(
		device: UiDevice,
		context: Context,
		timeoutMs: Long,
	): Boolean {
		val deadline = System.currentTimeMillis() + timeoutMs
		var lastMinuteReported = 0L
		while (System.currentTimeMillis() < deadline) {
			if (InstrumentationStateProbe.projectInitState == ProbeProjectInitState.INITIALIZED) {
				Log.i(TAG, "project init state probe reports INITIALIZED")
				return true
			}
			if (InstrumentationStateProbe.projectInitState == ProbeProjectInitState.FAILED) {
				Log.e(TAG, "project init state probe reports FAILED")
				return false
			}
			if (isQuickRunClickable(device, context)) {
				Log.i(TAG, "quick run is available; project considered initialized")
				return true
			}
			lastMinuteReported =
				logMinuteProgress(
					waitName = "project init",
					timeoutMs = timeoutMs,
					deadlineMs = deadline,
					lastMinuteReported = lastMinuteReported,
				)
			device.waitForIdle(800)
		}
		Log.w(TAG, "Timeout waiting for project init readiness (${timeoutMs}ms)")
		return false
	}

	fun waitForBuildOutcome(
		device: UiDevice,
		context: Context,
		timeoutMs: Long,
	): BuildOutcome {
		val successText = context.getString(R.string.build_status_sucess)
		val failedText = context.getString(R.string.build_status_failed)
		val deadline = System.currentTimeMillis() + timeoutMs
		var lastMinuteReported = 0L
		while (System.currentTimeMillis() < deadline) {
			when (InstrumentationStateProbe.buildState) {
				ProbeBuildState.SUCCESS -> {
					Log.i(TAG, "Build outcome detected by state probe: SUCCESS")
					return BuildOutcome.SUCCESS
				}
				ProbeBuildState.FAILED -> {
					Log.e(TAG, "Build outcome detected by state probe: FAILURE")
					return BuildOutcome.FAILURE
				}
				else -> {
					// Continue with fallback checks below.
				}
			}
			if (hasVisibleText(device, "BUILD SUCCESSFUL") || hasVisibleText(device, successText)) {
				Log.i(TAG, "Build outcome detected: SUCCESS")
				return BuildOutcome.SUCCESS
			}
			if (hasVisibleText(device, "BUILD FAILED") || hasVisibleText(device, failedText)) {
				Log.e(TAG, "Build outcome detected: FAILURE")
				return BuildOutcome.FAILURE
			}
			lastMinuteReported =
				logMinuteProgress(
					waitName = "build result",
					timeoutMs = timeoutMs,
					deadlineMs = deadline,
					lastMinuteReported = lastMinuteReported,
				)
			device.waitForIdle(800)
		}
		Log.w(TAG, "Timeout waiting for build outcome (${timeoutMs}ms)")
		return BuildOutcome.TIMEOUT
	}

	fun dismissInstallationFailedBannerIfShown(
		device: UiDevice,
		context: Context,
		maxAttempts: Int = 3,
	): Boolean {
		val dismiss = context.getString(R.string.dismiss)
		val installationFailed = context.getString(R.string.title_installation_failed)
		repeat(maxAttempts) {
			val hasInstallError =
				hasVisibleText(device, installationFailed) ||
					hasVisibleText(device, "installation failed")
			if (!hasInstallError) {
				return false
			}
			val dismissSelectors =
				listOf(
					UiSelector().text(dismiss),
					UiSelector().textContains(dismiss),
					UiSelector().description(dismiss),
					UiSelector().descriptionContains(dismiss),
				)
			for (sel in dismissSelectors) {
				val btn = device.findObject(sel)
				if (btn.waitForExists(1200) && btn.exists() && btn.isEnabled) {
					val clicked = runCatching { btn.click() }.getOrDefault(false)
					if (clicked) {
						device.waitForIdle(500)
						Log.i(TAG, "Dismissed installation-failed banner")
						return true
					}
				}
			}
			device.waitForIdle(500)
		}
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
						device.waitForIdle(500)
						Log.i(TAG, "quick run clicked (attempt ${attempt + 1})")
						return true
					}
				}
			}
			Log.w(TAG, "quick run not found, retry ${attempt + 1}/$maxAttempts")
			device.waitForIdle(500)
		}
		return false
	}

	private fun isQuickRunClickable(device: UiDevice, context: Context): Boolean {
		val desc = context.getString(R.string.cd_toolbar_quick_run)
		val selectors =
			listOf(
				UiSelector().description(desc),
				UiSelector().descriptionContains(desc),
				UiSelector().text(desc),
				UiSelector().textContains(desc),
			)
		for (sel in selectors) {
			val node = device.findObject(sel)
			if (node.waitForExists(400) && node.exists() && node.isEnabled) {
				return true
			}
		}
		return false
	}

	private fun hasVisibleText(device: UiDevice, text: String): Boolean {
		val exact = device.findObject(UiSelector().text(text))
		if (exact.waitForExists(300) && exact.exists()) return true
		val contains = device.findObject(UiSelector().textContains(text))
		return contains.waitForExists(300) && contains.exists()
	}

	private fun logMinuteProgress(
		waitName: String,
		timeoutMs: Long,
		deadlineMs: Long,
		lastMinuteReported: Long,
	): Long {
		val now = System.currentTimeMillis()
		val waitedMs = (timeoutMs - (deadlineMs - now)).coerceAtLeast(0L)
		val elapsedMinutes = ((timeoutMs - (deadlineMs - now)).coerceAtLeast(0L)) / 60_000L
		if (lastMinuteReported == 0L && waitedMs >= 5_000L) {
			val started = "waiting for $waitName (5m max): started"
			Log.i(TAG, started)
			System.err.println("$TAG: $started")
			println("$TAG: $started")
		}
		if (elapsedMinutes >= 1 && elapsedMinutes > lastMinuteReported) {
			val msg =
				WaitProgressFormatter.formatMinutesElapsedMessage(
					waitName = waitName,
					elapsedMinutes = elapsedMinutes,
					maxMinutes = 5,
				)
			Log.i(TAG, msg)
			System.err.println("$TAG: $msg")
			println("$TAG: $msg")
			sendInstrumentationProgress(msg)
			return elapsedMinutes
		}
		return lastMinuteReported
	}

	private fun sendInstrumentationProgress(message: String) {
		runCatching {
			val instrumentation = InstrumentationRegistry.getInstrumentation()
			val results =
				Bundle().apply {
					putString(Instrumentation.REPORT_KEY_IDENTIFIER, TAG)
					putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$message\n")
				}
			instrumentation.sendStatus(0, results)
		}.onFailure { err ->
			Log.w(TAG, "Failed to send instrumentation progress: ${err.message}")
		}
	}
}
