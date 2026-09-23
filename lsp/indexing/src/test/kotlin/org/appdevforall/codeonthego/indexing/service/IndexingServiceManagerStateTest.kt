package org.appdevforall.codeonthego.indexing.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class IndexingServiceManagerStateTest {
	@Test
	fun `the state reports the tracker's progress`() {
		val manager = IndexingServiceManager()

		manager.progressTracker.openPass().track("a", Job())

		assertThat(manager.state.value).isEqualTo(IndexingState.Indexing(done = 0, total = 1))
		manager.close()
	}

	@Test
	fun `close leaves the state Idle while a pass is open and a job pending`() {
		val manager = IndexingServiceManager()
		manager.progressTracker.openPass().track("a", Job())

		manager.close()

		assertThat(manager.state.value).isEqualTo(IndexingState.Idle)
	}
}
