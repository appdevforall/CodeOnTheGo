package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DeployMetadataTest {

	@Test
	void ignoresUnknownFields() {
		// The host must be able to extend the schema without breaking installed apps.
		DeployMetadata meta = DeployMetadata.parse(
				"{\"reason\": \"code\", \"futureField\": {\"x\": 1}, \"count\": 3}");
		assertThat(meta.reason).isEqualTo("code");
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
		assertThat(meta.changedAssets).isEmpty();
		assertThat(meta.reason).isEqualTo(DeployMetadata.REASON_UNKNOWN);
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
	void parsesFullSchema() {
		DeployMetadata meta = DeployMetadata.parse(
				"{\"entryActivity\": \"com.example.app.MainActivity\","
						+ " \"changedAssets\": [\"data/levels.json\", \"img/logo.png\"],"
						+ " \"reason\": \"mixed\"}");
		assertThat(meta.entryActivity).isEqualTo("com.example.app.MainActivity");
		assertThat(meta.changedAssets)
				.containsExactly("data/levels.json", "img/logo.png")
				.inOrder();
		assertThat(meta.reason).isEqualTo("mixed");
	}

	@Test
	void wrongFieldTypesFallBackToDefaults() {
		DeployMetadata meta = DeployMetadata.parse(
				"{\"entryActivity\": 42, \"changedAssets\": \"not-a-list\", \"reason\": [\"x\"]}");
		assertThat(meta.entryActivity).isNull();
		assertThat(meta.changedAssets).isEmpty();
		assertThat(meta.reason).isEqualTo(DeployMetadata.REASON_UNKNOWN);
	}
}
