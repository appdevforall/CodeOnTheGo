package com.itsaky.androidide.quickbuild.runtime;

/**
 * Recognizes three fingers landing within {@link #BURST_WINDOW_MS} of the first touch.
 *
 * Observation only: the caller passes every event through to the app whatever this returns, so
 * normal one- and two-finger interaction is never consumed or delayed. Fires at most once per
 * gesture and resets when the last finger lifts. Takes int action constants that mirror
 * MotionEvent's public values, so the state machine is JVM-unit-testable without android.jar's
 * stubbed MotionEvent.
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
	 * Advances the gesture state machine with one touch event.
	 *
	 * @param actionMasked MotionEvent.getActionMasked(); anything but the four constants above
	 *     leaves the state machine untouched
	 * @param pointerCount MotionEvent.getPointerCount(), the number of fingers down after this
	 *     event
	 * @param eventTimeMillis MotionEvent.getEventTime(), an uptime-based millisecond clock; only
	 *     differences against the gesture start are used, never wall-clock time
	 * @return true exactly when the third pointer of a burst lands, and not again until all
	 *     fingers lift
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
