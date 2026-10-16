package org.appdevforall.cotg.quickbuild.domain.reload

/**
 * Persistence for the session's generation counter. Implementations live in the data
 * layer (a file under the project's `.androidide` state dir); tests use an in-memory fake.
 *
 * Both members suspend because the callers sit on the session thread, which must not block
 * (concurrency.md), and the real store is a file on FUSE-backed storage.
 */
interface GenerationStore {
	/**
	 * Reads the last persisted generation.
	 *
	 * @return the stored number, or null when no session has ever run for this project - an
	 *   unreadable store must also answer null, since a throw would fail session startup.
	 */
	suspend fun load(): Long?

	/**
	 * Persists [generation] so it survives a CoGo restart.
	 *
	 * @param generation the number just allocated, written before it is handed out so that a
	 *   crash burns it rather than letting a later session reuse it.
	 */
	suspend fun save(generation: Long)
}

/**
 * Hands out monotonically increasing generation numbers for deploy payloads.
 *
 * The proxy app accepts a payload only if its generation is newer than the one it runs, so this
 * counter is what makes "an old payload can never replace a newer one" true even across a CoGo
 * crash: [next] persists before returning, burning a number rather than reusing it.
 *
 * Not thread-safe - call from the orchestrator's single-threaded context.
 *
 * Built through [open], which does the one read of the store; the constructor takes the
 * value so that no blocking read hides in construction.
 *
 * @param store where the counter survives a restart; read once by [open], so a store changed
 *   underneath a live tracker is not noticed.
 * @param initial the generation [open] read, or 0 when the store had none.
 */
class GenerationTracker(
	private val store: GenerationStore,
	initial: Long,
) {
	/** The most recently allocated generation; 0 before any session has run. */
	var current: Long = initial
		private set

	/**
	 * Allocates the next generation, persisting it before it is handed out.
	 *
	 * @return the new [current], always strictly greater than the previous one; a failed save
	 *   propagates, so no number is handed out that the store did not accept.
	 */
	suspend fun next(): Long {
		val next = current + 1
		store.save(next)
		current = next
		return next
	}

	/**
	 * Adopts a generation another allocator over the same store handed out, so [next] stays
	 * strictly above it.
	 *
	 * The proxy app build stamps its baseline generation through a host-side tracker over the
	 * same per-project store, while this tracker read the store once at construction. Without
	 * adopting the stamp after a rebaseline, [next] would hand out numbers at or below the
	 * freshly installed baseline and the runtime would reject every later deploy as stale.
	 * Persists like [next], so a crash cannot resurrect a number below an installed baseline.
	 *
	 * @param generation the stamped baseline generation; values at or below [current] are
	 *   no-ops, so an unstamped (0) baseline never moves the counter.
	 */
	suspend fun adoptAtLeast(generation: Long) {
		if (generation > current) {
			store.save(generation)
			current = generation
		}
	}

	companion object {
		/**
		 * Reads the store once and builds a tracker resuming from it.
		 *
		 * @param store where the counter survives a restart.
		 * @return a tracker whose [current] is the stored generation, or 0 for a fresh project.
		 */
		suspend fun open(store: GenerationStore): GenerationTracker = GenerationTracker(store, store.load() ?: 0L)
	}
}
