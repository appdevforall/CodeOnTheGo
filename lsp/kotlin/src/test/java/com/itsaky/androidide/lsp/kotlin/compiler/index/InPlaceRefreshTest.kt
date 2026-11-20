package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.compiler.write
import com.itsaky.androidide.lsp.kotlin.compiler.services.ProjectStructureProvider
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.psi.KtFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.io.path.pathString
import java.util.concurrent.TimeUnit.SECONDS

/** Requirement: an open document keeps one [KtFile], edited in place, with pins deferring the edit. */
internal class InPlaceRefreshTest : KtLspTest() {
	override val enableParserEventSystem = true

	private val openedPaths = mutableListOf<Path>()

	@After
	fun closeDocs() {
		env.ktSymbolIndex.onRefreshDeferred = null
		env.ktSymbolIndex.beforePinStampReleased = null
		env.ktSymbolIndex.beforeParsedDocumentPublished = null
		openedPaths.forEach { FileManager.onDocumentClose(DocumentCloseEvent(it)) }
		openedPaths.clear()
	}

	private fun sourcePath(relativePath: String): Path = env.sourceRoots.first().resolve(relativePath)

	private fun openDocument(
		path: Path,
		content: String,
	) {
		FileManager.onDocumentOpen(DocumentOpenEvent(path, content, 1))
		openedPaths.add(path)
	}

	private fun closeDocument(path: Path) {
		FileManager.onDocumentClose(DocumentCloseEvent(path))
		openedPaths.remove(path)
	}

	private fun changeDocument(
		path: Path,
		content: String,
		version: Int,
	) {
		FileManager.onDocumentContentChange(
			DocumentChangeEvent(path, content, content, version, ChangeType.NEW_TEXT, 0, Range.NONE),
		)
	}

	@OptIn(UnpinnedKtFileAccess::class)
	private fun currentInstance(path: Path): KtFile? {
		runBlocking { env.ktSymbolIndex.refreshCurrentKtFile(path) }
		return env.ktSymbolIndex.peekLiveKtFile(path)
	}

	/** The document's text once its applied version reaches [version], observed without pinning or refreshing. */
	private fun awaitAppliedText(
		path: Path,
		version: Int,
	): String? {
		val deadline = System.nanoTime() + SECONDS.toNanos(10)
		while (System.nanoTime() < deadline) {
			val doc = env.ktSymbolIndex.incrementalDocument(path)
			if (doc != null && doc.appliedVersion == version) return env.project.read { doc.ktFile.text }
			Thread.sleep(20)
		}
		return null
	}

	private fun pinnedText(path: Path): String? = env.ktSymbolIndex.withLiveKtFile(path) { live -> live.read { it.text } }

	@Test
	fun `a version bump keeps the same KtFile and its new text`() {
		createSourceFile("A.kt", "fun a() {}")
		val path = sourcePath("A.kt")
		openDocument(path, "fun a() {}")
		val v1 = currentInstance(path)

		changeDocument(path, "fun a() {\n    println()\n}", 2)
		val v2 = currentInstance(path)

		assertNotNull(v1)
		assertSame(v1, v2)
		assertEquals("fun a() {\n    println()\n}", pinnedText(path))
	}

	@Test
	fun `an edit while the path is pinned is deferred and applied when the pin is released`() {
		createSourceFile("B.kt", "fun b() {}")
		val path = sourcePath("B.kt")
		openDocument(path, "fun b() {}")
		currentInstance(path)

		val textInsidePin =
			env.ktSymbolIndex.withLiveKtFile(path) { live ->
				changeDocument(path, "fun b() {}\nfun c() {}", 2)
				runBlocking { env.ktSymbolIndex.refreshCurrentKtFile(path) }
				live.read { it.text }
			}

		assertEquals("fun b() {}", textInsidePin)
		// Pinning again would refresh by itself and hide a release that never scheduled the owed refresh.
		assertEquals("fun b() {}\nfun c() {}", awaitAppliedText(path, 2))
	}

	/**
	 * A refresh can reach the document after a pin took its read stamp but before the pin is
	 * published; it must not edit under the stamp, and must not be mistaken for the new version later.
	 */
	@Test
	fun `a refresh that finds the document locked is deferred and a later refresh applies it`() {
		createSourceFile("C.kt", "fun c() {}")
		val path = sourcePath("C.kt")
		openDocument(path, "fun c() {}")
		currentInstance(path)
		val doc = env.ktSymbolIndex.incrementalDocument(path)!!

		val stamp = doc.editLock.readLock()
		try {
			changeDocument(path, "fun c() {}\nfun d() {}", 2)
			runBlocking { env.ktSymbolIndex.refreshCurrentKtFile(path) }
			assertEquals(1, doc.appliedVersion)
		} finally {
			doc.editLock.unlockRead(stamp)
		}

		assertEquals("fun c() {}\nfun d() {}", pinnedText(path))
		assertEquals(2, doc.appliedVersion)
	}

	@Test
	fun `closing a document while an edit is deferred does not apply it`() {
		createSourceFile("D.kt", "fun d() {}")
		val path = sourcePath("D.kt")
		openDocument(path, "fun d() {}")
		currentInstance(path)

		env.ktSymbolIndex.withLiveKtFile(path) {
			changeDocument(path, "fun d() {}\nfun e() {}", 2)
			runBlocking { env.ktSymbolIndex.refreshCurrentKtFile(path) }
			closeDocument(path)
			env.ktSymbolIndex.invalidateCurrent(path)
		}

		assertEquals(null, env.ktSymbolIndex.incrementalDocument(path))
		assertEquals("fun d() {}", pinnedText(path))
	}

	@Test
	fun `a reparse failure falls back to a full parse with correct analysis`() {
		createSourceFile("E.kt", "fun e(): Int = 1")
		val path = sourcePath("E.kt")
		openDocument(path, "fun e(): Int = 1")
		currentInstance(path)
		val failing = env.ktSymbolIndex.incrementalDocument(path)!!
		failing.reparseOverride = { ReparseOutcome.Failed("forced by test", null) }

		changeDocument(path, "fun e(): Int = 1\nfun f(): Int = e()", 2)
		val diagnosticMessages =
			env.ktSymbolIndex.withLiveKtFile(path) { live ->
				live.analyzing(AnalysisPriority.DIAGNOSTICS, noopCancelChecker()) { ktFile ->
					ktFile
						.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
						.map { it.defaultMessage }
				}
			}

		assertNotSame(failing, env.ktSymbolIndex.incrementalDocument(path))
		assertEquals("fun e(): Int = 1\nfun f(): Int = e()", pinnedText(path))
		assertEquals(emptyList<String>(), diagnosticMessages)
	}

	@Test
	fun `a full parse that finishes after close leaves no document behind`() {
		createSourceFile("G.kt", "fun g() {}")
		val path = sourcePath("G.kt")
		openDocument(path, "fun g() {}")

		// The parse needs project.read, so holding the write lock keeps it in flight across the close.
		val refresh =
			env.project.write {
				// Undispatched, so the refresh is launched on this thread before the close below.
				val inFlight =
					CoroutineScope(Dispatchers.IO).future(start = CoroutineStart.UNDISPATCHED) {
						env.ktSymbolIndex.refreshCurrentKtFile(path)
					}
				closeDocument(path)
				env.ktSymbolIndex.invalidateCurrent(path)
				inFlight
			}
		refresh.get(10, SECONDS)

		assertEquals(null, env.ktSymbolIndex.incrementalDocument(path))
		assertFalse(ProjectStructureProvider.getInstance(env.project).hasInMemoryFile(path.pathString))
	}

	@Test
	fun `an edit deferred by a pin that is already leaving is applied after the release`() {
		createSourceFile("H.kt", "fun h() {}")
		val path = sourcePath("H.kt")
		openDocument(path, "fun h() {}")
		currentInstance(path)
		val edited = "fun h() {}\nfun i() {}"
		env.ktSymbolIndex.beforePinStampReleased = {
			env.ktSymbolIndex.beforePinStampReleased = null
			changeDocument(path, edited, 2)
			// The pin has left the map but still holds its stamp, so this refresh defers with no pin to owe the edit to.
			runBlocking { env.ktSymbolIndex.refreshCurrentKtFile(path) }
		}

		env.ktSymbolIndex.withLiveKtFile(path) { }

		assertEquals(edited, awaitAppliedText(path, 2))
	}

	@Test
	fun `a refresh that joins a deferred refresh still in flight brings the document up to date`() {
		createSourceFile("J.kt", "fun j() {}")
		val path = sourcePath("J.kt")
		openDocument(path, "fun j() {}")
		currentInstance(path)
		val doc = env.ktSymbolIndex.incrementalDocument(path)!!
		val edited = "fun j() {}\nfun k() {}"
		val deferred = CountDownLatch(1)
		val resume = CountDownLatch(1)
		env.ktSymbolIndex.onRefreshDeferred = {
			env.ktSymbolIndex.onRefreshDeferred = null
			deferred.countDown()
			resume.await(10, SECONDS)
		}

		val stamp = doc.editLock.readLock()
		changeDocument(path, edited, 2)
		val first = CoroutineScope(Dispatchers.IO).future { env.ktSymbolIndex.refreshCurrentKtFile(path) }
		assertTrue(deferred.await(10, SECONDS))
		doc.editLock.unlockRead(stamp)
		// As a pin's release retry would: the deferred refresh is still pending, so this joins it.
		val retry = CoroutineScope(Dispatchers.IO).future { env.ktSymbolIndex.refreshCurrentKtFile(path) }
		Thread.sleep(200)
		resume.countDown()
		first.get(10, SECONDS)
		retry.get(10, SECONDS)

		assertEquals(2, doc.appliedVersion)
		assertEquals(edited, env.project.read { doc.ktFile.text })
	}

	@Test
	fun `a parse from before a close and reopen does not replace the reopened document`() {
		createSourceFile("M.kt", "fun m() {}")
		val path = sourcePath("M.kt")
		openDocument(path, "fun m() {}")
		val parsed = CountDownLatch(1)
		val resume = CountDownLatch(1)
		env.ktSymbolIndex.beforeParsedDocumentPublished = {
			env.ktSymbolIndex.beforeParsedDocumentPublished = null
			parsed.countDown()
			resume.await(10, SECONDS)
		}
		val stale = CoroutineScope(Dispatchers.IO).future { env.ktSymbolIndex.refreshCurrentKtFile(path) }
		assertTrue(parsed.await(10, SECONDS))

		closeDocument(path)
		env.ktSymbolIndex.invalidateCurrent(path)
		val reopened = "fun m() {}\nfun n() {}"
		openDocument(path, reopened)
		currentInstance(path)
		resume.countDown()
		stale.get(10, SECONDS)

		val document = env.ktSymbolIndex.incrementalDocument(path)!!
		assertEquals(reopened, env.project.read { document.ktFile.text })
		assertSame(
			document.ktFile.viewProvider.virtualFile,
			ProjectStructureProvider.getInstance(env.project).inMemoryFile(path.pathString),
		)
	}
}
