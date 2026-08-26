package com.itsaky.androidide.lsp.kotlin.navigation

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.nio.file.Path

/**
 * R5's live-buffer tier: a usage that exists only in an unsaved editor buffer must still be found.
 *
 * Separate from [FindUsagesTest] because it needs `enableParserEventSystem`, so that the `KtFile` built
 * from the buffer is physical the way production's is (see `KtLspTestEnvironment`).
 *
 * This is the case find usages is most often run in - you search *while* editing - and the one a
 * disk-only prefilter silently gets wrong: the file would never be selected as a candidate, so it would
 * never be parsed and the usage would simply not appear.
 */
class FindUsagesLiveDocumentTest : KtLspTest() {
	override val enableParserEventSystem = true

	private val openedPaths = mutableListOf<Path>()

	@After
	fun closeDocs() {
		openedPaths.forEach { FileManager.onDocumentClose(DocumentCloseEvent(it)) }
		openedPaths.clear()
	}

	private fun openDocument(
		path: Path,
		content: String,
	) {
		FileManager.onDocumentOpen(DocumentOpenEvent(path, content, 1))
		openedPaths.add(path)
	}

	@Test
	fun `a usage typed into an unsaved buffer is found`() {
		val declarationText = "fun target() {}"
		val declaration = createSourceFile("Declaration.kt", declarationText)
		val declarationPath = Path.of(declaration.virtualFile.path)

		// On disk this file contains no usage at all, so a prefilter reading saved bytes would skip it.
		val usage = createSourceFile("Usage.kt", "fun caller() {  }")
		val usagePath = Path.of(usage.virtualFile.path)
		val editedText = "fun caller() { target() }"
		openDocument(usagePath, editedText)

		val params =
			ReferenceParams(
				declarationPath,
				Position(0, 0, declarationText.indexOf("target")),
				true,
				ICancelChecker.NOOP,
			)
		val locations = runBlocking { context(env) { findUsagesAt(params) } }.locations

		assertThat(locations).hasSize(1)
		assertThat(locations[0].file).isEqualTo(usagePath)
		assertThat(locations[0].range.start.index).isEqualTo(editedText.indexOf("target()"))
	}

	@Test
	fun `a usage deleted in an unsaved buffer is not reported`() {
		val declarationText = "fun target() {}"
		val declaration = createSourceFile("GoneDeclaration.kt", declarationText)
		val declarationPath = Path.of(declaration.virtualFile.path)

		// The saved bytes still mention the name, so this file is still a candidate; it is resolution,
		// not the prefilter, that must reject it.
		val usage = createSourceFile("GoneUsage.kt", "fun caller() { target() }")
		val usagePath = Path.of(usage.virtualFile.path)
		openDocument(usagePath, "fun caller() {  }")

		val params =
			ReferenceParams(
				declarationPath,
				Position(0, 0, declarationText.indexOf("target")),
				true,
				ICancelChecker.NOOP,
			)

		assertThat(runBlocking { context(env) { findUsagesAt(params) } }.locations).isEmpty()
	}
}
