package com.itsaky.androidide.api

import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import java.lang.ref.WeakReference

/**
 * Provides a weak reference to the current EditorHandlerActivity
 * to allow decoupled services to trigger UI actions.
 */
object ActionContextProvider {
	// IDEApiFacade.runApp() (a suspend fun with no explicit Dispatchers.Main) reads getActivity() with
	// no guarantee its caller is already on the main thread that writes this -- @Volatile establishes
	// the same happens-before guarantee this PR's sibling PendingDeepLinkOpen.value already relies on
	// for the identical cross-thread read/write pattern.
	@Volatile
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
	 *
	 * [setActivity] is called from `onCreate` (not `onResume`), so an instance is discoverable for
	 * its entire lifetime rather than leaving a blind window between `onCreate` and `onResume` where
	 * a caller like [com.itsaky.androidide.activities.DeepLinkActivity] would otherwise see `null` for
	 * a live instance and start a second, redundant open flow via `MainActivity`. The `isFinishing`/
	 * `isDestroyed` filter above still excludes an instance that registered but is already tearing
	 * down.
	 */
	fun getActivity(): EditorHandlerActivity? = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}
