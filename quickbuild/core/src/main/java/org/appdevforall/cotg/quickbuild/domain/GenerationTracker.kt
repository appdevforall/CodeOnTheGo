package org.appdevforall.cotg.quickbuild.domain

/**
 * Persistence for the session's generation counter. Implementations live in the data
 * layer (a file under the project's `.androidide` state dir); tests use an in-memory fake.
 */
interface GenerationStore {
	/**
	 * Reads the last persisted generation.
	 *
	 * @return the stored number, or null when no session has ever run for this project. An
	 *   unreadable store must also answer null, since a throw would fail session startup.
	 */
	fun load(): Long?

	/**
	 * Persists [generation] so it survives a CoGo restart.
	 *
	 * @param generation the number just allocated. Written before it is handed out, so a crash
	 *   burns the number rather than letting a later session reuse it.
	 */
	fun save(generation: Long)
}

/**
 * Hands out monotonically increasing generation numbers for deploy payloads.
 *
 * The proxy app accepts a payload only if its generation is newer than the one it runs, so
 * this counter is what makes "an old payload can never replace a newer one" true, including
 * across CoGo crashes: [next] persists before returning, so a number is burned rather than
 * reused. Gaps are fine; reuse is not.
 *
 * Not thread-safe - call from the orchestrator's single-threaded context.
 *
 * @param store where the counter survives a restart; read once at construction, so a store
 *   changed underneath a live tracker is not noticed.
 */
class GenerationTracker(
	private val store: GenerationStore,
) {
	/** The most recently allocated generation; 0 before any session has run. */
	var current: Long = store.load() ?: 0L
		private set

	/**
	 * Allocates the next generation, persisting it before it is handed out.
	 *
	 * @return the new [current], always strictly greater than the previous one. A failed save
	 *   propagates, so no number is handed out that the store did not accept.
	 */
	fun next(): Long {
		val next = current + 1
		store.save(next)
		current = next
		return next
	}
}
