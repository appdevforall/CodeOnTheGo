package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins that actions outside the detector's vocabulary are pure pass-throughs.
 *
 * MOVE, POINTER_UP and the rest must neither fire the gesture nor disturb an in-flight burst: fingers always drift a little between landing, so a MOVE that reset the burst would make the 3-finger tap nearly impossible to perform.
 */
class ThreeFingerTapDetectorMoveEventTest {

	/** MotionEvent.ACTION_MOVE; not a constant on the detector because it ignores it. */
	private static final int ACTION_MOVE = 2;

	@Test
	void moveEventsBetweenPointerDownsDoNotDisturbTheBurst() {
		ThreeFingerTapDetector detector = new ThreeFingerTapDetector();

		detector.onTouch(ThreeFingerTapDetector.ACTION_DOWN, 1, 0);
		detector.onTouch(ACTION_MOVE, 1, 20);
		detector.onTouch(ThreeFingerTapDetector.ACTION_POINTER_DOWN, 2, 40);
		detector.onTouch(ACTION_MOVE, 2, 60);

		assertThat(detector.onTouch(ThreeFingerTapDetector.ACTION_POINTER_DOWN, 3, 80))
				.isTrue();
	}

	@Test
	void moveEventsNeverFire() {
		ThreeFingerTapDetector detector = new ThreeFingerTapDetector();

		detector.onTouch(ThreeFingerTapDetector.ACTION_DOWN, 1, 0);

		assertThat(detector.onTouch(ACTION_MOVE, 1, 10)).isFalse();
	}
}
