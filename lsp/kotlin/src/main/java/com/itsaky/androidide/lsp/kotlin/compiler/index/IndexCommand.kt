package com.itsaky.androidide.lsp.kotlin.compiler.index

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path

/*
 * A `pass` identifies the ScanningWorker.scan() call that sent a command, so a command left over from
 * a superseded scan cannot close a newer scan's phase. It is null for a file indexed outside a scan.
 */
internal sealed interface IndexCommand {
	data object Stop : IndexCommand

	data class SourceScanningStarted(
		val pass: Int,
	) : IndexCommand

	data class SourceScanningComplete(
		val pass: Int,
	) : IndexCommand

	data class IndexingComplete(
		val pass: Int,
	) : IndexCommand

	data class ScanSourceFile(
		val vf: VirtualFile,
	) : IndexCommand

	data class IndexModifiedFile(
		val ktFile: KtFile,
	) : IndexCommand

	data class IndexSourceFile(
		val vf: VirtualFile,
		val pass: Int? = null,
		val isRetry: Boolean = false,
	) : IndexCommand

	data class RemoveFromIndex(
		val path: Path,
	) : IndexCommand
}
