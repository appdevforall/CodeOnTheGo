package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

class QuickBuildClassLoadersTest {

	private final ClassLoader fallback = getClass().getClassLoader();

	@Test
	void picksThePayloadLoaderWhenOneIsLive() {
		ClassLoader payload = new ClassLoader(null) {};
		// The payload loader wins even though fallback also "works" - a stale-code lie
		// (Fragment/custom-view resolution silently missing every payload-only class)
		// is exactly the ADFA-4128 Bug 9 regression this guards.
		assertThat(QuickBuildClassLoaders.choose(payload, fallback)).isSameInstanceAs(payload);
	}

	@Test
	void fallsBackWhenNoPayloadIsLive() {
		// Inert runtime (no baseline loaded, or this Activity type reached before
		// ensureBaseline ran): must behave like a normal app, never NPE.
		assertThat(QuickBuildClassLoaders.choose(null, fallback)).isSameInstanceAs(fallback);
	}
}
