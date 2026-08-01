package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The public Service-typed entry points promise "never throws": a null service (or the logging inside) must neither crash a user's service lifecycle nor corrupt the census. Exercised with null because android.app.Service is not constructible in a JVM test; the census logic itself is covered via the Object-typed seams in ServiceTrackerTest.
 */
class ServiceTrackerNullSafetyTest {

	@Test
	void onServiceCreatedWithNullNeverThrowsAndLeavesTheCensusUntouched() {
		assertDoesNotThrow(() -> ServiceTracker.onServiceCreated(null));

		assertThat(ServiceTracker.hasLiveServices()).isFalse();
		assertThat(ServiceTracker.liveCount()).isEqualTo(0);
	}

	@Test
	void onServiceDestroyedWithNullNeverThrowsAndKeepsLiveServices() {
		Object live = new Object();
		ServiceTracker.trackCreated(live);

		assertDoesNotThrow(() -> ServiceTracker.onServiceDestroyed(null));

		assertThat(ServiceTracker.liveCount()).isEqualTo(1);
	}

	@BeforeEach
	@AfterEach
	void resetCensus() {
		ServiceTracker.reset();
	}
}
