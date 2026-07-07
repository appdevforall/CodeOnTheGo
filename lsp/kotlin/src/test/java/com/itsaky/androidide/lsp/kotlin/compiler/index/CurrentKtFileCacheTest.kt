package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.file.Path

internal class CurrentKtFileCacheTest : KtLspTest() {

	private val openedPaths = mutableListOf<Path>()

	@After
	fun closeDocs() {
		openedPaths.forEach { FileManager.onDocumentClose(docCloseEvent(it)) }
		openedPaths.clear()
	}

	private fun docCloseEvent(path: Path) = DocumentCloseEvent(path)

	/** The [Path] under the first source root that [createSourceFile] wrote [relativePath] to. */
	private fun sourcePath(relativePath: String): Path =
		env.sourceRoots.first().resolve(relativePath)

	/** Registers [path] as an active document at version 1 with [content]. */
	private fun openDocument(path: Path, content: String) {
		FileManager.onDocumentOpen(DocumentOpenEvent(path, content, 1))
		openedPaths.add(path)
	}

	private fun changeDocument(path: Path, content: String, version: Int) {
		FileManager.onDocumentContentChange(
			DocumentChangeEvent(path, content, content, version, ChangeType.NEW_TEXT, 0, Range.NONE)
		)
	}

	@Test
	fun `same version returns same instance`() {
		createSourceFile("A.kt", "fun a() {}")
		val path = sourcePath("A.kt")
		openDocument(path, "fun a() {}")

		val first = env.ktSymbolIndex.getCurrentKtFile(path).get()
		val second = env.ktSymbolIndex.getCurrentKtFile(path).get()

		assertSame(first, second)
	}

	@Test
	fun `new version returns new instance reflecting new content`() {
		createSourceFile("B.kt", "fun b() {}")
		val path = sourcePath("B.kt")
		openDocument(path, "fun b() {}")
		val v1 = env.ktSymbolIndex.getCurrentKtFile(path).get()!!

		changeDocument(path, "fun b() {}\nfun c() {}", 2)
		val v2 = env.ktSymbolIndex.getCurrentKtFile(path).get()!!

		assertNotSame(v1, v2)
		assertEquals("fun b() {}\nfun c() {}", v2.text)
	}

	@Test
	fun `concurrent requests at same version parse once`() {
		createSourceFile("D.kt", "fun d() {}")
		val path = sourcePath("D.kt")
		openDocument(path, "fun d() {}")

		val futures = (1..16).map { env.ktSymbolIndex.getCurrentKtFile(path) }
		val results = futures.map { it.get() }

		results.forEach { assertSame(results.first(), it) }
	}

	@Test
	fun `refreshed file resolves against new content via analysis`() {
		createSourceFile("E.kt", "fun e(): Int = 1")
		val path = sourcePath("E.kt")
		openDocument(path, "fun e(): Int = 1")
		env.ktSymbolIndex.getCurrentKtFile(path).get()

		changeDocument(path, "fun e(): Int = 1\nfun f(): Int = e()", 2)
		val v2 = env.ktSymbolIndex.getCurrentKtFile(path).get()!!

		// The new top-level `f` calling `e` must resolve without UNRESOLVED_REFERENCE.
		val diagnostics = env.analyze(v2) {
			v2.collectDiagnostics(
				org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS
			).toList()
		}
		assertEquals(emptyList<Any>(), diagnostics.map { it.defaultMessage })
	}
}
