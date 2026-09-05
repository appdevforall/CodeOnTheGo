package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The restart path's wait for the framework to be told the app's state before the process dies.
 *
 * What each test would catch: ending the wait at the last stop - the shape this replaced, one callback later - kills the process while ActivityThread's report to the server is still sitting on the main looper, which measured on an A56 as a force-removed record and a collapsed task; asking for the drain before every activity has stopped drains the wrong queue; giving each phase its own timeout doubles how long a restart is delayed by an app that will not stop; and returning true on timeout would report a handoff that never happened, which is the failure mode the caller logs about.
 */
class RestartHandoffTest {

	/** Long enough that a slow machine cannot fail it, short enough that a hang is obvious. */
	private static final long GENEROUS_TIMEOUT_MILLIS = 5000;

	/** A budget for the two-phase test, big enough that its two halves are separable on a loaded machine. */
	private static final long SHARED_BUDGET_MILLIS = 300;

	/** Long enough to be measurable, short enough to keep the suite fast. */
	private static final long SHORT_TIMEOUT_MILLIS = 60;

	/** Most of {@link #SHARED_BUDGET_MILLIS}, so a per-phase bound would visibly overrun it. */
	private static final long SLOW_STOP_MILLIS = 250;

	/** A drain request that never produces a drain, standing in for a main looper that never idles. */
	private static Runnable neverDrains() {
		return new Runnable() {

			@Override
			public void run() {}
		};
	}

	@Test
	void aDrainFromAnEarlierHandoffDoesNotAnswerThisOne() {
		// Nothing arms the drain except arm(), so a stale one would let a restart kill the
		// process the instant the last activity stopped - one message too early, which is the
		// whole defect.
		RestartHandoff handoff = new RestartHandoff();
		handoff.onDrained();
		handoff.arm();

		assertThat(handoff.awaitHandoff(SHORT_TIMEOUT_MILLIS, neverDrains())).isFalse();
	}

	@Test
	void aDrainThatLandsInsideTheRequestStillCounts() {
		// The real ordering has the drain arrive on the main thread, but it can land before the
		// waiter gets back to wait() and must still be seen.
		final RestartHandoff handoff = new RestartHandoff();
		handoff.arm();

		boolean handedOff = handoff.awaitHandoff(GENEROUS_TIMEOUT_MILLIS, new Runnable() {

			@Override
			public void run() {
				handoff.onDrained();
			}
		});

		assertThat(handedOff).isTrue();
	}

	@Test
	void anAppAlreadyOffScreenNeedsNoBackgrounding() {
		// The normal loop: the user saves by typing in CoGo, so every activity stopped long ago
		// and the framework already holds what the relaunch needs. Measured clean on an A56, 0
		// of 5 backgrounded saves force-removed, and this is the check that keeps it that way.
		RestartHandoff handoff = new RestartHandoff();

		assertThat(handoff.anyActivityStarted()).isFalse();

		handoff.onActivityStarted();
		assertThat(handoff.anyActivityStarted()).isTrue();

		handoff.onActivityStopped();
		assertThat(handoff.anyActivityStarted()).isFalse();
	}

	@Test
	void anAppThatNeverStopsTimesOutWithoutAskingForADrain() {
		RestartHandoff handoff = new RestartHandoff();
		handoff.arm();
		handoff.onActivityStarted();
		final AtomicInteger drainRequests = new AtomicInteger();

		long startedAt = System.nanoTime();
		boolean handedOff = handoff.awaitHandoff(SHORT_TIMEOUT_MILLIS, new Runnable() {

			@Override
			public void run() {
				drainRequests.incrementAndGet();
			}
		});
		long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

		assertThat(handedOff).isFalse();
		assertThat(elapsedMillis).isAtLeast(SHORT_TIMEOUT_MILLIS - 5);
		// Nothing has been queued behind a stop that never happened, so there is nothing to
		// drain and asking would only wait on an unrelated idle.
		assertThat(drainRequests.get()).isEqualTo(0);
	}

	@Test
	void anInterruptEndsTheWaitAndKeepsTheFlagSet() throws Exception {
		// The caller still owes a kill, so an interrupt must end the wait rather than propagate -
		// but swallowing it outright would hide it from anything else on the thread.
		final RestartHandoff handoff = new RestartHandoff();
		handoff.arm();
		handoff.onActivityStarted();
		final AtomicBoolean handedOff = new AtomicBoolean(true);
		final AtomicBoolean interruptFlagKept = new AtomicBoolean();
		final CountDownLatch waiting = new CountDownLatch(1);
		Thread waiter = new Thread(() -> {
			waiting.countDown();
			handedOff.set(handoff.awaitHandoff(GENEROUS_TIMEOUT_MILLIS, neverDrains()));
			interruptFlagKept.set(Thread.currentThread().isInterrupted());
		});
		waiter.start();
		assertThat(waiting.await(GENEROUS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
		// The waiter has to reach wait() before the interrupt lands, or it never blocks.
		Thread.sleep(50);

		waiter.interrupt();
		waiter.join(GENEROUS_TIMEOUT_MILLIS);

		assertThat(waiter.isAlive()).isFalse();
		assertThat(handedOff.get()).isFalse();
		assertThat(interruptFlagKept.get()).isTrue();
	}

	@Test
	void anUnbalancedStopCannotDriveTheCountBelowZero() {
		// A count stuck below zero would swallow the next real start, and the restart after that
		// would kill an app that is still on screen.
		RestartHandoff handoff = new RestartHandoff();

		handoff.onActivityStopped();
		handoff.onActivityStarted();

		assertThat(handoff.anyActivityStarted()).isTrue();
	}

	@Test
	void aWaitWithNoTimeLeftReportsNoHandoffImmediately() {
		RestartHandoff handoff = new RestartHandoff();
		handoff.arm();

		assertThat(handoff.awaitHandoff(0, neverDrains())).isFalse();
		assertThat(handoff.awaitHandoff(-1, neverDrains())).isFalse();
	}

	@Test
	void theDrainIsAskedForOnlyOnceEveryActivityHasStopped() throws Exception {
		// Two activities in the task; the drain has to wait for both, because ActivityThread
		// posts a report per activity and the last one is the one that can still be in flight.
		final RestartHandoff handoff = new RestartHandoff();
		handoff.arm();
		handoff.onActivityStarted();
		handoff.onActivityStarted();
		final AtomicInteger drainRequests = new AtomicInteger();
		final AtomicBoolean handedOff = new AtomicBoolean();
		final CountDownLatch waiting = new CountDownLatch(1);
		Thread waiter = new Thread(() -> {
			waiting.countDown();
			handedOff.set(handoff.awaitHandoff(GENEROUS_TIMEOUT_MILLIS, new Runnable() {

				@Override
				public void run() {
					drainRequests.incrementAndGet();
					handoff.onDrained();
				}
			}));
		});
		waiter.start();
		assertThat(waiting.await(GENEROUS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
		Thread.sleep(50);

		handoff.onActivityStopped();
		Thread.sleep(50);
		assertThat(drainRequests.get()).isEqualTo(0);

		handoff.onActivityStopped();
		waiter.join(GENEROUS_TIMEOUT_MILLIS);

		assertThat(waiter.isAlive()).isFalse();
		assertThat(drainRequests.get()).isEqualTo(1);
		assertThat(handedOff.get()).isTrue();
	}

	@Test
	void theHandoffIsNotCompleteUntilTheDrainLands() {
		// The defect this commit exists for. Every activity has stopped, so the app has written
		// its bundle - and the server has not been told, because ActivityThread's report to it
		// is still queued on the main looper. Killing here is what force-removed the record on 8
		// of 8 foreground saves measured on an A56.
		RestartHandoff handoff = new RestartHandoff();
		handoff.arm();

		long startedAt = System.nanoTime();
		boolean handedOff = handoff.awaitHandoff(SHORT_TIMEOUT_MILLIS, neverDrains());
		long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

		assertThat(handedOff).isFalse();
		assertThat(elapsedMillis).isAtLeast(SHORT_TIMEOUT_MILLIS - 5);
	}

	@Test
	void theTimeoutBoundsBothPhasesTogetherRatherThanEach() throws Exception {
		// The bound is what keeps a restart under the host's disconnect wait. Two phases each
		// given the full timeout would spend twice as long on an app that will not stop, and the
		// justification for the number would no longer hold.
		final RestartHandoff handoff = new RestartHandoff();
		handoff.arm();
		handoff.onActivityStarted();
		Thread stopper = new Thread(() -> {
			try {
				Thread.sleep(SLOW_STOP_MILLIS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return;
			}
			handoff.onActivityStopped();
		});
		stopper.start();

		long startedAt = System.nanoTime();
		boolean handedOff = handoff.awaitHandoff(SHARED_BUDGET_MILLIS, neverDrains());
		long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
		stopper.join(GENEROUS_TIMEOUT_MILLIS);

		assertThat(handedOff).isFalse();
		// Most of the budget went on the stop, so the drain can only have had what was left of
		// it. A per-phase bound would run to SLOW_STOP + SHARED_BUDGET instead.
		assertThat(elapsedMillis).isAtLeast(SHARED_BUDGET_MILLIS - 15);
		assertThat(elapsedMillis).isLessThan(SLOW_STOP_MILLIS + SHARED_BUDGET_MILLIS - 100);
	}

	@Test
	void theWaitEndsAsSoonAsTheDrainArrives() throws Exception {
		final RestartHandoff handoff = new RestartHandoff();
		handoff.arm();
		final AtomicBoolean handedOff = new AtomicBoolean();
		final CountDownLatch waiting = new CountDownLatch(1);
		Thread waiter = new Thread(() -> {
			waiting.countDown();
			handedOff.set(handoff.awaitHandoff(GENEROUS_TIMEOUT_MILLIS, neverDrains()));
		});
		waiter.start();
		assertThat(waiting.await(GENEROUS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();

		handoff.onDrained();
		waiter.join(GENEROUS_TIMEOUT_MILLIS);

		assertThat(waiter.isAlive()).isFalse();
		assertThat(handedOff.get()).isTrue();
	}
}
