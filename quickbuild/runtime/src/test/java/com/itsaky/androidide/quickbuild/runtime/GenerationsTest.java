package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

class GenerationsTest {

	@Test
	void acceptsStrictlyNewerGeneration() {
		assertThat(Generations.accepts(0, 1)).isTrue();
		assertThat(Generations.accepts(41, 42)).isTrue();
		assertThat(Generations.accepts(41, 100)).isTrue();
	}

	// The stamped-baseline boot gate is pinned in PersistedSelectionTest, against the real
	// PayloadStore seam - re-numbering accepts() cases here could not fail on that caller.

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
