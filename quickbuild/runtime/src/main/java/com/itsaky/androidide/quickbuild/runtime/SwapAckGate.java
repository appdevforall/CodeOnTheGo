package com.itsaky.androidide.quickbuild.runtime;

/**
 * Holds a backgrounded deploy's ack until every resource swap it posted has committed.
 *
 * A resource swap is queued to the main looper and lands after the deploy method has returned, so acking at apply time claims a reload that can still fail. When it did fail, the rollback, the quarantine and the crash report all ran after CoGo had already been told the generation reloaded, and CoGo's deploy resolves on the first report naming that generation: the build was recorded as a successful reload with a timing number while the session manager separately raised a reload crash, so one save produced both signals. The foreground branch does not have this problem - it acks from a drawn frame, which cannot precede the swap that the frame renders.
 *
 * So the ack is owed only once, and only when every posted swap has committed and none has failed. A deploy that posts no swap at all - a dex-only edit, the commonest one - still owes it immediately, which is what {@link #noSwapPosted} is for.
 */
final class SwapAckGate {

	/** Posted swaps not yet committed. Guarded by {@code this}. */
	private int outstanding;

	/** Whether the ack has been claimed or cancelled, so it can never be owed twice. Guarded by {@code this}. */
	private boolean settled;

	/**
	 * @param postedSwaps
	 *            how many resource swaps this deploy queued; 0 when it carries no resource or asset payload
	 */
	SwapAckGate(int postedSwaps) {
		this.outstanding = postedSwaps;
	}

	/**
	 * Records that one posted swap committed.
	 *
	 * @return true when this was the last one outstanding, so the caller now owes the ack
	 */
	synchronized boolean committed() {
		if (settled || outstanding <= 0) {
			return false;
		}
		outstanding--;
		if (outstanding > 0) {
			return false;
		}
		settled = true;
		return true;
	}

	/**
	 * Cancels the ack for good.
	 *
	 * A swap that failed is reported by the failure path instead, which rolls the store back and names the generation to CoGo. A second swap of the same deploy committing afterwards must not turn that into a success.
	 */
	synchronized void failed() {
		settled = true;
	}

	/**
	 * Asks whether this deploy queued nothing to wait for, settling the gate when it did not.
	 *
	 * Deliberately not {@link #committed}: a deploy with one swap still in flight would otherwise take that call for the swap's own commit and ack before it landed - the very thing this gate exists to stop.
	 *
	 * @return true when no swap was posted, so the ack is owed right away
	 */
	synchronized boolean noSwapPosted() {
		if (settled || outstanding > 0) {
			return false;
		}
		settled = true;
		return true;
	}
}
