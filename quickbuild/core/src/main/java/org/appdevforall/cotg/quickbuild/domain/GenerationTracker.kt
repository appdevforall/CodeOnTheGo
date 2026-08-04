package org.appdevforall.cotg.quickbuild.domain

/**
 * Persistence for the session's generation counter. Implementations live in the data
 * layer (a file under the project's `.androidide` state dir); tests use an in-memory fake.
 */
interface GenerationStore {
	/** The last persisted generation, or null when no session has ever run. */
	fun load(): Long?

	/** Persists [generation] so it survives a CoGo restart. */
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
 */
class GenerationTracker(
	private val store: GenerationStore,
) {
	/** The most recently allocated generation; 0 before any session has run. */
	var current: Long = store.load() ?: 0L
		private set

	/** Allocates the next generation, persisting it before it is handed out. */
	fun next(): Long {
		val next = current + 1
		store.save(next)
		current = next
		return next
	}
}
