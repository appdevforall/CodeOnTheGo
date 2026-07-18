package org.appdevforall.cotg.quickbuild.domain

/**
 * Persistence for the session's generation counter. Implementations live in the data
 * layer (a file under the project's `.androidide` state dir); tests use an in-memory fake.
 */
interface GenerationStore {
	/** The last persisted generation, or null when no session has ever run. */
	fun load(): Long?

	fun save(generation: Long)
}

/**
 * Monotonic generation counter (plan section 2.5). The test app accepts a payload only if
 * its generation is newer than the one it runs, so this counter is what makes "an old
 * payload can never replace a newer one" true — including across CoGo crashes.
 *
 * [next] persists BEFORE returning: if CoGo dies between allocating a generation and the
 * deploy completing, the number is burned, never reused. Gaps are fine; reuse is not.
 *
 * Not thread-safe by design — call from the orchestrator's single-threaded context.
 */
class GenerationTracker(
	private val store: GenerationStore,
) {
	var current: Long = store.load() ?: 0L
		private set

	fun next(): Long {
		val next = current + 1
		store.save(next)
		current = next
		return next
	}
}
