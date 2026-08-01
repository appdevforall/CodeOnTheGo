package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * addAssetPath's failure contract: ANY reflective failure must surface as an IOException naming the path, so the deploy path can roll the resource payload back instead of silently dropping it (never-stale invariant). On the JVM the hidden AssetManager.addAssetPath cannot be invoked (the SDK stub does not carry it), which IS a representative reflective failure - the success path only exists on a real API 28/29 device.
 */
class LegacyResourceSwapAddAssetPathTest {

	@Test
	void aReflectiveFailureIsWrappedInAnIOExceptionNamingThePath() {
		IOException error = assertThrows(IOException.class,
				() -> LegacyResourceSwap.addAssetPath(null, "/data/x/gen-3.zip"));

		assertThat(error).hasMessageThat().contains("/data/x/gen-3.zip");
		assertThat(error.getCause()).isNotNull();
	}
}
