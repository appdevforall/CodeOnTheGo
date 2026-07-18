package com.itsaky.androidide.quickbuild.runtime;

import android.app.Activity;
import android.content.Intent;
import android.view.MotionEvent;

/**
 * App-switcher gesture (plan A3): a 3-finger tap in the test app jumps back to CoGo.
 *
 * PUBLIC because the generated proxy activities call it from their {@code dispatchTouchEvent} override (see ProxySourceGenerator in :gradle-plugin) - everything else in this AAR stays package-private. The contract with the proxies: this method only OBSERVES the event; the proxy always forwards to {@code super.dispatchTouchEvent} afterwards, so the app under test sees every touch unmodified and undelayed.
 */
public final class QuickBuildGestures {

	private static final ThreeFingerTapDetector DETECTOR = new ThreeFingerTapDetector();

	/** Never throws and never consumes: a gesture bug must not break the app's touch input. */
	public static void onDispatchTouchEvent(Activity activity, MotionEvent event) {
		if (activity == null || event == null) {
			return;
		}
		try {
			boolean fired = DETECTOR.onTouch(event.getActionMasked(), event.getPointerCount(),
					event.getEventTime());
			if (fired) {
				returnToIde(activity);
			}
		} catch (Throwable error) {
			RuntimeLog.w("gesture detection failed", error);
		}
	}

	private static void returnToIde(Activity activity) {
		Intent launch = activity.getPackageManager()
				.getLaunchIntentForPackage(QuickBuildClient.IDE_PACKAGE);
		if (launch == null) {
			RuntimeLog.w("cannot return to CoGo: no launch intent for "
					+ QuickBuildClient.IDE_PACKAGE);
			return;
		}
		RuntimeLog.i("3-finger tap: returning to CoGo");
		activity.startActivity(launch);
	}

	private QuickBuildGestures() {}
}
