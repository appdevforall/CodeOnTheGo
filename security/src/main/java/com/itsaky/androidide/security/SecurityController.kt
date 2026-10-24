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
import android.view.WindowManager
import android.widget.Toast
import com.itsaky.androidide.preferences.internal.SecurityPreferences
import com.itsaky.androidide.resources.R as ResR

/**
 * Drives the Instant Hide (ADFA-40) triggers for a host IDE activity: the three-finger tap, the
 * screen-off broadcast, the face-down sensor pair, and the inactivity timer. Any of them engages the
 * disguise. It also applies FLAG_SECURE while Instant Hide is armed (masking the IDE in Recents and
 * blocking screenshots), unless the testing override file is present, in which case it leaves
 * screenshots working and warns once.
 *
 * The host forwards its lifecycle and touch events; see `EdgeToEdgeIDEActivity`.
 */
class SecurityController(
	private val activity: Activity,
) {
	companion object {
		private const val INACTIVITY_TIMEOUT_MS = 60_000L

		// Android gravity Z goes negative when the screen faces down (~-9.8 when fully inverted).
		private const val FACE_DOWN_Z_THRESHOLD = -6.0f
	}

	private val enabled: Boolean get() = SecurityPreferences.instantHideEnabled

	private var hideDetector: GestureDetectorHelper.ThreeFingerTapDetector? = null
	private var screenOffReceiver: BroadcastReceiver? = null

	private val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
	private val gravitySensor =
		sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
			?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
	private var isFaceDown = false

	private val inactivityHandler = Handler(Looper.getMainLooper())
	private val inactivityRunnable = Runnable { triggerInstantHide() }

	private var advisoryShown = false

	private val sensorListener =
		object : SensorEventListener {
			override fun onSensorChanged(event: SensorEvent?) {
				if (event == null || !enabled) return
				if (event.sensor.type != Sensor.TYPE_GRAVITY && event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
				val faceDownNow = event.values[2] <= FACE_DOWN_Z_THRESHOLD
				// Fire once on the flip into face-down, not repeatedly while it stays inverted.
				if (faceDownNow && !isFaceDown) triggerInstantHide()
				isFaceDown = faceDownNow
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
			gravitySensor?.let { sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }
			resetInactivityTimer()
		} else {
			hideDetector = null
		}
	}

	fun onPause() {
		sensorManager?.unregisterListener(sensorListener)
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
		if (!enabled) return
		resetInactivityTimer()
		hideDetector?.onTouchEvent(ev)
	}

	private fun resetInactivityTimer() {
		stopInactivityTimer()
		if (enabled) inactivityHandler.postDelayed(inactivityRunnable, INACTIVITY_TIMEOUT_MS)
	}

	private fun stopInactivityTimer() = inactivityHandler.removeCallbacks(inactivityRunnable)

	private fun triggerInstantHide() {
		if (!enabled || activity.isFinishing) return
		SecurityPreferences.isHiddenStateActive = true
		LauncherAliasManager.setStealthMode(activity, true)
		activity.startActivity(
			Intent(activity, CryptogramActivity::class.java).apply {
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
			},
		)
		activity.finish()
	}
}
