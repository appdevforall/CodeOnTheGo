package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Actions outside the detector's vocabulary (MOVE, POINTER_UP, ...) are pure pass-throughs: they must neither fire nor disturb an in-flight burst - fingers always move a little between landing, and a MOVE that reset the gesture would make the 3-finger tap nearly impossible to perform.
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
