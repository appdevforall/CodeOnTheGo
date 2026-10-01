package org.appdevforall.codeonthego.indexing.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import org.appdevforall.codeonthego.indexing.service.IndexingState.Idle
import org.appdevforall.codeonthego.indexing.service.IndexingState.Indexing
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class IndexingProgressTrackerTest {
	private val tracker = IndexingProgressTracker()

	@Test
	fun `each tracked source counts once toward the total`() {
		val pass = tracker.openPass()

		pass.track("a", Job())
		pass.track("b", Job())

		assertThat(tracker.state.value).isEqualTo(Indexing(done = 0, total = 2))
	}

	@Test
	fun `a source resubmitted while pending is counted once`() {
		val pass = tracker.openPass()
		val first = Job()

		pass.track("a", first)
		pass.track("a", first)
		pass.track("a", Job())

		assertThat(tracker.state.value).isEqualTo(Indexing(done = 0, total = 1))
	}

	@Test
	fun `a superseded job's completion is not counted as done`() {
		val pass = tracker.openPass()
		val superseded = Job()
		pass.track("a", superseded)
		pass.track("a", Job())

		superseded.cancel()

		assertThat(tracker.state.value).isEqualTo(Indexing(done = 0, total = 1))
	}

	@Test
	fun `a pass that tracks a source another pass has pending counts nothing new`() {
		val job = Job()
		tracker.openPass().track("a", job)
		val folding = tracker.openPass()

		folding.track("a", job)

		assertThat(folding.newlyCounted).isEqualTo(0)
	}

	@Test
	fun `a pass counts each source it adds to the total`() {
		val pass = tracker.openPass()

		pass.track("a", Job())
		pass.track("b", Job())

		assertThat(pass.newlyCounted).isEqualTo(2)
	}

	@Test
	fun `a completed job counts toward done`() {
		val pass = tracker.openPass()
		val first = Job()
		pass.track("a", first)
		pass.track("b", Job())

		first.complete()

		assertThat(tracker.state.value).isEqualTo(Indexing(done = 1, total = 2))
	}

	@Test
	fun `the state returns to Idle once the pass closes and every job completed`() {
		val job = Job()
		tracker.openPass().use { it.track("a", job) }

		job.complete()

		assertThat(tracker.state.value).isEqualTo(Idle)
	}

	@Test
	fun `a cancelled job still returns the state to Idle`() {
		val job = Job()
		tracker.openPass().use { it.track("a", job) }

		job.cancel()

		assertThat(tracker.state.value).isEqualTo(Idle)
	}

	@Test
	fun `a failed job still returns the state to Idle`() {
		val job = Job()
		tracker.openPass().use { it.track("a", job) }

		job.completeExceptionally(IllegalStateException("scan failed"))

		assertThat(tracker.state.value).isEqualTo(Idle)
	}

	@Test
	fun `a pass that throws between submissions still returns the state to Idle`() {
		val job = Job()

		runCatching {
			tracker.openPass().use { pass ->
				pass.track("a", job)
				error("jarsToIndex failed")
			}
		}
		job.complete()

		assertThat(tracker.state.value).isEqualTo(Idle)
	}

	@Test
	fun `an open pass stays Indexing after its only tracked job completes`() {
		val pass = tracker.openPass()

		pass.track("a", completedJob())

		assertThat(tracker.state.value).isEqualTo(Indexing(done = 1, total = 1))
	}

	@Test
	fun `jobs still pending after their pass closes keep the state Indexing`() {
		tracker.openPass().use { it.track("a", Job()) }

		assertThat(tracker.state.value).isEqualTo(Indexing(done = 0, total = 1))
	}

	@Test
	fun `an open pass that tracked nothing leaves the state Idle`() {
		tracker.openPass()

		tracker.openPass().use { it.track("a", completedJob()) }

		assertThat(tracker.state.value).isEqualTo(Idle)
	}

	@Test
	fun `counts restart from zero once the state returns to Idle`() {
		tracker.openPass().use { it.track("a", completedJob()) }

		tracker.openPass().track("b", Job())

		assertThat(tracker.state.value).isEqualTo(Indexing(done = 0, total = 1))
	}

	@Test
	fun `reset returns the state to Idle while work is pending`() {
		tracker.openPass().track("a", Job())

		tracker.reset()

		assertThat(tracker.state.value).isEqualTo(Idle)
	}

	@Test
	fun `a pass left open across reset no longer counts its submissions`() {
		val stale = tracker.openPass()
		tracker.reset()

		stale.track("a", Job())

		assertThat(tracker.state.value).isEqualTo(Idle)
	}

	private fun completedJob(): CompletableJob = Job().apply { complete() }
}
