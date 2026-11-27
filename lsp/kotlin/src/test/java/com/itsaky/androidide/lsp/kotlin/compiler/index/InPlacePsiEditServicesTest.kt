package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.write
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicator
import org.jetbrains.kotlin.com.intellij.psi.impl.BlockSupportImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.ChangedPsiRangeUtil
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.FileElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import org.junit.Test

class InPlacePsiEditServicesTest : KtLspTest() {
	override val enableParserEventSystem = true

	@Test
	fun `an in-place block reparse applies to the same KtFile`() {
		val oldText = "fun f(a: Int): Int {\n    val x = 1\n    return a + x\n}"
		val newText = oldText.replace("val x = 1", "val x = 2")
		val file = env.ktSymbolIndex.parser.createFile("InPlace.kt", oldText)
		val functionBefore = file.findDescendantOfType<KtNamedFunction>()
		val project = env.project

		val oldRootType =
			project.write {
				ApplicationManager.getApplication().runWriteAction<String> {
					val fileNode = file.node as FileElement
					val range = ChangedPsiRangeUtil.getChangedPsiRange(file, fileNode, newText)!!
					val result =
						BlockSupportImpl.reparse(file, fileNode, range, newText, EmptyProgressIndicator(), oldText)
					CommandProcessor.getInstance().executeCommand(
						project,
						{ result.log.doActualPsiChange(file) },
						null,
						null,
					)
					result.oldRoot.elementType.toString()
				}
			}

		assertThat((file.node as FileElement).textMatches(newText)).isTrue()
		assertThat(file.findDescendantOfType<KtNamedFunction>()).isSameInstanceAs(functionBefore)
		assertThat(oldRootType).isEqualTo("BLOCK")
	}
}
