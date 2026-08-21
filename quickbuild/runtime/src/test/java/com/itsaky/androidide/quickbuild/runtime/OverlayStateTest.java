package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

class OverlayStateTest {

	@Test
	void buildFailedNeverNamesAnErrorLocation() {
		// Locating an error is CoGo's job (Build Output); the overlay is a stale-app warning,
		// so no file path reaches the device even when CoGo knows one.
		OverlayState state = OverlayState.buildFailed(BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"file\": \"/p/src/Foo.kt\", \"line\": \"12\","
						+ " \"message\": \"boom\"}"));
		assertThat(state.text()).contains("boom");
		assertThat(state.text()).doesNotContain("Foo.kt");
		assertThat(state.text()).doesNotContain("12");
	}

	@Test
	void buildFailedSaysTheAppRunsTheLastWorkingVersion() {
		OverlayState state = OverlayState.buildFailed(BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"message\": \"Unresolved reference: foo\"}"));
		// The honesty line is the point of the overlay: never stale.
		assertThat(state.text()).contains("running the last working version");
		assertThat(state.text()).contains("Unresolved reference: foo");
		assertThat(state.isError()).isTrue();
	}

	@Test
	void buildFailedShowsTheExtraErrorCount() {
		OverlayState state = OverlayState.buildFailed(BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"message\": \"first\", \"moreErrors\": \"2\"}"));
		assertThat(state.text()).contains("(+2 more)");
	}

	@Test
	void buildingSaysWhichGenerationIsStillOnScreen() {
		OverlayState state = OverlayState.building(4L);
		assertThat(state.text()).contains("gen 4");
		assertThat(state.isBuilding()).isTrue();
		// Not an error - there is no failure yet.
		assertThat(state.isError()).isFalse();
	}

	@Test
	void buildingWithAnUnknownGenerationStillRendersHonestly() {
		OverlayState state = OverlayState.building(-1L);
		assertThat(state.text()).doesNotContain("gen -1");
		assertThat(state.text()).contains("one reload behind");
	}

	@Test
	void crashedSaysTheAppRunsTheLastWorkingVersionAndCarriesTheSummary() {
		OverlayState state = OverlayState.crashed("java.lang.NullPointerException\n at Foo.bar");
		assertThat(state.text()).contains("running the last working version");
		assertThat(state.text()).contains("NullPointerException");
		assertThat(state.isError()).isTrue();
	}

	@Test
	void hiddenRendersNothing() {
		OverlayState state = OverlayState.hidden();
		assertThat(state.kind).isEqualTo(OverlayState.Kind.HIDDEN);
		assertThat(state.text()).isEmpty();
		assertThat(state.isError()).isFalse();
	}

	@Test
	void onlyBuildingIsBuilding() {
		assertThat(OverlayState.hidden().isBuilding()).isFalse();
		assertThat(OverlayState.crashed("x").isBuilding()).isFalse();
	}

	@Test
	void reinstallPendingIsClearedBySuccessLikeAnyError() {
		// isError() is what makes a later build_ok take the banner down; without it the
		// banner would outlive the recovery it asks for.
		assertThat(OverlayState.reinstallPending().isError()).isTrue();
	}

	@Test
	void reinstallPendingSendsTheUserBackToCoGo() {
		// The user watching this app is the one person CoGo's own signals cannot reach;
		// this banner is the recovery instruction, plus the standard honesty line.
		OverlayState state = OverlayState.reinstallPending();
		assertThat(state.text()).contains("Code on the Go");
		assertThat(state.text()).contains("running the last working version");
	}
}
