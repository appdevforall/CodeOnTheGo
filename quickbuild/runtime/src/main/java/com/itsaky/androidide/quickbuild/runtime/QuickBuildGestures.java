package com.itsaky.androidide.quickbuild.runtime;

import android.app.Activity;
import android.content.Intent;
import android.view.MotionEvent;

/**
 * Turns a 3-finger tap in the proxy app into a jump back to CoGo.
 *
 * Public because generated proxy activities call it from their {@code dispatchTouchEvent} override (see ProxySourceGenerator in :gradle-plugin); everything else in this AAR is package-private. It only observes the event - the proxy always forwards to {@code super.dispatchTouchEvent} afterwards, so the app under test sees every touch unmodified and undelayed.
 */
public final class QuickBuildGestures {

	private static final ThreeFingerTapDetector DETECTOR = new ThreeFingerTapDetector();

	/**
	 * Feeds one touch event to the detector and returns to CoGo when the gesture completes.
	 *
	 * Never throws and never consumes the event: a gesture bug must not break the app's touch input.
	 *
	 * @param activity
	 *            the proxy activity dispatching the touch, used to launch CoGo when the gesture fires; null is ignored
	 * @param event
	 *            the event being dispatched, read only for action, pointer count and event time; null is ignored
	 */
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

	/**
	 * Launches CoGo, or logs when it has no launch intent.
	 *
	 * Package-private so {@link ReturnToIdeButton} shares this exact path: the gesture and the visible button are two triggers for one action.
	 *
	 * @param activity
	 *            the activity to start CoGo from; must be non-null, as both callers have one
	 */
	static void returnToIde(Activity activity) {
		Intent launch = activity.getPackageManager()
				.getLaunchIntentForPackage(QuickBuildClient.IDE_PACKAGE);
		if (launch == null) {
			RuntimeLog.w("cannot return to CoGo: no launch intent for "
					+ QuickBuildClient.IDE_PACKAGE);
			return;
		}
		RuntimeLog.i("returning to CoGo");
		activity.startActivity(launch);
	}

	private QuickBuildGestures() {}
}
