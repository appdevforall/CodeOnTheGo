package com.itsaky.androidide.quickbuild.runtime;

/**
 * The wait a restart deploy makes between asking Android to background the app and killing the process.
 *
 * A process killed while the server still believes its top activity has no saved state gets that record force-removed ("app died, no saved state") - taking the task with it when it was the only entry, so the relaunch has nothing to resume and the user lands on the launcher screen with no Back to their work. Measured on an A56: force-removed on 8 of 8 restarts made with the app in front against 0 of 5 made with it backgrounded, and on a clean two-entry stack the task collapsed to one entry. Twice in six the relaunch then found nothing to start at all and the save cost 17 s instead of 2.
 *
 * The state reaches the server in two steps, and only the second is the one it reads. Every activity stops, which is when ActivityThread captures the state; then ActivityThread posts its {@code activityStopped} report - carrying that state - to the main looper, and the looper runs it. So this waits for both: {@link #onActivityStopped} for the first, and a drain of the main looper for the second, since a message queued behind the stop cannot run before the report the stop queued.
 *
 * Extracted from {@link ActivityTracker} and {@link QuickBuildRuntime} so the wait is JVM-testable: the halves either side of it are Activity lifecycle, {@code MessageQueue.IdleHandler} and {@code Process.killProcess}, none of which runs off device.
 *
 * The started count is kept for the process rather than armed per restart, because it is a census of what is on screen right now - unlike the capture flag this replaced, which said only that a capture had happened at some point and so answered this restart's wait with an hour-old backgrounding.
 */
final class RestartHandoff {

	/** True once the main looper has drained past the framework's report. Guarded by {@code this}. */
	private boolean drained;

	/** Activities of this process between onStart and onStop. Guarded by {@code this}. */
	private int startedActivities;

	/**
	 * Whether any activity of this process is started, which is the case a restart has to hand off for.
	 *
	 * @return true when at least one activity is between onStart and onStop; false is the normal loop, where the user saves by typing in CoGo and the framework already holds everything it needs
	 */
	synchronized boolean anyActivityStarted() {
		return startedActivities > 0;
	}

	/** Discards a drain from an earlier handoff, so only one requested from here on can end this wait. */
	synchronized void arm() {
		drained = false;
	}

	/**
	 * Waits for every activity to stop and then for the main looper to run what stopping queued.
	 *
	 * @param timeoutMillis
	 *            upper bound across BOTH phases, not per phase; the caller kills the process either way, so this bounds how long a restart is delayed by an app that will not stop
	 * @param requestDrain
	 *            invoked once, on the calling thread, the moment the last activity has stopped; the caller uses it to schedule the main-looper drain that {@link #onDrained} ends. Not invoked at all when the stop wait times out, since there would be nothing behind the report to drain
	 * @return true when every activity stopped and the drain landed inside the bound; false on timeout or interruption, which the caller reports rather than treating as a handoff
	 */
	boolean awaitHandoff(long timeoutMillis, Runnable requestDrain) {
		long deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
		if (!awaitAllStopped(deadlineNanos)) {
			return false;
		}
		// Deliberately outside the monitor: the drain it schedules calls back in.
		requestDrain.run();
		return awaitDrained(deadlineNanos);
	}

	/** Counts an activity into the set a restart waits to empty. */
	synchronized void onActivityStarted() {
		startedActivities++;
	}

	/**
	 * Counts an activity out of that set, releasing a waiting restart once it is empty.
	 *
	 * Balanced against {@link #onActivityStarted} by the framework, which stops an activity before destroying it; clamped at zero anyway, since a count stuck above it would make every later restart pay the full timeout.
	 */
	synchronized void onActivityStopped() {
		if (startedActivities > 0 && --startedActivities == 0) {
			notifyAll();
		}
	}

	/** Records that the main looper has run everything queued behind the last stop, ending any wait. */
	synchronized void onDrained() {
		drained = true;
		notifyAll();
	}

	/**
	 * @param deadlineNanos
	 *            when to give up, on the {@link System#nanoTime} clock
	 * @return true once no activity is started, including when none was to begin with
	 */
	private synchronized boolean awaitAllStopped(long deadlineNanos) {
		while (startedActivities > 0) {
			if (!waitUntil(deadlineNanos)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * @param deadlineNanos
	 *            when to give up, on the {@link System#nanoTime} clock
	 * @return true once a drain has landed, including one that landed before this call
	 */
	private synchronized boolean awaitDrained(long deadlineNanos) {
		while (!drained) {
			if (!waitUntil(deadlineNanos)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * @param deadlineNanos
	 *            when to give up, on the {@link System#nanoTime} clock
	 * @return false when the deadline has passed or the wait was interrupted; the interrupt is re-flagged rather than propagated, since the restart is still owed a kill
	 */
	private synchronized boolean waitUntil(long deadlineNanos) {
		long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
		if (remainingMillis <= 0) {
			return false;
		}
		try {
			wait(remainingMillis);
			return true;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
