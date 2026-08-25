package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.actions.AddImportAction
import com.itsaky.androidide.lsp.kotlin.actions.ImplementMembersAction
import com.itsaky.androidide.lsp.kotlin.actions.OrganizeImportsAction
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractionRefusal
import com.itsaky.androidide.lsp.kotlin.utils.refactor.buildExtractMethodPlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.buildExtractionPlan
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.jvm.JvmClassInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmSourceLanguage
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * A site whose output is an edit must refuse rather than compute against a pin it joined.
 *
 * While any scope on a path is open, every other request for that path joins it and gets its
 * instance, however old. The action layer stamps its version guard from the live buffer, so a joined
 * stale pin passes that guard and then applies offsets measured against older text to the newer
 * buffer - a silent wrong edit, in the one place a check exists to prevent exactly that. Each site
 * here degrades to its own "nothing to offer" answer instead.
 *
 * Every test first computes the unpinned result and asserts it is non-empty, so a refusal cannot pass
 * for an unrelated reason.
 */
internal class StalePinEditRefusalTest : KtLspTest() {
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
	 * Runs [block] inside an open scope on [path] whose document has since moved on.
	 *
	 * This is the production shape: some other feature holds the pin, the user types, and [block]'s
	 * acquisition joins the frozen instance instead of resolving the current one.
	 */
	private fun <R> whileHoldingAStalePin(
		path: Path,
		content: String,
		block: () -> R,
	): R =
		env.ktSymbolIndex.withLiveKtFile(path) { live ->
			FileManager.onDocumentContentChange(
				DocumentChangeEvent(path, content, content, 2, ChangeType.NEW_TEXT, 0, Range.NONE),
			)
			assertTrue("the pin must be stale for this test to mean anything", live.isStale)
			block()
		}!!

	@Test
	fun `extract-method refuses a plan built on a joined stale pin`() {
		val content =
			"""
			package p
			fun demo(a: Int, b: Int): Int {
				return b * a + a
			}
			""".trimIndent()
		val path = openDocument("Method.kt", content)
		val offset = content.indexOf("b * a") + 1
		val plan = { buildExtractMethodPlan(env, path, offset, offset, 2, noopCancelChecker()) }

		assertFalse(plan().candidates.isEmpty())
		val refused = whileHoldingAStalePin(path, content, plan)

		assertEquals(ExtractionRefusal.CouldNotAnalyse, refused.refusal)
		assertTrue(refused.candidates.isEmpty())
	}

	@Test
	fun `extract-variable returns an empty plan on a joined stale pin`() {
		val content =
			"""
			package p
			fun demo(a: Int, b: Int): Int {
				return b * a + a
			}
			""".trimIndent()
		val path = openDocument("Variable.kt", content)
		val start = content.indexOf("b * a")
		val plan = { buildExtractionPlan(env, path, start, start + "b * a".length, 2, noopCancelChecker()) }

		assertFalse(plan().candidates.isEmpty())
		val empty = whileHoldingAStalePin(path, content, plan)

		assertTrue(empty.candidates.isEmpty())
	}

	@Test
	fun `organize-imports emits no edit on a joined stale pin`() {
		createSourceFile("lib/Lib.kt", "package lib\nclass Used\nclass Unused")
		val content =
			"""
			package p
			import lib.Used
			import lib.Unused
			fun f(x: Used) {}
			""".trimIndent()
		val path = openDocument("Main.kt", content)
		val edits = { OrganizeImportsAction().computeOrganizeEdit(env, path, ICancelChecker.NOOP) }

		assertFalse(edits().isEmpty())

		assertTrue(whileHoldingAStalePin(path, content, edits).isEmpty())
	}

	@Test
	fun `implement-members emits no edit on a joined stale pin`() {
		val content =
			"""
			package p
			interface I { fun foo() }
			class C : I
			""".trimIndent()
		val path = openDocument("Members.kt", content)
		val caret = content.indexOf("class C") + 2
		val edits = { ImplementMembersAction().computeImplementMembersEdit(env, path, caret, ICancelChecker.NOOP) }

		assertFalse(edits().isEmpty())

		assertTrue(whileHoldingAStalePin(path, content, edits).isEmpty())
	}

	@Test
	fun `add-import offers no candidate on a joined stale pin`() {
		runBlocking {
			env.ktSymbolIndex.sourceIndex.insert(
				JvmSymbol(
					key = "lib/Foo#CLASS",
					sourceId = "test",
					name = "lib/Foo",
					shortName = "Foo",
					packageName = "lib",
					kind = JvmSymbolKind.CLASS,
					language = JvmSourceLanguage.KOTLIN,
					data = JvmClassInfo(),
				),
			)
		}
		val content = "package p\nfun f(x: Foo) {}"
		val path = openDocument("Import.kt", content)
		val candidates = { AddImportAction().computeImportCandidates(env, path, "Foo") }

		assertFalse(candidates().isEmpty())

		assertTrue(whileHoldingAStalePin(path, content, candidates).isEmpty())
	}
}
