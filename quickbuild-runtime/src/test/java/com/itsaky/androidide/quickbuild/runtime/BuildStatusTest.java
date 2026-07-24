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
	void parsesBuildFailedWithFullLocation() {
		BuildStatus status = BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"file\": \"/project/app/src/main/java/Foo.kt\","
						+ " \"line\": \"12\", \"column\": \"5\","
						+ " \"message\": \"Unresolved reference: foo\", \"moreErrors\": \"2\"}");
		assertThat(status.kind).isEqualTo(BuildStatus.KIND_BUILD_FAILED);
		assertThat(status.file).isEqualTo("/project/app/src/main/java/Foo.kt");
		assertThat(status.line).isEqualTo(12);
		assertThat(status.column).isEqualTo(5);
		assertThat(status.message).isEqualTo("Unresolved reference: foo");
		assertThat(status.moreErrors).isEqualTo(2);
	}

	@Test
	void parsesBuildFailedWithMissingLocationFields() {
		BuildStatus status = BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"message\": \"something broke\"}");
		assertThat(status.kind).isEqualTo(BuildStatus.KIND_BUILD_FAILED);
		assertThat(status.file).isNull();
		assertThat(status.line).isEqualTo(-1);
		assertThat(status.column).isEqualTo(-1);
		assertThat(status.moreErrors).isEqualTo(0);
	}

	@Test
	void parsesBuildOk() {
		BuildStatus status = BuildStatus.parse("{\"kind\": \"build_ok\"}");
		assertThat(status.kind).isEqualTo(BuildStatus.KIND_BUILD_OK);
	}

	@Test
	void parsesBuilding() {
		BuildStatus status = BuildStatus.parse(
				"{\"kind\": \"building\", \"runningGeneration\": \"5\"}");
		assertThat(status.kind).isEqualTo(BuildStatus.KIND_BUILDING);
		assertThat(status.runningGeneration).isEqualTo(5L);
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
		BuildStatus status = BuildStatus.parse(
				"{\"kind\": \"build_failed\", \"line\": \"twelve\", \"moreErrors\": \"-3\"}");
		assertThat(status.line).isEqualTo(-1);
		// A negative extra-error count would render as nonsense; clamped to zero.
		assertThat(status.moreErrors).isEqualTo(0);
	}
}
