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
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.Window
import java.util.WeakHashMap

/**
 * Instant Hide (ADFA-40) on every screen of the app. It is mounted once, from the Application, rather
 * than on a base class, so a screen with another base class (the terminal, onboarding) or an entry
 * point added later cannot be left out: while the disguise is engaged no activity but the disguise
 * shows, and while it is armed every screen gets the triggers and FLAG_SECURE.
 */
object InstantHide {
	/**
	 * A screen whose gestures and sensors must not hide the IDE: the security settings, where the user
	 * arms and practises them. FLAG_SECURE still applies there.
	 */
	interface GesturesExempt

	internal var state: HideState = PreferencesHideState

	private val controllers = WeakHashMap<Activity, SecurityController>()
	private var onHide: (Context) -> Unit = {}

	/**
	 * @param onHide closes what the app shows outside its activities, such as floating windows; runs on
	 *   every hide, before the disguise starts.
	 */
	fun register(
		application: Application,
		onHide: (Context) -> Unit,
	) {
		this.onHide = onHide
		application.registerActivityLifecycleCallbacks(Callbacks)
	}

	/**
	 * Shows the disguise and finishes [activity] if the disguise is engaged. An entry point whose
	 * onCreate does work calls this right after `super.onCreate` and returns when it is true, so the
	 * work never starts; the lifecycle callback has already redirected, and this does not repeat it.
	 */
	fun redirectIfHidden(activity: Activity): Boolean {
		if (activity is CryptogramActivity || !state.hidden) return false
		if (!activity.isFinishing) {
			activity.startActivity(disguiseIntent(activity))
			activity.finish()
		}
		return true
	}

	/**
	 * Brings the disguise forward. A running one is reused rather than recreated, so a link or a file
	 * opened while hidden does not reset the puzzle.
	 */
	fun disguiseIntent(context: Context): Intent =
		Intent(context, CryptogramActivity::class.java).addFlags(
			Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
		)

	/** Re-applies FLAG_SECURE to [activity] at once, for a caller that has just armed or disarmed. */
	fun applyScreenSecurity(activity: Activity) {
		controllers[activity]?.applyScreenSecurity()
	}

	private object Callbacks : Application.ActivityLifecycleCallbacks {
		override fun onActivityCreated(
			activity: Activity,
			savedInstanceState: Bundle?,
		) {
			if (activity is CryptogramActivity || redirectIfHidden(activity)) return
			val controller =
				SecurityController(activity, gesturesEnabled = activity !is GesturesExempt, onHide = onHide, state = state)
			controllers[activity] = controller
			activity.window.callback = InputWatcher(activity.window.callback, controller)
		}

		override fun onActivityResumed(activity: Activity) {
			// An IDE screen left in another app's task when the disguise engaged.
			if (redirectIfHidden(activity)) return
			controllers[activity]?.apply {
				applyScreenSecurity()
				onResume()
			}
		}

		override fun onActivityPaused(activity: Activity) {
			controllers[activity]?.onPause()
		}

		override fun onActivityDestroyed(activity: Activity) {
			controllers.remove(activity)?.onDestroy()
		}

		override fun onActivityStarted(activity: Activity) = Unit

		override fun onActivityStopped(activity: Activity) = Unit

		override fun onActivitySaveInstanceState(
			activity: Activity,
			outState: Bundle,
		) = Unit
	}

	/**
	 * Passes every input the window receives to [controller] on its way to the activity: touches for the
	 * gestures, and all of it for the inactivity timer.
	 */
	private class InputWatcher(
		private val delegate: Window.Callback,
		private val controller: SecurityController,
	) : Window.Callback by delegate {
		override fun dispatchTouchEvent(event: MotionEvent): Boolean {
			controller.onTouchEvent(event)
			return delegate.dispatchTouchEvent(event)
		}

		override fun dispatchKeyEvent(event: KeyEvent): Boolean {
			controller.onUserInput()
			return delegate.dispatchKeyEvent(event)
		}

		override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
			controller.onUserInput()
			return delegate.dispatchKeyShortcutEvent(event)
		}

		override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
			controller.onUserInput()
			return delegate.dispatchGenericMotionEvent(event)
		}

		override fun dispatchTrackballEvent(event: MotionEvent): Boolean {
			controller.onUserInput()
			return delegate.dispatchTrackballEvent(event)
		}

		// Java default methods: forwarded by hand so the activity's own still run.
		override fun onProvideKeyboardShortcuts(
			data: MutableList<KeyboardShortcutGroup>?,
			menu: Menu?,
			deviceId: Int,
		) = delegate.onProvideKeyboardShortcuts(data, menu, deviceId)

		override fun onPointerCaptureChanged(hasCapture: Boolean) = delegate.onPointerCaptureChanged(hasCapture)
	}
}
