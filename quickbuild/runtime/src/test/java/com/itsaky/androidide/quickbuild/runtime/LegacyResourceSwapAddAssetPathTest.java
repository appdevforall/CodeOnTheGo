package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Pins that any addAssetPath reflective failure becomes an IOException naming the path.
 *
 * The deploy path needs that to roll the resource payload back instead of silently dropping it (the never-stale invariant). On the JVM the hidden AssetManager.addAssetPath cannot be invoked at all - the SDK stub omits it - which is a representative reflective failure; the success path exists only on a real API 28/29 device.
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
