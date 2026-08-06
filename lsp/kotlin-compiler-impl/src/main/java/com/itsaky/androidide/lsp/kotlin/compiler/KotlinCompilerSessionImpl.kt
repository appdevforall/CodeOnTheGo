package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.kotlin.KotlinCodeActionsMenu
import com.itsaky.androidide.lsp.kotlin.api.IKotlinCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.api.IKotlinCompilerSession
import com.itsaky.androidide.lsp.util.LSPEditorActions
import com.itsaky.androidide.projects.api.Workspace
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatform
import java.nio.file.Path

/** Wraps [Compiler] behind [IKotlinCompilerSession] for the resident side to call across the DexClassLoader boundary. */
internal class KotlinCompilerSessionImpl(
	private val compiler: Compiler,
	private val projectModel: KotlinProjectModel,
	private val jvmPlatform: TargetPlatform,
) : IKotlinCompilerSession {
	override fun updateLanguageClient(client: ILanguageClient?) {
		compiler.updateLanguageClient(client)
	}

	override fun updateProjectModel(workspace: Workspace) {
		projectModel.update(workspace, jvmPlatform)
	}

	override fun refreshSources() {
		compiler.refreshSources()
	}

	override fun compilationEnvironmentFor(file: Path): IKotlinCompilationEnvironment? = compiler.compilationEnvironmentFor(file)

	override fun registerCodeActions() {
		LSPEditorActions.ensureActionsMenuRegistered(KotlinCodeActionsMenu)
	}

	override fun unregisterCodeActions() {
		LSPEditorActions.ensureActionsMenuUnregistered(KotlinCodeActionsMenu)
	}

	override fun openFileIfNeeded(activeDocumentFiles: List<Path>) {
		activeDocumentFiles.forEach { file ->
			compiler.compilationEnvironmentFor(file)?.openFileIfNeeded(file)
		}
	}

	override fun close() {
		compiler.close()
	}
}
