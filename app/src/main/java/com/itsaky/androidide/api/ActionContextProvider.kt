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
	 * The current registered [EditorHandlerActivity], or `null` if there is none. Deliberately NOT
	 * filtered on `isFinishing`/`isDestroyed` (unlike [getLiveActivity]): pre-existing consumers
	 * depend on getting the instance even during a finishing window -- e.g. a floating
	 * `EditorPanelDockableContent`, documented to outlive the activity, and `IDEApiFacade.runApp`,
	 * which should still launch the built app rather than report "no active IDE window".
	 */
	fun getActivity(): EditorHandlerActivity? = activityRef?.get()

	/**
	 * Like [getActivity], but `null` also for an instance that is already tearing down -- one that
	 * called `finish()` but hasn't run `onDestroy()` (and cleared itself via [clearActivity]) yet.
	 * Android delivers `singleTask` intents to a finishing instance's [android.app.Activity.onNewIntent]
	 * inconsistently (a genuinely new instance can be created instead), so callers that route based
	 * on "is there a live editor to hand this off to" -- the deep-link routing in
	 * [com.itsaky.androidide.activities.DeepLinkActivity] -- need this distinction, not just non-null.
	 *
	 * [setActivity] is called from both `onCreate` and `onResume`: `onCreate` closes the blind window
	 * between `onCreate` and `onResume` where a caller like
	 * [com.itsaky.androidide.activities.DeepLinkActivity] would otherwise see `null` for a live
	 * instance and start a second, redundant open flow via `MainActivity`; `onResume` lets an instance
	 * reclaim this registration whenever it becomes foreground-active again, in case a different,
	 * stale-duplicate instance briefly registered over it and was destroyed without anything else
	 * restoring it. The `isFinishing`/`isDestroyed` filter here still excludes an instance that
	 * registered but is already tearing down.
	 */
	fun getLiveActivity(): EditorHandlerActivity? = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}
