package com.itsaky.androidide.quickbuild.runtime;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Tracks the process's live activities so a reload knows which one to recreate.
 *
 * Registered via {@link Application#registerActivityLifecycleCallbacks}. Activities are held weakly so the tracker never keeps a destroyed one alive, and access is synchronized because the binder thread reads {@link #hasResumedActivity} while lifecycle callbacks mutate the lists on the main thread.
 */
final class ActivityTracker implements Application.ActivityLifecycleCallbacks {

	private final QuickBuildRuntime runtime;

	/** Every live activity, oldest first, so the newest is the last live entry. */
	private final List<WeakReference<Activity>> created = new ArrayList<WeakReference<Activity>>();

	/** The most recently resumed activity, or null once it is destroyed. */
	private WeakReference<Activity> resumed;

	/**
	 * Whether {@link #resumed} is still in the resumed state. Cleared on its pause, so this distinguishes "on screen now" from "was on screen last"; {@link #resumed} alone cannot, because it survives a home press until the activity is destroyed.
	 */
	private boolean resumedActive;

	/**
	 * @param runtime
	 *            the runtime to notify of activity creation and resume; held strongly, which is safe because the runtime outlives every activity
	 */
	ActivityTracker(QuickBuildRuntime runtime) {
		this.runtime = runtime;
	}

	/**
	 * Records the activity, lets the runtime do its first-activity Context work, then attaches swapped resources.
	 *
	 * The runtime call comes first because it is what creates the resource loader when a cold start adopts a persisted generation; attaching before it would be a no-op, leaving this activity resolving against the baseline table for its whole lifetime.
	 *
	 * @param activity
	 *            the activity being created
	 * @param savedInstanceState
	 *            the framework's saved state; unused here
	 */
	@Override
	public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
		synchronized (this) {
			created.add(new WeakReference<Activity>(activity));
		}
		runtime.onActivityCreated(activity);
		ResourceStore.INSTANCE.attachTo(activity.getResources());
	}

	/**
	 * Drops the activity, and any reference whose activity has already been collected.
	 *
	 * @param activity
	 *            the activity being destroyed
	 */
	@Override
	public void onActivityDestroyed(Activity activity) {
		synchronized (this) {
			Iterator<WeakReference<Activity>> it = created.iterator();
			while (it.hasNext()) {
				Activity tracked = it.next().get();
				if (tracked == null || tracked == activity) {
					it.remove();
				}
			}
			if (resumed != null && resumed.get() == activity) {
				resumed = null;
				resumedActive = false;
			}
		}
	}

	/**
	 * Marks the app as off screen when its resumed activity pauses.
	 *
	 * In an in-app A-to-B transition, A's pause runs before B's resume, so the flag dips and recovers within the same handoff; only a real background (home, app switch) leaves it cleared.
	 *
	 * @param activity
	 *            the activity leaving the resumed state
	 */
	@Override
	public void onActivityPaused(Activity activity) {
		synchronized (this) {
			if (resumed != null && resumed.get() == activity) {
				resumedActive = false;
			}
		}
	}

	/**
	 * Attaches swapped resources early enough that the activity's own inflation sees them.
	 *
	 * Only fires on API 29+; on older devices {@link #onActivityCreated} is the later backstop.
	 *
	 * The runtime's Context work runs here too, because this is the only hook that precedes the activity's own inflation: on a cold start that adopts a persisted generation the resources do not exist until it runs, so deferring it to {@link #onActivityCreated} would let the first activity inflate against the baseline table. Every step of it is idempotent.
	 *
	 * @param activity
	 *            the activity about to be created, used for its Resources and as the runtime's first Context
	 * @param savedInstanceState
	 *            the framework's saved state; unused here
	 */
	@Override
	public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
		runtime.onActivityCreated(activity);
		ResourceStore.INSTANCE.attachTo(activity.getResources());
	}

	/**
	 * Marks the activity as the reload target and lets the runtime bind its overlay to it.
	 *
	 * @param activity
	 *            the activity now in the foreground
	 */
	@Override
	public void onActivityResumed(Activity activity) {
		synchronized (this) {
			resumed = new WeakReference<Activity>(activity);
			resumedActive = true;
		}
		runtime.onActivityResumed(activity);
	}

	/**
	 * @param activity
	 *            the activity being saved; unused, since what a restart waits for is the stop that follows, not this callback - the framework reports the state to the server from the stop, and killing between the two is what force-removes the record
	 * @param outState
	 *            the framework's bundle; untouched
	 */
	@Override
	public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

	/**
	 * Counts the activity into the set a restart deploy waits to empty before killing the process.
	 *
	 * @param activity
	 *            the activity being started; unused, since the wait is on the census rather than on any one of them
	 */
	@Override
	public void onActivityStarted(Activity activity) {
		runtime.onActivityStarted();
	}

	/**
	 * Counts the activity back out of that set, which is where ActivityThread captures its state.
	 *
	 * @param activity
	 *            the activity being stopped; unused, as above
	 */
	@Override
	public void onActivityStopped(Activity activity) {
		runtime.onActivityStopped();
	}

	/**
	 * Whether the app is on screen: some live, non-finishing activity is currently resumed.
	 *
	 * @return true when the most recently resumed activity is still resumed and alive
	 */
	synchronized boolean hasResumedActivity() {
		if (!resumedActive || resumed == null) {
			return false;
		}
		Activity top = resumed.get();
		return top != null && !top.isFinishing();
	}

	/**
	 * Picks the activity a reload should recreate: the resumed one, else the newest live one.
	 *
	 * @return the resumed activity, else the newest live one, else null; a finishing activity is skipped, since recreating one would just have it finish again
	 */
	synchronized Activity topActivity() {
		if (resumed != null) {
			Activity top = resumed.get();
			if (top != null && !top.isFinishing()) {
				return top;
			}
		}
		for (int i = created.size() - 1; i >= 0; i--) {
			Activity candidate = created.get(i).get();
			if (candidate != null && !candidate.isFinishing()) {
				return candidate;
			}
		}
		return null;
	}
}
