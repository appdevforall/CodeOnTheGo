package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DeployMetadataTest {

	@Test
	void ignoresUnknownFields() {
		// The host must be able to extend the schema without breaking installed apps - it
		// writes changedAssets and reason, which this class does not read.
		DeployMetadata meta = DeployMetadata.parse(
				"{\"entryActivity\": \"com.example.app.MainActivity\","
						+ " \"changedAssets\": [\"data/levels.json\", \"img/logo.png\"],"
						+ " \"reason\": \"mixed\", \"futureField\": {\"x\": 1}, \"count\": 3}");
		assertThat(meta.entryActivity).isEqualTo("com.example.app.MainActivity");
		assertThat(meta.restart).isFalse();
	}

	@Test
	void malformedJsonThrows() {
		assertThrows(IllegalArgumentException.class, () -> DeployMetadata.parse("not json"));
		assertThrows(IllegalArgumentException.class, () -> DeployMetadata.parse(null));
	}

	@Test
	void missingFieldsFallBackToSafeDefaults() {
		DeployMetadata meta = DeployMetadata.parse("{}");
		assertThat(meta.entryActivity).isNull();
		assertThat(meta.restart).isFalse();
	}

	@Test
	void parsesRestartFlag() {
		// The CoGo side marks restart deploys with the STRING "true" (MiniJson
		// strings-only convention); anything else must read as a plain hot-swap.
		assertThat(DeployMetadata.parse("{\"restart\": \"true\"}").restart).isTrue();
		assertThat(DeployMetadata.parse("{\"restart\": \"false\"}").restart).isFalse();
		assertThat(DeployMetadata.parse("{\"restart\": true}").restart).isFalse();
		assertThat(DeployMetadata.parse("{\"reason\": \"code\"}").restart).isFalse();
	}

	@Test
	void wrongFieldTypesFallBackToDefaults() {
		DeployMetadata meta = DeployMetadata.parse(
				"{\"entryActivity\": 42, \"restart\": [\"true\"]}");
		assertThat(meta.entryActivity).isNull();
		assertThat(meta.restart).isFalse();
	}
}
