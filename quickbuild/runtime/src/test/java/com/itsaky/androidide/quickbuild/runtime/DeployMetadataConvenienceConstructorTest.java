package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Pins the 3-arg no-restart shorthand: restart defaults to false and the full constructor's null-safety applies unchanged.
 */
class DeployMetadataConvenienceConstructorTest {

	@Test
	void threeArgConstructorAppliesTheNullDefaults() {
		DeployMetadata metadata = new DeployMetadata(null, null, null);

		assertThat(metadata.restart).isFalse();
		assertThat(metadata.entryActivity).isNull();
		assertThat(metadata.changedAssets).isEmpty();
		assertThat(metadata.reason).isEqualTo(DeployMetadata.REASON_UNKNOWN);
	}

	@Test
	void threeArgConstructorDefaultsRestartToFalse() {
		DeployMetadata metadata = new DeployMetadata("com.example.Main",
				Arrays.asList("data/a.json"), "assets");

		assertThat(metadata.restart).isFalse();
		assertThat(metadata.entryActivity).isEqualTo("com.example.Main");
		assertThat(metadata.changedAssets).containsExactly("data/a.json");
		assertThat(metadata.reason).isEqualTo("assets");
	}
}
