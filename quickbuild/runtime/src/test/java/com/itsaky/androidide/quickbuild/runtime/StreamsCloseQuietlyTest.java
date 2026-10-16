package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

/** closeQuietly's contract: null-safe, and a failing close never propagates. */
class StreamsCloseQuietlyTest {

	@Test
	void closesTheCloseable() {
		final boolean[] closed = {false};
		Streams.closeQuietly(() -> closed[0] = true);
		assertThat(closed[0]).isTrue();
	}

	@Test
	void nullIsANoOp() {
		assertDoesNotThrow(() -> Streams.closeQuietly(null));
	}

	@Test
	void swallowsACloseFailure() {
		assertDoesNotThrow(() -> Streams.closeQuietly(() -> {
			throw new java.io.IOException("close failed");
		}));
	}
}
