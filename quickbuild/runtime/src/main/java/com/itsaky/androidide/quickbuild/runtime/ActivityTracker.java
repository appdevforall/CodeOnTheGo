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
 * Registered via {@link Application#registerActivityLifecycleCallbacks}. Activities are held weakly so the tracker never keeps a destroyed one alive, and access is synchronized because deploy code reads {@link #topActivity} off the main thread.
 */
final class ActivityTracker implements Application.ActivityLifecycleCallbacks {

	private final QuickBuildRuntime runtime;
	private final List<WeakReference<Activity>> created = new ArrayList<WeakReference<Activity>>();
	private WeakReference<Activity> resumed;

	ActivityTracker(QuickBuildRuntime runtime) {
		this.runtime = runtime;
	}

	@Override
	public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
		ResourceStore.INSTANCE.attachTo(activity.getResources());
		synchronized (this) {
			created.add(new WeakReference<Activity>(activity));
		}
		runtime.onActivityCreated(activity);
	}

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
			}
		}
	}

	@Override
	public void onActivityPaused(Activity activity) {}

	/**
	 * Attaches swapped resources early enough that the activity's own inflation sees them.
	 *
	 * Only fires on API 29+; on older devices {@link #onActivityCreated} is the later backstop.
	 */
	@Override
	public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
		ResourceStore.INSTANCE.attachTo(activity.getResources());
	}

	@Override
	public void onActivityResumed(Activity activity) {
		synchronized (this) {
			resumed = new WeakReference<Activity>(activity);
		}
		runtime.onActivityResumed(activity);
	}

	@Override
	public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

	@Override
	public void onActivityStarted(Activity activity) {}

	@Override
	public void onActivityStopped(Activity activity) {}

	/**
	 * Picks the activity a reload should recreate: the resumed one, else the newest live one.
	 *
	 * @return null when no live activity remains, meaning the caller should launch the entry activity instead.
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
