package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

class GenerationsTest {

	@Test
	void aBackgroundedApplyClearsThePendingSlotItAlreadyAcked() {
		// The regression: deploy 9 lands foreground and is left pending, the user backgrounds
		// before its first resumed frame, deploy 10 applies and acks in the background. The
		// backgrounded apply must still assign the slot - skipping it left stale 9 behind, so
		// the crash guard blamed 9 for gen 10's crashes and 10 escaped quarantine.
		assertThat(Generations.pendingAfterApply(false, 10)).isEqualTo(-1);
	}

	// The stamped-baseline boot gate is pinned in PersistedSelectionTest, against the real
	// PayloadStore seam - re-numbering accepts() cases here could not fail on that caller.

	@Test
	void acceptsStrictlyNewerGeneration() {
		assertThat(Generations.accepts(0, 1)).isTrue();
		assertThat(Generations.accepts(41, 42)).isTrue();
		assertThat(Generations.accepts(41, 100)).isTrue();
	}

	@Test
	void aFailureAfterItsOwnSwapCommittedLeavesAMixedState() {
		// The common ordering: applyTable posts and returns, applyAssets throws on the binder
		// thread, and by then the table swap has committed. The dex rolls back, the table
		// cannot, so the banner must not claim the last working version.
		assertThat(Generations.leavesMixedState(7, 7)).isTrue();
	}

	@Test
	void aFailureBeforeAnySwapCommittedIsNotMixed() {
		// The swap was refused or never posted: the screen resolves the previous table under
		// the previous dex, which is the last working version the banner names.
		assertThat(Generations.leavesMixedState(-1, 7)).isFalse();
		assertThat(Generations.leavesMixedState(6, 7)).isFalse();
	}

	@Test
	void aFailureSupersededByANewerLiveGenerationStaysSilent() {
		// Gen 6's posted recreate throws after gen 7 already applied: gen 7 owns the store,
		// the pending ack and the screen, so gen 6's failure must touch and say nothing.
		assertThat(Generations.onReloadFailure(7, 6))
				.isEqualTo(Generations.FailureAction.LEAVE_ALONE);
	}

	@Test
	void aFailureTheStoreNeverAdoptedStillReports() {
		// An oversize payload, a persist failure, a restart deploy missing its dex: the store
		// still runs the previous generation, so there is nothing to roll back or quarantine -
		// but the report and banner must fire, or the failure's only trace is the host's
		// deploy timeout and the developer sees nothing on device.
		assertThat(Generations.onReloadFailure(5, 6))
				.isEqualTo(Generations.FailureAction.REPORT_ONLY);
		assertThat(Generations.onReloadFailure(0, 1))
				.isEqualTo(Generations.FailureAction.REPORT_ONLY);
	}

	@Test
	void aFailureWhileTheFailedGenerationOwnsTheStoreRollsBackAndReports() {
		assertThat(Generations.onReloadFailure(6, 6))
				.isEqualTo(Generations.FailureAction.ROLLBACK_AND_REPORT);
	}

	@Test
	void aForegroundApplyLeavesItsGenerationPendingItsFirstFrame() {
		assertThat(Generations.pendingAfterApply(true, 10)).isEqualTo(10);
	}

	@Test
	void rejectsEqualGeneration() {
		// A replayed deploy of the running generation must be dropped, not re-rendered.
		assertThat(Generations.accepts(7, 7)).isFalse();
		assertThat(Generations.accepts(0, 0)).isFalse();
	}

	@Test
	void rejectsOlderGeneration() {
		assertThat(Generations.accepts(7, 6)).isFalse();
		assertThat(Generations.accepts(7, 0)).isFalse();
		assertThat(Generations.accepts(0, -1)).isFalse();
	}

	@Test
	void rollbackAppliesWhileTheFailedGenerationIsStillTheLiveOne() {
		assertThat(Generations.rollbackApplies(6, 6)).isTrue();
		assertThat(Generations.rollbackApplies(0, 0)).isTrue();
	}

	@Test
	void rollbackDoesNotApplyOnceANewerPayloadHasLanded() {
		// The case that motivated the rule: gen 6's recreate is posted to the main thread,
		// gen 7 lands on a binder thread, then the posted recreate throws. Restoring gen 6's
		// pre-apply snapshot would take the store back to gen 5 and undo gen 7.
		assertThat(Generations.rollbackApplies(7, 6)).isFalse();
		assertThat(Generations.rollbackApplies(100, 41)).isFalse();
	}

	@Test
	void rollbackDoesNotApplyToAGenerationTheStoreNeverReached() {
		// Defensive rather than reachable, but the rule is an equality and should say so:
		// a failure naming a generation ahead of the store owns nothing either.
		assertThat(Generations.rollbackApplies(6, 7)).isFalse();
	}
}
