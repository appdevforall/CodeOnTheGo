package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins forActivity's contract on both sides of the choice: the live payload loader wins, and with none live the fallback comes back unchanged.
 *
 * The payload side is what a proxy activity's getClassLoader() exists for - reporting the fallback while a payload is live means LayoutInflater and FragmentFactory resolve names against the base APK and silently miss every payload-only class. The fallback side is the defense-in-depth half: the loader must never be null.
 */
class QuickBuildClassLoadersForActivityTest {

	/** The store is process-wide, so each test takes it over and hands it back. */
	private PayloadStore.Payload previous;

	@Test
	void prefersTheLivePayloadLoaderOverTheFallback() {
		ClassLoader fallback = new ClassLoader() {};
		ClassLoader payload = new ClassLoader(null) {};
		PayloadStore.INSTANCE.restore(new PayloadStore.Payload(1L, payload));

		assertThat(QuickBuildClassLoaders.forActivity(fallback)).isSameInstanceAs(payload);
	}

	@AfterEach
	void restoreThePayloadStore() {
		PayloadStore.INSTANCE.restore(previous);
	}

	@Test
	void returnsTheFallbackWhenNoPayloadIsLive() {
		ClassLoader fallback = new ClassLoader() {};

		assertThat(QuickBuildClassLoaders.forActivity(fallback)).isSameInstanceAs(fallback);
	}

	@BeforeEach
	void takeOverThePayloadStore() {
		previous = PayloadStore.INSTANCE.snapshot();
		PayloadStore.INSTANCE.restore(null);
	}
}
