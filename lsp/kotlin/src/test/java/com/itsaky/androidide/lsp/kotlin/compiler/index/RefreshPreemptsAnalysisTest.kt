package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPreemptedException
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.compiler.modules.withAnalysisLock
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Applying an edit needs `project.write`, which every analysis blocks by holding `project.read` for
 * its whole run. Completion waits on that refresh before it ever reaches the scheduler, so without a
 * barrier a long INDEXING analysis delays the keystroke's completion by its full duration.
 */
internal class RefreshPreemptsAnalysisTest : KtLspTest() {
	override val enableParserEventSystem = true

	private val openedPaths = mutableListOf<Path>()

	@After
	fun closeDocs() {
		openedPaths.forEach { FileManager.onDocumentClose(DocumentCloseEvent(it)) }
		openedPaths.clear()
	}

	private val content =
		"""
		package p

		class Widget
		""".trimIndent()

	private fun openDocument(): Path {
		createSourceFile("Main.kt", content)
		val path = env.sourceRoots.first().resolve("Main.kt")
		FileManager.onDocumentOpen(DocumentOpenEvent(path, content, 1))
		openedPaths.add(path)
		return path
	}

	@Test(timeout = 30_000)
	fun `a refresh does not wait for a running indexing analysis`() {
		val path = openDocument()
		val started = CountDownLatch(1)
		val analysisError = AtomicReference<Throwable?>()
		// Cancelled in finally so a regression (refresh timed out) still stops the analysis and frees the lock.
		val cleanup = ICancelChecker.Default()
		val checker = ScheduledCancelChecker(cleanup)
		val analysis =
			thread {
				try {
					env.project.read {
						withAnalysisLock(AnalysisPriority.INDEXING, checker) {
							started.countDown()
							while (true) {
								checker.abortIfCancelled()
								Thread.sleep(10)
							}
						}
					}
				} catch (e: Throwable) {
					analysisError.set(e)
				}
			}
		try {
			assertThat(started.await(5, SECONDS)).isTrue()
			FileManager.onDocumentContentChange(
				DocumentChangeEvent(path, content, content, 2, ChangeType.NEW_TEXT, 0, Range.NONE),
			)

			runBlocking { withTimeout(3_000) { env.ktSymbolIndex.refreshCurrentKtFile(path) } }

			analysis.join(5_000)
			assertThat(analysisError.get()).isInstanceOf(AnalysisPreemptedException::class.java)
		} finally {
			cleanup.cancel()
			analysis.join()
		}
	}
}
