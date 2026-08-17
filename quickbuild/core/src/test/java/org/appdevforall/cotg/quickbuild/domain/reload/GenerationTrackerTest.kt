package org.appdevforall.cotg.quickbuild.domain.reload

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GenerationTrackerTest {
	private class FakeStore(
		private var stored: Long? = null,
	) : GenerationStore {
		val saves: MutableList<Long> = mutableListOf()

		override fun load(): Long? = stored

		override fun save(generation: Long) {
			saves.add(generation)
			stored = generation
		}
	}

	@Test
	fun `fresh store starts at generation 0 and next returns 1`() {
		val store = FakeStore()
		val tracker = GenerationTracker(store)

		assertThat(tracker.current).isEqualTo(0L)

		val next = tracker.next()

		assertThat(next).isEqualTo(1L)
		assertThat(store.saves).isEqualTo(listOf(1L))
	}

	@Test
	fun `next is monotonic across calls`() {
		val store = FakeStore()
		val tracker = GenerationTracker(store)

		assertThat(tracker.next()).isEqualTo(1L)
		assertThat(tracker.current).isEqualTo(1L)

		assertThat(tracker.next()).isEqualTo(2L)
		assertThat(tracker.current).isEqualTo(2L)

		assertThat(tracker.next()).isEqualTo(3L)
		assertThat(tracker.current).isEqualTo(3L)
	}

	@Test
	fun `resumes from a store with an existing generation`() {
		val store = FakeStore(stored = 41L)
		val tracker = GenerationTracker(store)

		assertThat(tracker.current).isEqualTo(41L)
		assertThat(tracker.next()).isEqualTo(42L)
	}

	@Test
	fun `persists before next returns`() {
		val store = FakeStore()
		val tracker = GenerationTracker(store)

		val next = tracker.next()

		assertThat(next).isEqualTo(1L)
		assertThat(store.saves).isEqualTo(listOf(1L))
	}

	@Test
	fun `adoptAtLeast moves the counter past a stamped baseline and persists it`() {
		// A rebaseline stamps generation 8 through the host-side allocator while this
		// (session) tracker still sits at 7; without adoption the next deploy would be 8,
		// equal to the baseline, and the runtime would reject it as stale.
		val store = FakeStore(stored = 7L)
		val tracker = GenerationTracker(store)

		tracker.adoptAtLeast(8L)

		assertThat(tracker.current).isEqualTo(8L)
		assertThat(store.saves).isEqualTo(listOf(8L))
		assertThat(tracker.next()).isEqualTo(9L)
	}

	@Test
	fun `adoptAtLeast is a no-op at or below the current counter`() {
		val store = FakeStore(stored = 5L)
		val tracker = GenerationTracker(store)

		// An unstamped (0) baseline and a stale stamp must not move or re-save the counter.
		tracker.adoptAtLeast(0L)
		tracker.adoptAtLeast(5L)

		assertThat(tracker.current).isEqualTo(5L)
		assertThat(store.saves).isEmpty()
		assertThat(tracker.next()).isEqualTo(6L)
	}
}
