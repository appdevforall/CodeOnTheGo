package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code QuickBuildRuntime.startFailReloadThread} to running its body on a new thread named qb-fail-reload.
 *
 * The failure body fsyncs the quarantine marker to disk ({@code PayloadPersistence.writeAtomic}), and two of its three entry points - a rejected resource swap and a recreate that throws - run on the main thread, so a body that ran inline would put a blocking disk sync on the frame path. This goes red if the helper is collapsed to an inline call.
 *
 * What it deliberately does NOT pin is the call site: {@code failReload} needs an Application, a main Looper and a bound client, so whether it still routes through this helper is checked on device, not here.
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
