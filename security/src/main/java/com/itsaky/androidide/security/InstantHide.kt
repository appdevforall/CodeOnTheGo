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
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
	 * Shows the disguise and finishes [activity] if the disguise is engaged or the install is
	 * neutralized (ADFA-6510): a wipe, or the tamper fingerprint, leaves it the disguise for good. The
	 * markers are checked only when the hidden flag is clear: two file stats and a launcher-label
	 * lookup. An entry point whose
	 * onCreate does work calls this right after `super.onCreate` and returns when it is true, so the
	 * work never starts; the lifecycle callback has already redirected, and this does not repeat it.
	 */
	fun redirectIfHidden(activity: Activity): Boolean {
		if (activity is CryptogramActivity || !(state.hidden || state.neutralized(activity))) return false
		if (!activity.isFinishing) showDisguise(activity, disguiseIntent(activity))
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

	/**
	 * Closes the floating windows and masks the notifications: what outlives the activities when the
	 * disguise engages, by a hide or by a wipe.
	 */
	fun hideOutsideActivities(context: Context) {
		runCatching { onHide(context) }
		runCatching { NeutralNotifications.mask(context) }
	}

	/** Starts the disguise with a new puzzle, as a hide does. */
	fun newDisguiseIntent(context: Context): Intent =
		Intent(context, CryptogramActivity::class.java).addFlags(
			Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
		)

	/**
	 * Starts [disguise], removes the app's other tasks, and finishes [activity]. The disguise has a task of
	 * its own, so the IDE's task stays behind it in Recents unless removed here; [activity] is finished
	 * as well in case it runs in another app's task, which is not the app's to remove.
	 */
	fun showDisguise(
		activity: Activity,
		disguise: Intent,
	) {
		activity.startActivity(disguise)
		activity.getSystemService(ActivityManager::class.java)?.appTasks?.forEach { task ->
			// A task can be gone by now: Android removes the one the disabled launcher alias started.
			runCatching {
				val base = task.taskInfo.baseIntent.component
				if (base?.className != CryptogramActivity::class.java.name) task.finishAndRemoveTask()
			}
		}
		activity.finish()
	}

	/** Whether the soft keyboard shows over [activity]. Robolectric has no keyboard, so tests replace it. */
	internal var keyboardShown: (Activity) -> Boolean = ::softKeyboardShown

	internal fun softKeyboardShown(activity: Activity): Boolean =
		ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

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
				SecurityController(
					activity,
					gesturesEnabled = activity !is GesturesExempt,
					onHide = ::hideOutsideActivities,
					state = state,
					keyboardShown = { keyboardShown(it) },
				)
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

		override fun onWindowFocusChanged(hasFocus: Boolean) {
			controller.onWindowFocusChanged(hasFocus)
			delegate.onWindowFocusChanged(hasFocus)
		}
	}
}
