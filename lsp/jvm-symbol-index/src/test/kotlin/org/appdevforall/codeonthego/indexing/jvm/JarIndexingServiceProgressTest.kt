package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.api.Workspace
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
import org.appdevforall.codeonthego.indexing.service.IndexingProgressTracker
import org.appdevforall.codeonthego.indexing.service.IndexingState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A [JarIndexingService.refresh] pass reports its submitted JARs to the tracker, and always closes. */
@RunWith(RobolectricTestRunner::class)
class JarIndexingServiceProgressTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val tracker = IndexingProgressTracker()

	@After
	fun tearDown() {
		context.deleteDatabase(DB_NAME)
	}

	@Test
	fun `a refresh counts each JAR it submits while its pass is open`() {
		val service = TestService(jars = setOf(jar("a.jar"), jar("b.jar")))

		service.refreshAndAwait()

		assertThat((service.stateWhileCompleting as? IndexingState.Indexing)?.total).isEqualTo(2)
	}

	@Test
	fun `the state is Idle once a refresh finishes`() {
		val service = TestService(jars = setOf(jar("a.jar")))

		service.refreshAndAwait()

		assertThat(tracker.state.value).isEqualTo(IndexingState.Idle)
	}

	@Test
	fun `a refresh that throws after submitting still returns the state to Idle`() {
		val service = TestService(jars = setOf(jar("a.jar")), onComplete = { error("optimize failed") })

		service.refreshAndAwait()

		assertThat(tracker.state.value).isEqualTo(IndexingState.Idle)
	}

	@Test
	fun `a refresh cancelled after submitting still returns the state to Idle`() {
		val completing = CompletableDeferred<Unit>()
		val service =
			TestService(jars = setOf(jar("a.jar")), onComplete = {
				completing.complete(Unit)
				awaitCancellation()
			})

		service.use {
			runBlocking {
				it.initialize(IndexRegistry())
				val pass = it.refresh()
				completing.await()
				pass.cancel()
				pass.join()
			}
		}

		assertThat(tracker.state.value).isEqualTo(IndexingState.Idle)
	}

	private fun TestService.refreshAndAwait() {
		use {
			runBlocking {
				it.initialize(IndexRegistry())
				it.refresh().join()
			}
		}
	}

	private fun jar(name: String): String =
		File(temp.root, name)
			.apply { writeBytes(ByteArray(1)) }
			.absolutePath

	private inner class TestService(
		private val jars: Set<String>,
		private val onComplete: suspend () -> Unit = {},
	) : JarIndexingService(context, tracker, workspaceSupplier = { mockk<Workspace>() }) {
		var stateWhileCompleting: IndexingState? = null

		override val id = "jar-indexing-service-progress-test"
		override val indexKey = IndexKey<JvmSymbolIndex>("jar-indexing-service-progress-test")
		override val dbName = DB_NAME
		override val indexName = INDEX_NAME

		override fun jarsToIndex(workspace: Workspace): Set<String> = jars

		override suspend fun completePass(jobs: List<Job>) {
			stateWhileCompleting = tracker.state.value
			onComplete()
			super.completePass(jobs)
		}
	}

	private companion object {
		const val DB_NAME = "jar_indexing_service_progress_test.db"
		const val INDEX_NAME = "jar-indexing-service-progress-test-index"
	}
}
