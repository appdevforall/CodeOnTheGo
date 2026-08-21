package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Covers the banner-text edges when a build_failed status arrives only partly filled in.
 *
 * The wire schema makes message and moreErrors both optional, so the rendered text must degrade cleanly on any subset instead of printing "null" or leaving dangling separators.
 */
class OverlayStateTextEdgeTest {

	private static BuildStatus failed(String json) {
		return BuildStatus.parse(json);
	}

	@Test
	void buildFailedWithADetailAppendsItUnderTheHeadline() {
		OverlayState state = OverlayState
				.buildFailed(failed("{\"kind\":\"build_failed\",\"message\":\"boom\"}"));

		assertThat(state.text()).isEqualTo(
				"Build failed - app is running the last working version\nboom");
	}

	@Test
	void buildFailedWithMoreErrorsAppendsTheCount() {
		OverlayState state = OverlayState.buildFailed(
				failed("{\"kind\":\"build_failed\",\"message\":\"boom\",\"moreErrors\":\"3\"}"));

		assertThat(state.text()).isEqualTo(
				"Build failed - app is running the last working version\nboom (+3 more)");
	}

	@Test
	void buildFailedWithNoDetailRendersOnlyTheHeadline() {
		// Nothing to name, so no dangling separator and no orphan "(+N more)" either.
		OverlayState state = OverlayState
				.buildFailed(failed("{\"kind\":\"build_failed\",\"moreErrors\":\"3\"}"));

		assertThat(state.text())
				.isEqualTo("Build failed - app is running the last working version");
	}

	@Test
	void crashedWithoutDetailRendersOnlyTheHeadline() {
		OverlayState state = OverlayState.crashed(null);

		assertThat(state.text())
				.isEqualTo("New code crashed - app is running the last working version");
	}
}
