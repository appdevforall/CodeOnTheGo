package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtElement
import org.junit.After
import org.junit.Test
import java.nio.file.Path

/**
 * Typing inside a function body must not make the enclosing class a redeclaration of itself.
 *
 * Reproduces GitHub issue #2152: open a file, let diagnostics run, type one space inside an empty
 * function body, and the next diagnostics run reports `Redeclaration: class LocalC : Any` across the
 * whole class.
 */
internal class InBodyEditRedeclarationTest : KtLspTest() {
	override val enableParserEventSystem = true

	private val openedPaths = mutableListOf<Path>()

	@After
	fun closeDocs() {
		openedPaths.forEach { FileManager.onDocumentClose(DocumentCloseEvent(it)) }
		openedPaths.clear()
	}

	private fun localC(bodyLine: String) = "package p\n\nclass LocalC {\n    fun ori() {\n$bodyLine\n    }\n}\n"

	private val original = localC("    ")

	private val withSpaceInBody = localC("     ")

	private fun diagnosticsFor(path: Path): List<String> =
		env.ktSymbolIndex.withLiveKtFile(path) { live ->
			live.analyzing(AnalysisPriority.DIAGNOSTICS, noopCancelChecker()) { ktFile ->
				ktFile
					.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
					.map { "${it.factoryName}: ${it.defaultMessage}" }
			}
		}!!

	/** What a completion request does with the buffer: analyse a placeholder copy and resolve the caret's scope. */
	private fun completeAt(
		path: Path,
		offset: Int,
	) {
		val buffer = FileManager.getDocumentContents(path)
		val withPlaceholder = buffer.substring(0, offset) + "KT_COMPLETION_PLACEHOLDER" + buffer.substring(offset)
		env.ktSymbolIndex.withLiveKtFile(path) { live ->
			live.analyzingVariant(path.fileName.toString(), withPlaceholder, AnalysisPriority.INTERACTIVE, noopCancelChecker()) { variant ->
				val position = PsiTreeUtil.getParentOfType(variant.findElementAt(offset), KtElement::class.java)!!
				variant.scopeContext(position).scopes.size
			}
		}
	}

	@Test
	fun `typing a space inside a function body reports no redeclaration`() {
		createSourceFile("LocalC.kt", original)
		val path = env.sourceRoots.first().resolve("LocalC.kt")
		FileManager.onDocumentOpen(DocumentOpenEvent(path, original, 1))
		openedPaths.add(path)
		assertThat(diagnosticsFor(path)).isEmpty()

		FileManager.onDocumentContentChange(
			DocumentChangeEvent(path, " ", withSpaceInBody, 2, ChangeType.INSERT, 1, Range.NONE),
		)
		completeAt(path, withSpaceInBody.indexOf("ori() {\n") + "ori() {\n     ".length)

		assertThat(diagnosticsFor(path)).isEmpty()
	}
}
