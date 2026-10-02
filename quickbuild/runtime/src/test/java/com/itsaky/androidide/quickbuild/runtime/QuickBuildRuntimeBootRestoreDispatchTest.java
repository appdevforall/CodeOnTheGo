package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code QuickBuildRuntime.startBootRestoreThread} to running its body on a new thread named qb-boot-restore.
 *
 * The restore body extracts the persisted asset merge and, on API 28/29, copies the relinked apk - both bounded only by the payload cap - and it is dispatched from inside the first activity's creation on the main thread. Run inline it is a launch stall or an ANR on every cold start that adopts a persisted generation with resources. This goes red if the helper is collapsed to an inline call.
 *
 * Like the fail-reload dispatch test, this does not pin the call site: {@code applyPendingBootResources} needs a Context and a store, so whether it still routes through this helper is checked on device.
 */
class QuickBuildRuntimeBootRestoreDispatchTest {

	@Test
	void bodyRunsOffTheCallersThread() throws Exception {
		final AtomicReference<Thread> ranOn = new AtomicReference<>();
		final CountDownLatch done = new CountDownLatch(1);
		Thread started = QuickBuildRuntime.startBootRestoreThread(new Runnable() {

			@Override
			public void run() {
				ranOn.set(Thread.currentThread());
				done.countDown();
			}
		});
		assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
		started.join(TimeUnit.SECONDS.toMillis(5));
		assertThat(ranOn.get()).isNotSameInstanceAs(Thread.currentThread());
		assertThat(ranOn.get()).isSameInstanceAs(started);
		assertThat(started.getName()).isEqualTo("qb-boot-restore");
	}
}
