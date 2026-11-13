package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.write
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.FileElement
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Test

/** Requirement: one [KtFile] per open document, with each new text applied to that same instance. */
class IncrementalKtDocumentTest : KtLspTest() {
	override val enableParserEventSystem = true

	private val oldText = "fun f(a: Int): Int {\n    val x = 1\n    return a + x\n}"

	private fun document(text: String = oldText): IncrementalKtDocument =
		IncrementalKtDocument(env.ktSymbolIndex.parser.createFile("Doc.kt", text), appliedVersion = 1)

	private fun IncrementalKtDocument.applyUnderWrite(
		text: String,
		version: Int,
	): ReparseOutcome =
		env.project.write {
			ApplicationManager.getApplication().runWriteAction<ReparseOutcome> { apply(text, version) }
		}

	private fun KtFile.treeMatches(text: String): Boolean = (node as FileElement).textMatches(text)

	@Test
	fun `an in-body edit returns InBlock with the reparsed block attached to the same file`() {
		val doc = document()
		val newText = oldText.replace("val x = 1", "val x = 2")

		val outcome = doc.applyUnderWrite(newText, 2)

		assertThat(outcome).isInstanceOf(ReparseOutcome.InBlock::class.java)
		assertThat((outcome as ReparseOutcome.InBlock).reparsed.containingKtFile).isSameInstanceAs(doc.ktFile)
		assertThat(doc.ktFile.treeMatches(newText)).isTrue()
		assertThat(doc.appliedVersion).isEqualTo(2)
	}

	@Test
	fun `an edit that unbalances braces returns WholeFile and the text matches`() {
		val doc = document()
		val newText = oldText.replace("val x = 1", "val x = {")

		val outcome = doc.applyUnderWrite(newText, 2)

		assertThat(outcome).isEqualTo(ReparseOutcome.WholeFile)
		assertThat(doc.ktFile.treeMatches(newText)).isTrue()
	}

	@Test
	fun `replacing the whole buffer returns WholeFile on the same KtFile`() {
		val doc = document()
		val fileBefore = doc.ktFile
		val newText = "class Other {\n    val y = 2\n}\n"

		val outcome = doc.applyUnderWrite(newText, 2)

		assertThat(outcome).isEqualTo(ReparseOutcome.WholeFile)
		assertThat(doc.ktFile).isSameInstanceAs(fileBefore)
		assertThat(doc.ktFile.treeMatches(newText)).isTrue()
	}

	@Test
	fun `an unchanged text returns Unchanged`() {
		val doc = document()

		val outcome = doc.applyUnderWrite(oldText, 2)

		assertThat(outcome).isEqualTo(ReparseOutcome.Unchanged)
	}

	@Test
	fun `the light file content follows the edit`() {
		val doc = document()
		val newText = oldText.replace("val x = 1", "val x = 2")

		doc.applyUnderWrite(newText, 2)

		val contents = doc.ktFile.viewProvider.contents
		assertThat(contents.toString()).isEqualTo(newText)
	}

	/** Diagnostic and edit ranges map offsets to lines through this document (see `toRange`). */
	@Test
	fun `the PSI document follows every edit`() {
		val doc = document()
		val firstText = oldText.replace("val x = 1", "val x = 2")
		val secondText = firstText.replace("return a + x", "return a +\n        x")

		doc.applyUnderWrite(firstText, 2)
		doc.applyUnderWrite(secondText, 3)

		val document = PsiDocumentManager.getInstance(env.project).getDocument(doc.ktFile)!!
		assertThat(document.text).isEqualTo(secondText)
		assertThat(document.lineCount).isEqualTo(5)
	}
}
