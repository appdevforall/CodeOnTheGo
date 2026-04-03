package com.itsaky.androidide.testing.android

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.io.FileInputStream

/**
 * Custom [AndroidJUnitRunner] for testing IDE.
 *
 * Disables global animation scales so Espresso idling and Kaspresso interactions stay reliable.
 * Shell `settings` runs from [onStart] (after the instrumentation is fully attached). Doing that in
 * [onCreate] immediately after [super.onCreate] has been seen to abort the run on some emulators
 * (“Starting 0 tests”, app never starts).
 *
 * @author Akash Yadav
 */
@Suppress("UNUSED")
class TestInstrumentationRunner : AndroidJUnitRunner() {
	override fun onCreate(arguments: Bundle?) {
		super.onCreate(arguments)
	}

	override fun onStart() {
		super.onStart()
		try {
			disableGlobalAnimations()
		} catch (_: Throwable) {
			// Never fail test startup if animation scaling is unavailable.
		}
	}

	private fun disableGlobalAnimations() {
		val ui = uiAutomation ?: return
		val commands =
			arrayOf(
				"settings put global window_animation_scale 0",
				"settings put global transition_animation_scale 0",
				"settings put global animator_duration_scale 0",
			)
		for (cmd in commands) {
			try {
				ui.executeShellCommand(cmd).use { pfd ->
					FileInputStream(pfd.fileDescriptor).bufferedReader().readText()
				}
			} catch (_: Throwable) {
				// Best effort (e.g. restricted settings on some images).
			}
		}
	}
}
