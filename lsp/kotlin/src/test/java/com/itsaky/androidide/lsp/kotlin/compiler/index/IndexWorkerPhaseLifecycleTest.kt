package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.memprof.Memprof
import com.itsaky.androidide.memprof.MemprofSink
import com.itsaky.androidide.memprof.MemprofSpan
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataDescriptor
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.junit.After
import org.junit.Test

/**
 * Regression tests for the [IndexWorker] phase lifecycle edge cases: a phase span must never be
 * begun-and-immediately-ended for a boundary that never really opened, a scan cancelled mid-way
 * must not leave a stale span for the next scan to fold into, and a clean run must report both
 * phases as ended, not abandoned.
 *
 * None of the commands exercised here (`SourceScanningStarted`, `SourceScanningComplete`,
 * `IndexingComplete`, `Stop`) touch `PsiManager`, so a mocked [Project] needs only [Project.isDisposed].
 */
class IndexWorkerPhaseLifecycleTest {
	private val sink = RecordingSink()

	@After
	fun uninstallSink() {
		Memprof.sink = null
	}

	private fun worker(): IndexWorker {
		val project = mockk<Project>()
		every { project.isDisposed } returns false

		val symbolBacking = InMemoryIndex(JvmSymbolDescriptor)
		val sourceIndex =
			object : JvmSymbolIndex(symbolBacking, BackgroundIndexer(symbolBacking)) {
				override fun isActive(sourceId: String) = true
			}

		return IndexWorker(
			project = project,
			queue = WorkerQueue(),
			fileIndex = KtFileMetadataIndex(InMemoryIndex(KtFileMetadataDescriptor)),
			sourceIndex = sourceIndex,
			scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
		)
	}

	@Test
	fun `a complete scan and index reports both phases ended, not abandoned`(): Unit =
		runBlocking {
			Memprof.sink = sink
			val worker = worker()

			worker.submitCommand(IndexCommand.SourceScanningStarted)
			worker.submitCommand(IndexCommand.SourceScanningComplete)
			worker.submitCommand(IndexCommand.IndexingComplete)
			worker.submitCommand(IndexCommand.Stop)

			withTimeout(5_000) { worker.start() }

			assertThat(sink.events)
				.containsExactly(
					"begin phase source_scan_complete",
					"begin phase source_index_complete",
					"end phase source_scan_complete",
					"end phase source_index_complete",
				).inOrder()
		}

	@Test
	fun `Stop before SourceScanningComplete abandons the open scan phase`(): Unit =
		runBlocking {
			Memprof.sink = sink
			val worker = worker()

			worker.submitCommand(IndexCommand.SourceScanningStarted)
			worker.submitCommand(IndexCommand.Stop)

			withTimeout(5_000) { worker.start() }

			assertThat(sink.events)
				.containsExactly("begin phase source_scan_complete", "abandon source_scan_complete")
				.inOrder()
		}

	@Test
	fun `a new scan starting while a stale scan phase is open abandons it and begins fresh`(): Unit =
		runBlocking {
			Memprof.sink = sink
			val worker = worker()

			// The second SourceScanningStarted simulates KtSymbolIndex.refreshSources() restarting
			// a scan whose first attempt was cancelled before it ever reached SourceScanningComplete,
			// leaving the first scanPhase stale and open.
			worker.submitCommand(IndexCommand.SourceScanningStarted)
			worker.submitCommand(IndexCommand.SourceScanningStarted)
			worker.submitCommand(IndexCommand.Stop)

			withTimeout(5_000) { worker.start() }

			assertThat(sink.events)
				.containsExactly(
					"begin phase source_scan_complete",
					"abandon source_scan_complete",
					"begin phase source_scan_complete",
					"abandon source_scan_complete",
				).inOrder()
		}

	@Test
	fun `IndexingComplete with no open index phase emits nothing`(): Unit =
		runBlocking {
			Memprof.sink = sink
			val worker = worker()

			worker.submitCommand(IndexCommand.IndexingComplete)
			worker.submitCommand(IndexCommand.Stop)

			withTimeout(5_000) { worker.start() }

			assertThat(sink.events).isEmpty()
		}

	private class RecordingSink : MemprofSink {
		val events = mutableListOf<String>()

		override fun beginPhase(
			title: String,
			marker: String,
		): MemprofSpan {
			events += "begin phase $marker"
			return RecordingSpan(marker)
		}

		override fun beginSection(
			name: String,
			detail: String?,
		): MemprofSpan {
			events += "begin section $name"
			return RecordingSpan(name)
		}

		override fun mark(marker: String) {
			events += "mark $marker"
		}

		private inner class RecordingSpan(
			private val label: String,
		) : MemprofSpan {
			private var finished = false

			override val isRecording: Boolean = true

			override fun put(
				key: String,
				value: Long,
			) = Unit

			// Idempotent, matching MemprofSpan's documented contract: once ended or abandoned,
			// further calls do nothing.
			override fun end() {
				if (!finished) {
					finished = true
					events += "end phase $label"
				}
			}

			override fun abandon() {
				if (!finished) {
					finished = true
					events += "abandon $label"
				}
			}
		}
	}
}
