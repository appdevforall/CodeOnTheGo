package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the reload-failure dispatch off the caller's thread.
 *
 * The failure body fsyncs the quarantine marker to disk ({@code PayloadPersistence.writeAtomic}), and two of its three entry points - a rejected resource swap and a recreate that throws - run on the main thread. Collapsing the dispatch back to the caller's thread would put a blocking disk sync on the frame path; this test goes red if that happens.
 */
class QuickBuildRuntimeFailReloadDispatchTest {

	@Test
	void bodyRunsOffTheCallersThread() throws Exception {
		final AtomicReference<Thread> ranOn = new AtomicReference<>();
		final CountDownLatch done = new CountDownLatch(1);
		Thread started = QuickBuildRuntime.startFailReloadThread(new Runnable() {

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
		assertThat(started.getName()).isEqualTo("qb-fail-reload");
	}
}
