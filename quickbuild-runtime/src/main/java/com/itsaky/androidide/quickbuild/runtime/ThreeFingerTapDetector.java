package com.itsaky.androidide.quickbuild.runtime;

/**
 * Detects a 3-pointer down burst: three fingers landing within {@link #BURST_WINDOW_MS} of the first touch (plan A3, the "return to CoGo" gesture). Pure observation - the caller feeds it every touch event and ALWAYS passes the event through to the app regardless of the return value, so normal 1-2 finger interaction is never consumed or delayed. Fires at most once per gesture (resets when the last finger lifts).
 *
 * Plain Java with int action constants (mirroring MotionEvent's public API values) so the state machine is JVM-unit-testable without android.jar's stubbed MotionEvent.
 */
final class ThreeFingerTapDetector {

	// MotionEvent.getActionMasked() values; stable public API constants.
	static final int ACTION_DOWN = 0;
	static final int ACTION_UP = 1;
	static final int ACTION_CANCEL = 3;
	static final int ACTION_POINTER_DOWN = 5;

	/** A slow 3-finger pile-up (e.g. resting a hand) must not trigger the gesture. */
	static final long BURST_WINDOW_MS = 300;

	private long gestureStartMillis = -1;
	private boolean firedThisGesture;

	/**
	 * Observes one touch event. Returns true exactly when the 3rd pointer of a burst lands - the caller's cue to act. Never true again until all fingers lift.
	 *
	 * @param actionMasked
	 *            MotionEvent.getActionMasked()
	 * @param pointerCount
	 *            MotionEvent.getPointerCount()
	 * @param eventTimeMillis
	 *            MotionEvent.getEventTime()
	 */
	boolean onTouch(int actionMasked, int pointerCount, long eventTimeMillis) {
		switch (actionMasked) {
		case ACTION_DOWN:
			gestureStartMillis = eventTimeMillis;
			firedThisGesture = false;
			return false;
		case ACTION_POINTER_DOWN:
			if (gestureStartMillis < 0 || firedThisGesture || pointerCount != 3) {
				return false;
			}
			if (eventTimeMillis - gestureStartMillis > BURST_WINDOW_MS) {
				return false;
			}
			firedThisGesture = true;
			return true;
		case ACTION_UP:
		case ACTION_CANCEL:
			gestureStartMillis = -1;
			firedThisGesture = false;
			return false;
		default:
			return false;
		}
	}
}
