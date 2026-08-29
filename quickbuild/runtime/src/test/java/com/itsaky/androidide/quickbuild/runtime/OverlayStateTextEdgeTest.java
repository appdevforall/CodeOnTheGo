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
	void crashedIsExactlyTheHeadlineAndThePointer() {
		// Pinned as an equality rather than a contains: the banner's height argument is
		// that these two strings are the whole of it, so anything appended has to fail
		// here rather than quietly cost lines the budget does not have.
		OverlayState state = OverlayState.crashed();

		assertThat(state.text())
				.isEqualTo("Live reload crashed. App is on the last working version.\n"
						+ "For more info, see Build Output in Code on the Go.");
	}
}
