package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.projects.FileManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * The editor measures a completion's offset against its own buffer, so completion must read its text
 * from that same buffer.
 *
 * The pin it holds while computing cannot be the source: the pin is process-wide, so a request that
 * joins another feature's open scope gets that feature's frozen text, which the offset does not
 * describe. Refusing on a stale pin is not the answer either - the refusal returns before the
 * analysis, so the request stops preempting the older completion whose pin it joined and that older
 * one publishes items for a caret the user has already moved past.
 */
internal class CompletionRequestBufferTest : KtLspTest() {
	override val enableParserEventSystem = true

	private val openedPaths = mutableListOf<Path>()

	@After
	fun closeDocs() {
		openedPaths.forEach { FileManager.onDocumentClose(DocumentCloseEvent(it)) }
		openedPaths.clear()
	}

	private fun openDocument(
		relativePath: String,
		content: String,
	): Path {
		createSourceFile(relativePath, content)
		val path = env.sourceRoots.first().resolve(relativePath)
		FileManager.onDocumentOpen(DocumentOpenEvent(path, content, 1))
		openedPaths.add(path)
		return path
	}

	private fun changeDocument(
		path: Path,
		newContent: String,
	) = FileManager.onDocumentContentChange(
		DocumentChangeEvent(path, newContent, newContent, 2, ChangeType.NEW_TEXT, 0, Range.NONE),
	)

	private fun paramsAt(
		path: Path,
		offset: Int,
	) = CompletionParams(Position(0, 0, offset), path, ICancelChecker.NOOP)

	@Test
	fun `resolves the buffer, not the text a joined pin froze`() {
		val original =
			"""
			package p
			fun f() {}
			""".trimIndent()
		// The user types a second declaration, which exists only in the buffer.
		val edited = "$original\nfun g() {}"
		val path = openDocument("Buffer.kt", original)
		val offset = edited.indexOf("fun g")
		val params = paramsAt(path, offset)

		var buffer: CompletionRequestBuffer? = null
		env.ktSymbolIndex.withLiveKtFile(path) { live ->
			changeDocument(path, edited)
			assertTrue("the pin must be stale for this test to mean anything", live.isStale)
			buffer = completionRequestBuffer(params)
		}

		assertNotNull("a stale pin must not make completion refuse", buffer)
		assertEquals(edited, buffer?.text)
		assertEquals(offset, buffer?.offset)
	}

	@Test
	fun `refuses an offset the buffer no longer has`() {
		val original =
			"""
			package p
			fun f() { val someLongName = 0 }
			""".trimIndent()
		val path = openDocument("Shrunk.kt", original)
		val params = paramsAt(path, original.length - 2)

		assertNotNull(completionRequestBuffer(params))

		// The user deletes most of the file, so nothing is at the requested offset any more. Clamping it
		// into range would compute items for an unrelated context and insert them at the real caret.
		changeDocument(path, "package p")

		assertNull(completionRequestBuffer(params))
	}

	@Test
	fun `accepts an offset at the very end of the buffer`() {
		val content =
			"""
			package p
			fun f() { }
			""".trimIndent()
		val path = openDocument("End.kt", content)

		// Completion at end-of-file is ordinary: the placeholder appends there.
		assertEquals(content.length, completionRequestBuffer(paramsAt(path, content.length))?.offset)
	}
}
