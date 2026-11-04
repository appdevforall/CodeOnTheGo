package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BuildStatusTest {

	@Test
	void buildingWithAMissingOrUnparseableGenerationFallsBackToUnknown() {
		assertThat(BuildStatus.parse("{\"kind\": \"building\"}").runningGeneration).isEqualTo(-1L);
		assertThat(BuildStatus.parse("{\"kind\": \"building\", \"runningGeneration\": \"nope\"}").runningGeneration).isEqualTo(-1L);
	}

	@Test
	void malformedJsonThrows() {
		assertThrows(IllegalArgumentException.class, () -> BuildStatus.parse("not json"));
		assertThrows(IllegalArgumentException.class, () -> BuildStatus.parse(null));
	}

	@Test
	void parsesBuildFailed() {
		BuildStatus status = BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"message\": \"Unresolved reference: foo\","
						+ " \"moreErrors\": \"2\"}");
		assertThat(status.kind).isEqualTo(BuildStatus.KIND_BUILD_FAILED);
		assertThat(status.message).isEqualTo("Unresolved reference: foo");
		assertThat(status.moreErrors).isEqualTo(2);
	}

	@Test
	void parsesBuildFailedWithMissingMessageFields() {
		BuildStatus status = BuildStatus.parse("{\"kind\": \"build_failed\"}");
		assertThat(status.kind).isEqualTo(BuildStatus.KIND_BUILD_FAILED);
		assertThat(status.message).isNull();
		assertThat(status.moreErrors).isEqualTo(0);
	}

	@Test
	void parsesBuilding() {
		BuildStatus status = BuildStatus.parse(
				"{\"kind\": \"building\", \"runningGeneration\": \"5\"}");
		assertThat(status.kind).isEqualTo(BuildStatus.KIND_BUILDING);
		assertThat(status.runningGeneration).isEqualTo(5L);
	}

	@Test
	void parsesBuildOk() {
		BuildStatus status = BuildStatus.parse("{\"kind\": \"build_ok\"}");
		assertThat(status.kind).isEqualTo(BuildStatus.KIND_BUILD_OK);
	}

	@Test
	void parsesReinstallPendingAsKindOnly() {
		BuildStatus status = BuildStatus.parse("{\"kind\": \"reinstall_pending\"}");
		assertThat(status.kind).isEqualTo(BuildStatus.KIND_REINSTALL_PENDING);
		assertThat(status.message).isNull();
		assertThat(status.moreErrors).isEqualTo(0);
	}

	@Test
	void positionFieldsFromAnOlderCoGoAreIgnored() {
		// A CoGo predating the position-free build-status still sends file/line/column; the
		// runtime has no use for them and must parse the rest of the message unchanged.
		BuildStatus status = BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"file\": \"/project/Foo.kt\", \"line\": \"12\","
						+ " \"column\": \"5\", \"message\": \"boom\", \"moreErrors\": \"1\"}");
		assertThat(status.kind).isEqualTo(BuildStatus.KIND_BUILD_FAILED);
		assertThat(status.message).isEqualTo("boom");
		assertThat(status.moreErrors).isEqualTo(1);
		assertThat(OverlayState.buildFailed(status).text())
				.isEqualTo("Build failed - app is running the last working version\nboom (+1 more)\n"
						+ "For more info, see Build Output in Code on the Go.");
	}

	@Test
	void unknownFieldsAreIgnored() {
		BuildStatus status = BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"message\": \"x\", \"futureField\": {\"y\": 1}}");
		assertThat(status.message).isEqualTo("x");
	}

	@Test
	void unknownKindParsesToNull() {
		// The versioning contract: a newer CoGo may send kinds this runtime predates.
		assertThat(BuildStatus.parse("{\"kind\": \"build_started\"}")).isNull();
		assertThat(BuildStatus.parse("{}")).isNull();
	}

	@Test
	void unparseableNumbersFallBack() {
		assertThat(BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"moreErrors\": \"three\"}").moreErrors).isEqualTo(0);
		// A negative extra-error count would render as nonsense; clamped to zero.
		assertThat(BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"moreErrors\": \"-3\"}").moreErrors).isEqualTo(0);
	}
}
