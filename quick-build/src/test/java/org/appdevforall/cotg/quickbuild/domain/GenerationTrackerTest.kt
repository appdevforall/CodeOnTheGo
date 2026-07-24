package org.appdevforall.cotg.quickbuild.domain

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
}
