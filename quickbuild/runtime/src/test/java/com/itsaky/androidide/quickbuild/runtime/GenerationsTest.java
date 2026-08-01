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
}
