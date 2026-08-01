package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Banner-text edges: a build_failed status can arrive with any subset of file/line/message (the wire schema makes every field optional), and the rendered text must degrade cleanly instead of printing "null" or dangling separators.
 */
class OverlayStateTextEdgeTest {

	private static BuildStatus failed(String json) {
		return BuildStatus.parse(json);
	}

	@Test
	void buildFailedWithDetailButNoFileOmitsTheLocation() {
		OverlayState state = OverlayState
				.buildFailed(failed("{\"kind\":\"build_failed\",\"message\":\"boom\"}"));

		assertThat(state.text()).isEqualTo(
				"Build failed - app is running the last working version\nboom");
	}

	@Test
	void buildFailedWithFileAndLineButNoMessageHasNoTrailingSeparator() {
		OverlayState state = OverlayState.buildFailed(
				failed("{\"kind\":\"build_failed\",\"file\":\"/p/Foo.kt\",\"line\":\"7\"}"));

		assertThat(state.text()).isEqualTo(
				"Build failed - app is running the last working version\nFoo.kt:7"
						+ "\nTap to open in Code on the Go");
	}

	@Test
	void buildFailedWithFileButNoMessageShowsTheBareLocation() {
		// No '/' in the path and no line: the location degrades to just the file name,
		// with no dangling ':' separator; the file still makes the banner jumpable.
		OverlayState state = OverlayState
				.buildFailed(failed("{\"kind\":\"build_failed\",\"file\":\"Foo.kt\"}"));

		assertThat(state.text()).isEqualTo(
				"Build failed - app is running the last working version\nFoo.kt"
						+ "\nTap to open in Code on the Go");
	}

	@Test
	void buildFailedWithNoLocationAndNoDetailRendersOnlyTheHeadline() {
		OverlayState state = OverlayState.buildFailed(failed("{\"kind\":\"build_failed\"}"));

		assertThat(state.text())
				.isEqualTo("Build failed - app is running the last working version");
		assertThat(state.canJumpToEditor()).isFalse();
	}

	@Test
	void crashedWithoutDetailRendersOnlyTheHeadline() {
		OverlayState state = OverlayState.crashed(null);

		assertThat(state.text())
				.isEqualTo("New code crashed - app is running the last working version");
	}
}
