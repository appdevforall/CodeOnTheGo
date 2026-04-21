package com.itsaky.androidide.lsp.kotlin.compiler.index

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.psi.KtFile

internal sealed interface IndexCommand {
	data object Stop : IndexCommand
	data object SourceScanningComplete: IndexCommand
	data object IndexingComplete: IndexCommand
	data class ScanSourceFile(val vf: VirtualFile): IndexCommand
	data class IndexModifiedFile(val ktFile: KtFile): IndexCommand
	data class IndexSourceFile(val vf: VirtualFile): IndexCommand
}