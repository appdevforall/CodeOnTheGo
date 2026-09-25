package com.itsaky.androidide.lsp.java

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.appdevforall.codeonthego.indexing.service.IndexingState
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** @author Akash Yadav */
@RunWith(JUnit4::class)
class CollectIndexingStateResetsTest {
	@Test
	fun `fires once per Indexing to Idle transition, across repeated cycles`() =
		runBlocking {
			val state = MutableStateFlow<IndexingState>(IndexingState.Idle)
			var resets = 0
			val job = launch { collectIndexingStateResets(state) { resets++ } }
			yield()

			state.value = IndexingState.Indexing(done = 0, total = 1)
			yield()
			assertThat(resets).isEqualTo(0)

			state.value = IndexingState.Indexing(done = 1, total = 1)
			yield()
			assertThat(resets).isEqualTo(0)

			state.value = IndexingState.Idle
			yield()
			assertThat(resets).isEqualTo(1)

			// A second full cycle must fire again, not just once ever.
			state.value = IndexingState.Indexing(done = 0, total = 1)
			yield()
			assertThat(resets).isEqualTo(1)

			state.value = IndexingState.Idle
			yield()
			assertThat(resets).isEqualTo(2)

			job.cancel()
		}

	@Test
	fun `does not fire when indexing starts`() =
		runBlocking {
			val state = MutableStateFlow<IndexingState>(IndexingState.Idle)
			var resets = 0
			val job = launch { collectIndexingStateResets(state) { resets++ } }
			yield()

			state.value = IndexingState.Indexing(done = 0, total = 1)
			yield()

			assertThat(resets).isEqualTo(0)
			job.cancel()
		}
}
