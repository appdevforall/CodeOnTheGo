package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPreemptedException
import com.itsaky.androidide.memprof.Memprof
import com.itsaky.androidide.memprof.MemprofSink
import com.itsaky.androidide.memprof.MemprofSpan
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataDescriptor
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import org.junit.After
import org.junit.Test
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Regression tests for the [IndexWorker] phase lifecycle edge cases: a phase span must never be
 * begun-and-immediately-ended for a boundary that never really opened, a scan cancelled mid-way
 * must not leave a stale span for the next scan to fold into, and a clean run must report both
 * phases as ended, not abandoned. A command from a superseded scan pass, or from no pass at all,
 * must not open or close the current pass's phases.
 *
 * The tests that send file commands mock [PsiManager] and, for indexing, `indexSourceFile`.
 */
class IndexWorkerPhaseLifecycleTest {
	private val sink = RecordingSink()

	@After
	fun uninstallSink() {
		Memprof.sink = null
		unmockkAll()
	}

	private fun worker(): IndexWorker {
		val project = mockk<Project>()
		every { project.isDisposed } returns false
		every { project.getUserData(any<Key<ReentrantReadWriteLock>>()) } returns ReentrantReadWriteLock()

		val symbolBacking = InMemoryIndex(JvmSymbolDescriptor)
		val sourceIndex =
			object : JvmSymbolIndex(symbolBacking, BackgroundIndexer(symbolBacking)) {
				override fun visibleSourceIds(): Collection<String>? = null
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

			worker.submitCommand(IndexCommand.SourceScanningStarted(pass = 1))
			worker.submitCommand(IndexCommand.SourceScanningComplete(pass = 1))
			worker.submitCommand(IndexCommand.IndexingComplete(pass = 1))
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

			worker.submitCommand(IndexCommand.SourceScanningStarted(pass = 1))
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

			/*
			 * The second SourceScanningStarted simulates KtSymbolIndex.refreshSources() restarting
			 * a scan whose first attempt was cancelled before it ever reached SourceScanningComplete,
			 * leaving the first scanPhase stale and open.
			 */
			worker.submitCommand(IndexCommand.SourceScanningStarted(pass = 1))
			worker.submitCommand(IndexCommand.SourceScanningStarted(pass = 2))
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

			worker.submitCommand(IndexCommand.IndexingComplete(pass = 1))
			worker.submitCommand(IndexCommand.Stop)

			withTimeout(5_000) { worker.start() }

			assertThat(sink.events).isEmpty()
		}

	@Test
	fun `a ScanSourceFile outside a scan pass opens no phase`(): Unit =
		runBlocking {
			Memprof.sink = sink
			mockPsiManager(ktFile = null)
			val worker = worker()

			worker.submitCommand(IndexCommand.ScanSourceFile(sourceFile()))
			worker.submitCommand(IndexCommand.Stop)

			withTimeout(5_000) { worker.start() }

			assertThat(sink.events).isEmpty()
		}

	@Test
	fun `a superseded pass's IndexingComplete does not end the current pass's index phase`(): Unit =
		runBlocking {
			Memprof.sink = sink
			val worker = worker()

			worker.submitCommand(IndexCommand.SourceScanningStarted(pass = 1))
			worker.submitCommand(IndexCommand.SourceScanningComplete(pass = 1))
			worker.submitCommand(IndexCommand.SourceScanningStarted(pass = 2))
			worker.submitCommand(IndexCommand.SourceScanningComplete(pass = 2))
			worker.submitCommand(IndexCommand.IndexingComplete(pass = 1))
			worker.submitCommand(IndexCommand.Stop)

			withTimeout(5_000) { worker.start() }

			assertThat(sink.events)
				.containsExactly(
					"begin phase source_scan_complete",
					"begin phase source_index_complete",
					"end phase source_scan_complete",
					"abandon source_index_complete",
					"begin phase source_scan_complete",
					"begin phase source_index_complete",
					"end phase source_scan_complete",
					"abandon source_index_complete",
				).inOrder()
		}

	@Test
	fun `the index phase ends only after a preempted file's retry is indexed`(): Unit =
		runBlocking {
			Memprof.sink = sink
			mockPsiManager(ktFile = mockk())
			mockkStatic("com.itsaky.androidide.lsp.kotlin.compiler.index.SourceFileIndexerKt")
			var attempts = 0
			coEvery { indexSourceFile(any(), any(), any(), any(), any()) } answers {
				if (++attempts == 1) throw AnalysisPreemptedException()
				sink.events += "indexed retry"
			}
			val worker = worker()

			worker.submitCommand(IndexCommand.SourceScanningStarted(pass = 1))
			worker.submitCommand(IndexCommand.SourceScanningComplete(pass = 1))
			worker.submitCommand(IndexCommand.IndexSourceFile(sourceFile(), pass = 1))
			worker.submitCommand(IndexCommand.IndexingComplete(pass = 1))

			withTimeout(5_000) {
				val running = launch { worker.start() }
				while ("end phase source_index_complete" !in sink.events) delay(10)
				worker.submitCommand(IndexCommand.Stop)
				running.join()
			}

			assertThat(sink.events.filter { "Index Kotlin file" !in it })
				.containsExactly(
					"begin phase source_scan_complete",
					"begin phase source_index_complete",
					"end phase source_scan_complete",
					"indexed retry",
					"end phase source_index_complete",
				).inOrder()
		}

	@Test
	fun `an edit re-indexed during a pass is not counted in that pass`(): Unit =
		runBlocking {
			Memprof.sink = sink
			mockkStatic("com.itsaky.androidide.lsp.kotlin.compiler.index.SourceFileIndexerKt")
			coEvery { indexSourceFile(any(), any(), any(), any(), any()) } answers { sink.events += "indexed edit" }
			val editedFile = mockk<KtFile>()
			every { editedFile.getUserData(any<Key<Path>>()) } returns Path.of("/project/src/Edited.kt")
			val worker = worker()

			worker.submitCommand(IndexCommand.SourceScanningStarted(pass = 1))
			worker.submitCommand(IndexCommand.SourceScanningComplete(pass = 1))
			worker.submitCommand(IndexCommand.IndexModifiedFile(editedFile))

			withTimeout(5_000) {
				val running = launch { worker.start() }
				while ("indexed edit" !in sink.events) delay(10)
				worker.submitCommand(IndexCommand.IndexingComplete(pass = 1))
				while ("end phase source_index_complete" !in sink.events) delay(10)
				worker.submitCommand(IndexCommand.Stop)
				running.join()
			}

			assertThat(sink.values["source_index_complete.indexed"]).isEqualTo(0L)
		}

	private fun mockPsiManager(ktFile: KtFile?) {
		val psiManager = mockk<PsiManager>()
		every { psiManager.findFile(any()) } returns ktFile
		mockkStatic(PsiManager::class)
		every { PsiManager.getInstance(any()) } returns psiManager
	}

	private fun sourceFile(): VirtualFile {
		val vf = mockk<VirtualFile>()
		every { vf.fileSystem.protocol } returns "file"
		every { vf.path } returns "/project/src/Sample.kt"
		return vf
	}

	private class RecordingSink : MemprofSink {
		val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
		val values: MutableMap<String, Long> = Collections.synchronizedMap(mutableMapOf())

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
			) {
				values["$label.$key"] = value
			}

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
