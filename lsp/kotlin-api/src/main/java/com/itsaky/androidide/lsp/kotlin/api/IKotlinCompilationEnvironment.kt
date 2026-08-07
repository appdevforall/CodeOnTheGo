package com.itsaky.androidide.lsp.kotlin.api

import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.lsp.models.DefinitionResult
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.progress.ICancelChecker
import java.nio.file.Path

/**
 * Bridge to a single Kotlin compilation environment (one per [IKotlinCompilerSession]
 * today), implemented on the far side of the [IKotlinCompilerSession]'s DexClassLoader
 * boundary. All operations here are what were previously called directly on
 * `CompilationEnvironment`/the `compiler.*` free functions from `KotlinLanguageServer`.
 */
interface IKotlinCompilationEnvironment {
	fun openFileIfNeeded(path: Path)

	fun onFileOpen(path: Path)

	fun onFileContentChanged(path: Path)

	fun onFileClosed(path: Path)

	fun onFileSaved(path: Path)

	suspend fun onFileCreated(path: Path)

	suspend fun onFileRemoved(path: Path)

	suspend fun onFileMoved(
		fromPath: Path,
		toPath: Path,
	)

	fun complete(params: CompletionParams): CompletionResult

	suspend fun findDefinition(params: DefinitionParams): DefinitionResult

	suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp

	fun collectDiagnostics(
		file: Path,
		cancelChecker: ICancelChecker,
	): DiagnosticResult
}
