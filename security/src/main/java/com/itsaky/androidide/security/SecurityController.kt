/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.security

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Toast
import com.itsaky.androidide.resources.R as ResR

/**
 * Drives the Instant Hide (ADFA-40) triggers for one IDE activity: the three-finger tap, the
 * screen-off broadcast, the face-down sensor, and the inactivity timer. Any of them engages the
 * disguise. It also applies FLAG_SECURE while Instant Hide is armed (masking the IDE in Recents and
 * blocking screenshots), unless the testing override file is present, in which case it leaves
 * screenshots working and warns once.
 *
 * [InstantHide] mounts one on every activity and forwards its lifecycle and input.
 *
 * @param gesturesEnabled false where the triggers must not fire; FLAG_SECURE still applies.
 * @param onHide runs on every hide, before the disguise starts.
 * @param keyboardShown whether the soft keyboard shows over the activity.
 */
internal class SecurityController(
	private val activity: Activity,
	private val gesturesEnabled: Boolean,
	private val onHide: (Context) -> Unit,
	private val state: HideState,
	private val keyboardShown: (Activity) -> Boolean,
) {
	companion object {
		private const val INACTIVITY_TIMEOUT_MS = 60_000L
	}

	private val enabled: Boolean get() = state.armed

	private val triggersOn: Boolean get() = enabled && gesturesEnabled

	private var hideDetector: GestureDetectorHelper.ThreeFingerTapDetector? = null
	private var screenOffReceiver: BroadcastReceiver? = null

	private val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
	private val gravitySensor =
		sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
			?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
	private val faceDown = FaceDownDetector()

	private val inactivityHandler = Handler(Looper.getMainLooper())
	private val inactivityRunnable =
		Runnable {
			if (userMayBeActive()) resetInactivityTimer() else triggerInstantHide()
		}

	private var advisoryShown = false

	// Typing on the soft keyboard reaches the keyboard's window, and so does the key that closes it, so
	// its opening and closing are the input this window sees. Either one lays the window out again.
	private var keyboardWasShown = false
	private val keyboardWatcher =
		ViewTreeObserver.OnGlobalLayoutListener {
			val shown = keyboardShown(activity)
			if (shown != keyboardWasShown) {
				keyboardWasShown = shown
				onUserInput()
			}
		}

	private val sensorListener =
		object : SensorEventListener {
			override fun onSensorChanged(event: SensorEvent?) {
				if (event == null || !triggersOn) return
				if (event.sensor.type != Sensor.TYPE_GRAVITY && event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
				if (faceDown.onGravityZ(event.values[2])) triggerInstantHide()
			}

			override fun onAccuracyChanged(
				sensor: Sensor?,
				accuracy: Int,
			) {}
		}

	/** Applies the FLAG_SECURE policy. Safe to call on any activity; call on every resume. */
	fun applyScreenSecurity() {
		val overridden = SecurityOverride.isScreenshotBlockingDisabled()
		if (enabled && !overridden) {
			activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		} else {
			activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
		}
		if (enabled && overridden && !advisoryShown) {
			advisoryShown = true
			Toast.makeText(activity, ResR.string.instant_hide_screenshot_override, Toast.LENGTH_LONG).show()
		}
	}

	fun onResume() {
		if (!gesturesEnabled) return
		// The screen-off receiver must survive onPause (the pause races the screen-off broadcast), so
		// it is registered once here and torn down only in onDestroy.
		if (screenOffReceiver == null) {
			screenOffReceiver =
				object : BroadcastReceiver() {
					override fun onReceive(
						context: Context?,
						intent: Intent?,
					) {
						if (intent?.action == Intent.ACTION_SCREEN_OFF && enabled) triggerInstantHide()
					}
				}
			activity.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
		}

		if (enabled) {
			hideDetector = GestureDetectorHelper.ThreeFingerTapDetector(activity) { triggerInstantHide() }
			faceDown.reset()
			gravitySensor?.let { sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }
			keyboardWasShown = keyboardShown(activity)
			activity.window.decorView.viewTreeObserver
				.addOnGlobalLayoutListener(keyboardWatcher)
			resetInactivityTimer()
		} else {
			hideDetector = null
		}
	}

	fun onPause() {
		sensorManager?.unregisterListener(sensorListener)
		activity.window.decorView.viewTreeObserver
			.removeOnGlobalLayoutListener(keyboardWatcher)
		stopInactivityTimer()
	}

	fun onDestroy() {
		sensorManager?.unregisterListener(sensorListener)
		stopInactivityTimer()
		screenOffReceiver?.let {
			runCatching { activity.unregisterReceiver(it) }
			screenOffReceiver = null
		}
	}

	fun onTouchEvent(ev: MotionEvent) {
		if (!triggersOn) return
		resetInactivityTimer()
		hideDetector?.onTouchEvent(ev)
	}

	/** Any input other than a touch on the activity's window: a key, a mouse or trackball move. */
	fun onUserInput() {
		if (triggersOn) resetInactivityTimer()
	}

	/** The window getting the focus back from a dialog or popup, which took input the window never saw. */
	fun onWindowFocusChanged(hasFocus: Boolean) {
		if (hasFocus) onUserInput()
	}

	private fun resetInactivityTimer() {
		stopInactivityTimer()
		if (triggersOn) inactivityHandler.postDelayed(inactivityRunnable, INACTIVITY_TIMEOUT_MS)
	}

	/**
	 * Whether the user may still be at the screen although no input reached its window: typing on the
	 * soft keyboard, whose touches go to the keyboard's window, or working in a dialog or popup, which
	 * holds the focus. A phone left like that still hides when its screen times out, through the
	 * screen-off trigger. Closing either restarts the minute.
	 */
	private fun userMayBeActive(): Boolean = keyboardShown(activity) || !activity.hasWindowFocus()

	private fun stopInactivityTimer() = inactivityHandler.removeCallbacks(inactivityRunnable)

	private fun triggerInstantHide() {
		// Every IDE activity keeps a screen-off receiver while paused, so one broadcast reaches them all;
		// the first hides, and a second would restart the disguise and reset its puzzle.
		if (!enabled || state.hidden || activity.isFinishing) return
		state.hidden = true
		LauncherAliasManager.setStealthMode(activity, true)
		// Floating windows and notifications outlive the activity; they must not show the IDE.
		runCatching { onHide(activity.applicationContext) }
		runCatching { NeutralNotifications.mask(activity) }
		InstantHide.showDisguise(activity, InstantHide.newDisguiseIntent(activity))
	}
}
