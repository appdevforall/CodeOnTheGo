package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * forActivity's defense-in-depth contract: with no payload live (the JVM-test reality - ensureBaseline never runs off-device), the fallback loader must come back unchanged, never null - a proxy activity's getClassLoader() must always return a usable loader.
 */
class QuickBuildClassLoadersForActivityTest {

	@Test
	void returnsTheFallbackWhenNoPayloadIsLive() {
		ClassLoader fallback = new ClassLoader() {};

		assertThat(QuickBuildClassLoaders.forActivity(fallback)).isSameInstanceAs(fallback);
	}
}
