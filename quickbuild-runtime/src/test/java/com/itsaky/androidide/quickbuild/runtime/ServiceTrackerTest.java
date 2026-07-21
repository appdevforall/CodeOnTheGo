package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the Object-typed census seams; the Service-typed entry points are one-line delegates verified on-device. */
class ServiceTrackerTest {

	@BeforeEach
	void resetCensus() {
		ServiceTracker.reset();
	}

	@Test
	void censusIsIdentityBased() {
		// Two equal-but-distinct instances are two live services; equals/hashCode
		// overrides in user code must not collapse the census.
		Object first = new String("com.example.SyncService");
		Object second = new String("com.example.SyncService");
		ServiceTracker.trackCreated(first);
		ServiceTracker.trackCreated(second);
		assertThat(ServiceTracker.liveCount()).isEqualTo(2);
		ServiceTracker.trackDestroyed(first);
		assertThat(ServiceTracker.liveCount()).isEqualTo(1);
	}

	@Test
	void countsCreateAndDestroy() {
		Object service = new Object();
		assertThat(ServiceTracker.hasLiveServices()).isFalse();
		ServiceTracker.trackCreated(service);
		assertThat(ServiceTracker.hasLiveServices()).isTrue();
		assertThat(ServiceTracker.liveCount()).isEqualTo(1);
		ServiceTracker.trackDestroyed(service);
		assertThat(ServiceTracker.hasLiveServices()).isFalse();
		assertThat(ServiceTracker.liveCount()).isEqualTo(0);
	}

	@Test
	void destroyOfUntrackedInstanceIsNoOp() {
		ServiceTracker.trackCreated(new Object());
		ServiceTracker.trackDestroyed(new Object());
		assertThat(ServiceTracker.liveCount()).isEqualTo(1);
	}

	@Test
	void duplicateCreateCountsOnce() {
		// A proxy whose onCreate somehow runs twice must not inflate the census.
		Object service = new Object();
		ServiceTracker.trackCreated(service);
		ServiceTracker.trackCreated(service);
		assertThat(ServiceTracker.liveCount()).isEqualTo(1);
		ServiceTracker.trackDestroyed(service);
		assertThat(ServiceTracker.liveCount()).isEqualTo(0);
	}

	@Test
	void nullIsIgnored() {
		ServiceTracker.trackCreated(null);
		ServiceTracker.trackDestroyed(null);
		assertThat(ServiceTracker.liveCount()).isEqualTo(0);
	}
}
