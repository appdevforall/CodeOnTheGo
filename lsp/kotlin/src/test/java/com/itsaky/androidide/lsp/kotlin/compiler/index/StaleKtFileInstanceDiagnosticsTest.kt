package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.psi.KtFile
import org.junit.After
import org.junit.Test
import java.nio.file.Path

/**
 * A `KtFile` instance for an open path must not be reported as a redeclaration of itself once a
 * newer instance for the same path has been registered.
 *
 * `KtSymbolIndex.currentFiles` mints a fresh instance per observed document version, and
 * `DeclarationProvider.ktFilesForPackage` resolves the path to whatever the newest one is. An
 * analysis that started against an older instance therefore sees every declaration in the file
 * twice - once as its own PSI, once through the provider - and reports the whole file as
 * conflicting. That is what reaches the editor as red squiggles over every declaration.
 */
internal class StaleKtFileInstanceDiagnosticsTest : KtLspTest() {
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

		fun render(a: Int, b: Int): Int = extracted(b, a) + a

		private fun extracted(b: Int, a: Int): Int = b * a
		""".trimIndent()

	private fun diagnosticsOf(ktFile: KtFile): List<String> =
		env.analyze(ktFile) {
			ktFile
				.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
				.map { "${it.factoryName}: ${it.defaultMessage}" }
		}

	@Test
	fun `an analysis holding a superseded instance does not see the file twice`() {
		createSourceFile("Main.kt", content)
		val path = env.sourceRoots.first().resolve("Main.kt")
		FileManager.onDocumentOpen(DocumentOpenEvent(path, content, 1))
		openedPaths.add(path)

		val inFlight = env.ktSymbolIndex.getCurrentKtFile(path).get()!!
		assertThat(diagnosticsOf(inFlight)).isEmpty()

		// Another request observes a different document version and installs a second instance for the
		// same path - identical text, new identity. In production this is any of the twelve
		// getCurrentKtFile call sites (the refresh scheduler, completion, a code action) running while
		// the diagnostics pass for `inFlight` is still going.
		FileManager.onDocumentContentChange(
			DocumentChangeEvent(path, content, content, 2, ChangeType.NEW_TEXT, 0, Range.NONE),
		)
		val superseding = env.ktSymbolIndex.getCurrentKtFile(path).get()!!
		assertThat(superseding).isNotSameInstanceAs(inFlight)

		assertThat(diagnosticsOf(inFlight)).isEmpty()
	}
}
