package com.itsaky.androidide.lsp.kotlin.navigation

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.nio.file.Path

class FindDefinitionRequestTest : KtLspTest() {
	// The live-document regression test below drives resolution and range computation through a
	// KtFile built by KtSymbolIndex.refreshToCurrent (KtPsiFactory.createFile), not through
	// createSourceFile's on-disk file. That file is only a faithful stand-in for production's live
	// document if it is physical the way production's is - see KtLspTestEnvironment's parameter of
	// the same name.
	override val enableParserEventSystem = true

	private val openedPaths = mutableListOf<Path>()

	@After
	fun closeDocs() {
		openedPaths.forEach { FileManager.onDocumentClose(DocumentCloseEvent(it)) }
		openedPaths.clear()
	}

	/** Registers [path] as an active document at version 1 with [content], as the editor does. */
	private fun openDocument(
		path: Path,
		content: String,
	) {
		FileManager.onDocumentOpen(DocumentOpenEvent(path, content, 1))
		openedPaths.add(path)
	}

	private fun requestAt(
		file: Path,
		text: String,
		marker: String,
		delta: Int = 0,
		cancelChecker: ICancelChecker = ICancelChecker.NOOP,
	) = runBlocking {
		val offset =
			text.indexOf(marker).also { check(it >= 0) { "marker '$marker' not in source" } } + delta
		val params = DefinitionParams(file, Position(0, 0, offset), cancelChecker)
		context(env) { findDefinitionAt(params) }
	}

	@Test
	fun `a resolvable reference returns its declaration`() {
		val text = "fun target() {}\nfun caller() { target() }"
		val ktFile = createSourceFile("Request.kt", text)
		val path = Path.of(ktFile.virtualFile.path)

		// "{ target(" not "target()": the bare form matches the declaration first.
		val result = requestAt(path, text, "{ target()", delta = 3)

		assertThat(result.locations).hasSize(1)
		assertThat(
			result.locations[0]
				.file.fileName
				.toString(),
		).isEqualTo("Request.kt")
		assertThat(
			result.locations[0]
				.range.start.index,
		).isEqualTo(text.indexOf("fun target") + 4)
	}

	@Test
	fun `a caret that names nothing returns an empty result`() {
		val text = "fun target() {}\nfun caller() {   }"
		val ktFile = createSourceFile("Empty.kt", text)
		val path = Path.of(ktFile.virtualFile.path)

		assertThat(requestAt(path, text, "{   }", delta = 2).locations).isEmpty()
	}

	@Test
	fun `a file the environment cannot load returns an empty result`() {
		val text = "fun caller() { target() }"
		val missing = env.sourceRoots.first().resolve("Missing.kt")

		assertThat(requestAt(missing, text, "target()", delta = 1).locations).isEmpty()
	}

	@Test
	fun `a cancelled request returns an empty result rather than throwing`() {
		val text = "fun target() {}\nfun caller() { target() }"
		val ktFile = createSourceFile("Cancel.kt", text)
		val path = Path.of(ktFile.virtualFile.path)

		val result =
			requestAt(
				path,
				text,
				"{ target()",
				delta = 3,
				cancelChecker = ICancelChecker.CANCELLED,
			)

		// The caret is on a genuinely resolvable call, so an empty result can only come from
		// cancellation - with the ambiguous "target()" marker this would have passed for the wrong
		// reason, by landing on the declaration's own name.
		assertThat(result.locations).isEmpty()
	}

	@Test
	fun `a same-file target found through the active document still resolves`() {
		// Every other test in this file leaves the file un-opened, so acquisition takes the disk
		// fallback - a real CoreLocalFileSystem-backed KtFile whose virtualFile has protocol
		// "file". That's exactly the path the production bug (ADFA-4823 finding 1) does NOT hit:
		// opening the file makes acquisition refresh a live KtFile instead
		// (KtSymbolIndex.refreshToCurrent), whose virtualFile is a non-physical LightVirtualFile -
		// locationOfPsi must resolve a path from backingFilePath instead, which is exactly what this
		// test exercises.
		val text = "fun target() {}\nfun caller() { target() }"
		val ktFile = createSourceFile("Live.kt", text)
		val path = Path.of(ktFile.virtualFile.path)
		openDocument(path, text)

		// "{ target(" not "target()": the bare form matches the declaration first.
		val result = requestAt(path, text, "{ target()", delta = 3)

		assertThat(result.locations).hasSize(1)
		assertThat(result.locations[0].file).isEqualTo(path)
		assertThat(
			result.locations[0]
				.range.start.index,
		).isEqualTo(text.indexOf("fun target") + 4)
	}
}
