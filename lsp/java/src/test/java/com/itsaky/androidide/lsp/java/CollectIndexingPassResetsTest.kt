package com.itsaky.androidide.lsp.java

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.appdevforall.codeonthego.indexing.service.IndexingProgressTracker
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** @author Akash Yadav */
@RunWith(JUnit4::class)
class CollectIndexingPassResetsTest {
	@Test
	fun `fires once per finished pass, across repeated passes`() =
		runBlocking {
			val finishedPasses = MutableStateFlow(0L)
			var resets = 0
			val job = launch { collectIndexingPassResets(finishedPasses) { resets++ } }
			yield()

			finishedPasses.value = 1
			yield()
			assertThat(resets).isEqualTo(1)

			finishedPasses.value = 2
			yield()
			assertThat(resets).isEqualTo(2)

			job.cancel()
		}

	@Test
	fun `does not fire for the passes finished before it started collecting`() =
		runBlocking {
			val finishedPasses = MutableStateFlow(3L)
			var resets = 0
			val job = launch { collectIndexingPassResets(finishedPasses) { resets++ } }
			yield()

			assertThat(resets).isEqualTo(0)
			job.cancel()
		}

	@Test
	fun `does not fire when indexing starts`() =
		runBlocking {
			val tracker = IndexingProgressTracker()
			var resets = 0
			val job = launch { collectIndexingPassResets(tracker.finishedPasses) { resets++ } }
			yield()

			val pass = tracker.openPass()
			pass.track("/libs/app.jar", Job())
			yield()

			assertThat(resets).isEqualTo(0)
			pass.close()
			job.cancel()
		}

	@Test
	fun `a pass that starts and finishes before the collector runs still resets`() =
		runBlocking {
			val tracker = IndexingProgressTracker()
			var resets = 0
			val job = launch { collectIndexingPassResets(tracker.finishedPasses) { resets++ } }
			yield()

			tracker.openPass().use { pass -> pass.track("/libs/app.jar", Job().apply { complete() }) }
			yield()

			assertThat(resets).isEqualTo(1)
			job.cancel()
		}
}
