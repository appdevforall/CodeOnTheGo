package com.itsaky.androidide.quickbuild.runtime;

/**
 * Which generation a crash right now should be quarantined against, so a fresh process stops booting it.
 *
 * A hot swap answers that on its own: the generation whose reload is still awaiting its first frame is the one that just took the screen. A restart deploy answers nothing - it persists the generation and kills the process, so the fresh process boots that generation from the store with no reload pending, and every crash on its way to the screen is invisible to the guard. Measured on an A56: the app crash-looped on the bad generation on every launch with no way out, where the same crash before the always-restart rule at least quarantined and came back on older code.
 *
 * So a generation this process took from the store is on probation until it proves itself, and the proof is the one a fallback already needs: {@link PayloadPersistence#markGood} recorded it, which only happens once an activity of it was resumed.
 *
 * Blaming too widely is the safe direction. {@link PayloadPersistence#quarantine} refuses to name a generation already recorded good, so an over-eager blame costs a log line rather than the user's last working code - which is also what keeps a fallback boot from quarantining the very generation it fell back to.
 */
final class BootProbation {

	/** The generation this process took from the store, until it proves itself, else -1. Guarded by {@code this}. */
	private long unprovenGeneration = -1;

	/**
	 * Puts the generation this process booted from the store on probation.
	 *
	 * @param generation
	 *            the persisted generation adopted at boot, or -1 when the process booted the code the installed APK carries - which is the floor a quarantine falls back to anyway, so there is nothing there to refuse
	 */
	synchronized void bootedFromStore(long generation) {
		unprovenGeneration = generation > 0 ? generation : -1;
	}

	/**
	 * The generation a crash happening right now should be quarantined against.
	 *
	 * @param pendingReloadGeneration
	 *            the hot-swapped generation awaiting its first frame, or -1; it outranks the booted one, being the newer claim on the screen that just died - unless the store has already moved past it, which means the value is stale (a later deploy acked while backgrounded) and the crash belongs to whatever runs now, not to it
	 * @param liveGeneration
	 *            the generation the store currently serves, which is how a booted generation superseded by a later deploy stops being blamed for that deploy's crash
	 * @return the generation to quarantine, or -1 when nothing this process adopted is to blame
	 */
	synchronized long generationToBlame(long pendingReloadGeneration, long liveGeneration) {
		if (pendingReloadGeneration >= 0 && pendingReloadGeneration >= liveGeneration) {
			return pendingReloadGeneration;
		}
		if (unprovenGeneration >= 0 && unprovenGeneration == liveGeneration) {
			return unprovenGeneration;
		}
		return -1;
	}

	/**
	 * Ends the probation: the generation is now recorded as the one a later quarantine falls back to.
	 *
	 * @param generation
	 *            the generation just recorded good; anything else is a confirmation for a superseded generation and leaves the probation where it is
	 */
	synchronized void proved(long generation) {
		if (generation == unprovenGeneration) {
			unprovenGeneration = -1;
		}
	}
}
