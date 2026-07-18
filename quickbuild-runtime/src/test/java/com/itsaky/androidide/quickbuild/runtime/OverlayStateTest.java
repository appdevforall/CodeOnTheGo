package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

class OverlayStateTest {

	@Test
	void buildFailedSaysTheAppRunsTheLastWorkingVersion() {
		OverlayState state = OverlayState.buildFailed(BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"file\": \"/p/src/Foo.kt\", \"line\": \"12\","
						+ " \"message\": \"Unresolved reference: foo\"}"));
		// The honesty line is the point of the overlay (never-stale, plan 1.4/A1).
		assertThat(state.text()).contains("running the last working version");
		assertThat(state.text()).contains("Foo.kt:12");
		assertThat(state.text()).contains("Unresolved reference: foo");
		assertThat(state.isError()).isTrue();
	}

	@Test
	void buildFailedShowsTheExtraErrorCount() {
		OverlayState state = OverlayState.buildFailed(BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"file\": \"/p/A.kt\", \"line\": \"3\","
						+ " \"message\": \"first\", \"moreErrors\": \"2\"}"));
		assertThat(state.text()).contains("(+2 more)");
	}

	@Test
	void buildFailedWithAFileOffersTapToJump() {
		OverlayState state = OverlayState.buildFailed(BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"file\": \"/p/src/Foo.kt\", \"line\": \"12\","
						+ " \"message\": \"boom\"}"));
		assertThat(state.canJumpToEditor()).isTrue();
		assertThat(state.file).isEqualTo("/p/src/Foo.kt");
		assertThat(state.line).isEqualTo(12);
		assertThat(state.text()).contains("Tap to open in Code on the Go");
	}

	@Test
	void buildFailedWithoutAFileDoesNotOfferTheJump() {
		OverlayState state = OverlayState.buildFailed(BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"message\": \"boom\"}"));
		assertThat(state.canJumpToEditor()).isFalse();
		assertThat(state.text()).doesNotContain("Tap to open");
		assertThat(state.text()).contains("boom");
	}

	@Test
	void crashedSaysTheAppRunsTheLastWorkingVersionAndCarriesTheSummary() {
		OverlayState state = OverlayState.crashed("java.lang.NullPointerException\n at Foo.bar");
		assertThat(state.text()).contains("running the last working version");
		assertThat(state.text()).contains("NullPointerException");
		assertThat(state.isError()).isTrue();
		assertThat(state.canJumpToEditor()).isFalse();
	}

	@Test
	void hiddenRendersNothing() {
		OverlayState state = OverlayState.hidden();
		assertThat(state.kind).isEqualTo(OverlayState.Kind.HIDDEN);
		assertThat(state.text()).isEmpty();
		assertThat(state.isError()).isFalse();
		assertThat(state.canJumpToEditor()).isFalse();
	}

	@Test
	void hintIsNeitherErrorNorJumpable() {
		OverlayState state = OverlayState.hint();
		assertThat(state.text()).contains("3 fingers");
		assertThat(state.isError()).isFalse();
		assertThat(state.canJumpToEditor()).isFalse();
	}
}
