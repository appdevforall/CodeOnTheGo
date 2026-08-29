package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Covers what the banner does with a status-bar inset read.
 *
 * The banner drew over the clock at font scale 2.0 because the read comes back null on the first render after the config-change recreate, and a null read was taken as a 0 inset. The decision is pulled out of the View code so it can be checked here; that the deferred re-read actually fires is a device check, not a JVM one.
 */
class StatusOverlayInsetActionTest {

	@Test
	void aDetachedBannerIsNotMovedEvenWhenTheInsetArrives() {
		assertThat(StatusOverlay.insetAction(Integer.valueOf(83), false))
				.isEqualTo(StatusOverlay.InsetAction.GIVE_UP);
	}

	@Test
	void aDetachedBannerStopsTheWaitEvenWithNoInsetYet() {
		// Otherwise the deferred re-read outlives the banner it was going to move.
		assertThat(StatusOverlay.insetAction(null, false))
				.isEqualTo(StatusOverlay.InsetAction.GIVE_UP);
	}

	@Test
	void anAvailableInsetIsApplied() {
		assertThat(StatusOverlay.insetAction(Integer.valueOf(83), true))
				.isEqualTo(StatusOverlay.InsetAction.APPLY);
	}

	@Test
	void anUnavailableInsetWaitsInsteadOfBeingTreatedAsZero() {
		// The regression: null means "not measured yet", not "no status bar".
		assertThat(StatusOverlay.insetAction(null, true)).isEqualTo(StatusOverlay.InsetAction.WAIT);
	}

	@Test
	void aZeroInsetIsStillARealReadAndIsApplied() {
		// A window with no status bar reports 0. That ends the wait, unlike null.
		assertThat(StatusOverlay.insetAction(Integer.valueOf(0), true))
				.isEqualTo(StatusOverlay.InsetAction.APPLY);
	}
}
