package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Which generation the crash guard blames, now that a restart deploy's crash lands in a different process from the deploy.
 *
 * What each test would catch: blaming only the pending reload - the rule before this - makes every crash on a restart-booted generation invisible, which measured on an A56 as an app that crash-looped on the bad generation with no way out; blaming a generation that already reached the screen would poison the fallback the app just landed on; and blaming one a later deploy has superseded would write a marker for a generation nothing is running.
 */
class BootProbationTest {

	/** Stands for "no reload is awaiting its first frame", the state a fresh process boots in. */
	private static final long NO_PENDING_RELOAD = -1;

	@Test
	void aBootedGenerationSupersededByALaterDeployIsNotBlamed() {
		// A deploy landed on top of the booted generation without a resumed activity to hang a
		// frame callback on, so nothing is pending - but 9 is no longer what is running, and a
		// marker naming it would refuse a generation the app is not booting anyway.
		BootProbation probation = new BootProbation();
		probation.bootedFromStore(9);

		assertThat(probation.generationToBlame(NO_PENDING_RELOAD, 10)).isEqualTo(NO_PENDING_RELOAD);
	}

	@Test
	void aBootedGenerationThatReachedTheScreenIsNotBlamedForALaterCrash() {
		// It got an activity up, so a fresh process booting it does not repeat whatever failed
		// afterwards. This is also what stops a fallback boot from quarantining the very
		// generation it fell back to.
		BootProbation probation = new BootProbation();
		probation.bootedFromStore(9);

		probation.proved(9);

		assertThat(probation.generationToBlame(NO_PENDING_RELOAD, 9)).isEqualTo(NO_PENDING_RELOAD);
	}

	@Test
	void aConfirmationForASupersededGenerationLeavesTheProbationStanding() {
		// A late mark-good for 8 says nothing about whether 9 can reach the screen.
		BootProbation probation = new BootProbation();
		probation.bootedFromStore(9);

		probation.proved(8);

		assertThat(probation.generationToBlame(NO_PENDING_RELOAD, 9)).isEqualTo(9);
	}

	@Test
	void aCrashOnTheGenerationThisProcessBootedIsBlamedOnIt() {
		// The defect this class exists for. A restart deploy persists and exits, so the process
		// that runs its work has no reload pending and the guard used to see nothing at all -
		// leaving the app to boot the same crashing generation on every launch, forever.
		BootProbation probation = new BootProbation();
		probation.bootedFromStore(9);

		assertThat(probation.generationToBlame(NO_PENDING_RELOAD, 9)).isEqualTo(9);
	}

	@Test
	void anUnstampedBaselineIsNotAGenerationWorthRefusing() {
		// An older host plugin stamps no generation, so the baseline boots as 0 and the store
		// reports it as the adopted one; generation 0 is the APK's own code either way.
		BootProbation probation = new BootProbation();
		probation.bootedFromStore(0);

		assertThat(probation.generationToBlame(NO_PENDING_RELOAD, 0)).isEqualTo(NO_PENDING_RELOAD);
	}

	@Test
	void aPendingReloadOutranksTheGenerationThisProcessBooted() {
		// Both are live claims on the screen; the hot swap is the newer one, and it is the one
		// whose classes the activity that just died was built from.
		BootProbation probation = new BootProbation();
		probation.bootedFromStore(9);

		assertThat(probation.generationToBlame(11, 11)).isEqualTo(11);
	}

	@Test
	void aProcessThatBootedTheInstalledCodeBlamesNothing() {
		// The baked baseline is the floor a quarantine falls back to. Refusing it would leave
		// the app nothing at all to boot.
		BootProbation probation = new BootProbation();
		probation.bootedFromStore(-1);

		assertThat(probation.generationToBlame(NO_PENDING_RELOAD, 0)).isEqualTo(NO_PENDING_RELOAD);
	}

	@Test
	void aProcessWithNothingAdoptedAndNothingPendingBlamesNothing() {
		// The steady state: the app has been up for an hour and the user's own code throws.
		// Quarantining a generation over that would cost them working code.
		BootProbation probation = new BootProbation();

		assertThat(probation.generationToBlame(NO_PENDING_RELOAD, 12)).isEqualTo(NO_PENDING_RELOAD);
	}

	@Test
	void aSecondBootFromTheStoreReplacesTheGenerationOnProbation() {
		// One runtime per process, so this is defensive rather than a path - but a probation
		// that accumulated would blame a generation two boots stale.
		BootProbation probation = new BootProbation();
		probation.bootedFromStore(9);

		probation.bootedFromStore(8);

		assertThat(probation.generationToBlame(NO_PENDING_RELOAD, 9)).isEqualTo(NO_PENDING_RELOAD);
		assertThat(probation.generationToBlame(NO_PENDING_RELOAD, 8)).isEqualTo(8);
	}
}
