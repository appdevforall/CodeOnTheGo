package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

class ThreeFingerTapDetectorTest {

	private static final int DOWN = ThreeFingerTapDetector.ACTION_DOWN;
	private static final int UP = ThreeFingerTapDetector.ACTION_UP;
	private static final int CANCEL = ThreeFingerTapDetector.ACTION_CANCEL;
	private static final int POINTER_DOWN = ThreeFingerTapDetector.ACTION_POINTER_DOWN;

	private final ThreeFingerTapDetector detector = new ThreeFingerTapDetector();

	@Test
	void doesNotFireWhenTheThirdFingerIsLate() {
		// A slow pile-up (resting a hand on the screen) is not the gesture.
		assertThat(detector.onTouch(DOWN, 1, 1000)).isFalse();
		assertThat(detector.onTouch(POINTER_DOWN, 2, 1100)).isFalse();
		assertThat(detector.onTouch(POINTER_DOWN, 3,
				1001 + ThreeFingerTapDetector.BURST_WINDOW_MS)).isFalse();
	}

	@Test
	void firesAtMostOncePerGesture() {
		assertThat(detector.onTouch(DOWN, 1, 1000)).isFalse();
		assertThat(detector.onTouch(POINTER_DOWN, 2, 1010)).isFalse();
		assertThat(detector.onTouch(POINTER_DOWN, 3, 1020)).isTrue();
		// A 4th finger, or lifting back to 3, must not re-fire mid-gesture.
		assertThat(detector.onTouch(POINTER_DOWN, 4, 1030)).isFalse();
		assertThat(detector.onTouch(POINTER_DOWN, 3, 1040)).isFalse();
	}

	@Test
	void firesExactlyAtTheWindowBoundary() {
		assertThat(detector.onTouch(DOWN, 1, 1000)).isFalse();
		assertThat(detector.onTouch(POINTER_DOWN, 3,
				1000 + ThreeFingerTapDetector.BURST_WINDOW_MS)).isTrue();
	}

	@Test
	void firesWhenThreeFingersLandWithinTheBurstWindow() {
		assertThat(detector.onTouch(DOWN, 1, 1000)).isFalse();
		assertThat(detector.onTouch(POINTER_DOWN, 2, 1050)).isFalse();
		assertThat(detector.onTouch(POINTER_DOWN, 3, 1100)).isTrue();
	}

	@Test
	void ignoresPointerDownWithoutAnInitialDown() {
		// Defensive: a stream that starts mid-gesture (no ACTION_DOWN seen) never fires.
		assertThat(detector.onTouch(POINTER_DOWN, 3, 1000)).isFalse();
	}

	@Test
	void neverFiresForOneOrTwoFingers() {
		assertThat(detector.onTouch(DOWN, 1, 1000)).isFalse();
		assertThat(detector.onTouch(UP, 1, 1080)).isFalse();

		assertThat(detector.onTouch(DOWN, 1, 2000)).isFalse();
		assertThat(detector.onTouch(POINTER_DOWN, 2, 2050)).isFalse();
		assertThat(detector.onTouch(UP, 1, 2200)).isFalse();
	}

	@Test
	void resetsAfterAllFingersLift() {
		detector.onTouch(DOWN, 1, 1000);
		assertThat(detector.onTouch(POINTER_DOWN, 3, 1020)).isTrue();
		detector.onTouch(UP, 1, 1100);

		// A fresh gesture fires again.
		detector.onTouch(DOWN, 1, 5000);
		assertThat(detector.onTouch(POINTER_DOWN, 3, 5020)).isTrue();
	}

	@Test
	void resetsOnCancel() {
		detector.onTouch(DOWN, 1, 1000);
		detector.onTouch(CANCEL, 1, 1010);
		// No ACTION_DOWN since the cancel: pointer events alone must not fire.
		assertThat(detector.onTouch(POINTER_DOWN, 3, 1020)).isFalse();
	}
}
