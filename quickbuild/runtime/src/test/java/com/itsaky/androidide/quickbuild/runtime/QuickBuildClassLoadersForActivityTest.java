package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins forActivity's defense-in-depth contract: it hands back a usable loader, never null.
 *
 * With no payload live - the JVM-test reality, since ensureBaseline never runs off-device - the
 * fallback loader must come back unchanged, because a proxy activity's getClassLoader() has to
 * return something usable in every state.
 */
class QuickBuildClassLoadersForActivityTest {

	@Test
	void returnsTheFallbackWhenNoPayloadIsLive() {
		ClassLoader fallback = new ClassLoader() {};

		assertThat(QuickBuildClassLoaders.forActivity(fallback)).isSameInstanceAs(fallback);
	}
}
