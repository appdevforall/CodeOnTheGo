package com.itsaky.androidide.quickbuild.runtime;

/**
 * Holds a hot swap's pending generation until the activity that took the screen has actually drawn it.
 *
 * A resume is not proof that a payload works. onResume runs before the first traversal, so a broken layout or a custom view that throws in measure, layout or draw dies after the resume and before any pixels exist. Clearing the pending generation at resume made that crash unattributable: {@link BootProbation#generationToBlame} saw nothing pending and nothing unproven, quarantined nothing, and the next launch adopted the same generation and died the same way, with no in-app escape.
 *
 * So the generation stays pending across the resume and is only released by {@link #drawn}, which the runtime calls once the frame that rendered it has completed. Everything a released generation owes - the ack to CoGo, the good-marking that ends its probation - hangs off that one release, so the moment the crash guard stops blaming a generation is the same moment there is drawn evidence it works.
 *
 * A generation that never draws is released by the runtime's own fallbacks instead: an activity with no live view tree, and the branch where the resumed activity is gone by the time the recreate runs, both complete without a frame. That is the deliberate looser case - waiting for a frame that will never arrive would strand the deploy unacked.
 *
 * One case is deliberately not released: a recreate that succeeds but never resumes, because the user backgrounded the app mid-relaunch and the task was then swiped away. No draw callback is ever installed, so the deploy ends only in CoGo's timeout, and until the next save {@link #pending} still names this generation, so an unrelated crash in the process would be reported against it. Releasing from onActivityDestroyed is not the fix, since recreate() itself destroys the armed activity on every normal reload; a destroy-based release would need to know a relaunch is still pending, which nothing here tracks yet.
 */
final class FirstFrameGate {

	/** Generation awaiting its first drawn frame, or -1 when nothing is pending. Guarded by {@code this}. */
	private long pendingGeneration = -1;

	/**
	 * Arms the gate for a payload that just applied.
	 *
	 * @param generation
	 *            the generation to hold until it draws, or -1 when the apply was already acked and nothing is pending - which must still be assigned rather than skipped, or an older generation left in the slot keeps taking the blame for this one's crashes
	 */
	synchronized void arm(long generation) {
		pendingGeneration = generation;
	}

	/** Releases the slot without acking, for a generation whose reload failed or was rolled back. */
	synchronized void disarm() {
		pendingGeneration = -1;
	}

	/**
	 * Records that a frame carrying the live generation finished drawing, releasing its ack.
	 *
	 * @param liveGeneration
	 *            the generation the store serves now; a pending generation the store has already moved past is stale and is not acked here
	 * @return the generation to ack, or -1 when nothing was pending for this live generation, including every frame after the first
	 */
	synchronized long drawn(long liveGeneration) {
		if (pendingGeneration < 0 || pendingGeneration != liveGeneration) {
			return -1;
		}
		long acked = pendingGeneration;
		pendingGeneration = -1;
		return acked;
	}

	/**
	 * The generation a crash right now should be blamed on.
	 *
	 * Deliberately unchanged by a resume: between the resume and the drawn frame this still names the generation that just took the screen, which is the whole point of the gate.
	 *
	 * @return the pending generation, or -1 when none is
	 */
	synchronized long pending() {
		return pendingGeneration;
	}
}
