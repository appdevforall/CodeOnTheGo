package com.itsaky.androidide.api

import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import java.lang.ref.WeakReference

/**
 * Provides a weak reference to the current EditorHandlerActivity
 * to allow decoupled services to trigger UI actions.
 */
object ActionContextProvider {
	private var activityRef: WeakReference<EditorHandlerActivity>? = null

	fun setActivity(activity: EditorHandlerActivity) {
		this.activityRef = WeakReference(activity)
	}

	fun clearActivity() {
		this.activityRef?.clear()
		this.activityRef = null
	}

	fun clearActivity(activity: EditorHandlerActivity) {
		if (this.activityRef?.get() === activity) {
			clearActivity()
		}
	}

	/**
	 * The current, live [EditorHandlerActivity], or `null` if there is none -- including one that
	 * called `finish()` but hasn't run `onDestroy()` (and cleared itself via [clearActivity]) yet.
	 * Android delivers `singleTask` intents to a finishing instance's [android.app.Activity.onNewIntent]
	 * inconsistently (a genuinely new instance can be created instead), so callers that route based
	 * on "is there a live editor to hand this off to" need this distinction, not just non-null.
	 */
	fun getActivity(): EditorHandlerActivity? = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}
