package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.actions.ImplementMembersAction
import com.itsaky.androidide.lsp.kotlin.actions.OrganizeImportsAction
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.projects.FileManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * A site whose output is an edit must also refuse when the buffer moves *while* it computes.
 *
 * [StalePinEditRefusalTest] covers the pin that was already stale when the site joined it. The wider
 * window is the computation itself: the pin is fresh when the site checks it, the analysis is slow
 * enough for the user to type through, and nothing between the site's return and `performCodeAction`
 * re-checks the offsets the edits were measured against.
 */
internal class BufferMovedWhileComputingTest : KtLspTest() {
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

	/**
	 * An [ICancelChecker] that moves [path]'s document to [newContent] the first time the analysis
	 * probes it.
	 *
	 * That first probe is the one `AnalysisScheduler.acquire` makes, which lands after the site's
	 * pre-acquisition staleness check and before it has produced any edit - the window under test. The
	 * checker reports "not cancelled", so the computation itself runs through untouched, and the pin
	 * keeps the version bump from refreshing the instance under it.
	 */
	private fun editingOnFirstProbe(
		path: Path,
		newContent: String,
	): ICancelChecker =
		object : ICancelChecker.Default() {
			private var probed = false

			override fun isCancelled(): Boolean {
				if (!probed) {
					probed = true
					FileManager.onDocumentContentChange(
						DocumentChangeEvent(path, newContent, newContent, 2, ChangeType.NEW_TEXT, 0, Range.NONE),
					)
				}
				return super.isCancelled()
			}
		}

	@Test
	fun `organize-imports drops edits when the buffer moves while it computes`() {
		createSourceFile("lib/Lib.kt", "package lib\nclass Used\nclass Unused")
		val content =
			"""
			package p
			import lib.Used
			import lib.Unused
			fun f(x: Used) {}
			""".trimIndent()
		val path = openDocument("Main.kt", content)
		val action = OrganizeImportsAction()

		assertFalse(action.computeOrganizeEdit(env, path, ICancelChecker.NOOP).isEmpty())

		// The user adds an import, shifting the import list this edit's range was measured against.
		val edited = content.replaceFirst("import lib.Used\n", "import lib.Used\nimport lib.Other\n")

		assertTrue(action.computeOrganizeEdit(env, path, editingOnFirstProbe(path, edited)).isEmpty())
	}

	@Test
	fun `implement-members drops edits when the buffer moves while it computes`() {
		val content =
			"""
			package p
			interface I { fun foo() }
			class C : I
			""".trimIndent()
		val path = openDocument("Members.kt", content)
		val caret = content.indexOf("class C") + 2
		val action = ImplementMembersAction()

		assertFalse(action.computeImplementMembersEdit(env, path, caret, ICancelChecker.NOOP).isEmpty())

		// The user adds an import, shifting the insertion offset this edit was measured against.
		val edited = content.replaceFirst("package p\n", "package p\nimport kotlin.math.max\n")

		assertTrue(action.computeImplementMembersEdit(env, path, caret, editingOnFirstProbe(path, edited)).isEmpty())
	}
}
