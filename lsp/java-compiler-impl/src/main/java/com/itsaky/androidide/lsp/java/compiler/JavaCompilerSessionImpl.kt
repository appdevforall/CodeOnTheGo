/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.itsaky.androidide.lsp.java.compiler

import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.javac.services.fs.CachingJarFileSystemProvider
import com.itsaky.androidide.lsp.internal.model.CachedCompletion
import com.itsaky.androidide.lsp.java.JavaCompilerProvider
import com.itsaky.androidide.lsp.java.actions.JavaCodeActionsMenu
import com.itsaky.androidide.lsp.java.api.IJavaCompilerSession
import com.itsaky.androidide.lsp.java.models.JavaServerSettings
import com.itsaky.androidide.lsp.java.providers.CodeFormatProvider
import com.itsaky.androidide.lsp.java.providers.CompletionProvider
import com.itsaky.androidide.lsp.java.providers.DefinitionProvider
import com.itsaky.androidide.lsp.java.providers.JavaDiagnosticProvider
import com.itsaky.androidide.lsp.java.providers.JavaSelectionProvider
import com.itsaky.androidide.lsp.java.providers.ReferenceProvider
import com.itsaky.androidide.lsp.java.providers.SignatureProvider
import com.itsaky.androidide.lsp.java.utils.CancelChecker.Companion.isCancelled
import com.itsaky.androidide.lsp.models.CodeFormatResult
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.lsp.models.DefinitionResult
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.ExpandSelectionParams
import com.itsaky.androidide.lsp.models.FormatCodeParams
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.lsp.models.ReferenceResult
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.lsp.util.LSPEditorActions
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.IProjectManager.Companion.getInstance
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import jdkx.tools.JavaFileObject
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.Objects

/**
 * Implements [IJavaCompilerSession] on the isolated side of the DexClassLoader boundary --
 * everything [JavaLanguageServer][com.itsaky.androidide.lsp.java.JavaLanguageServer]'s
 * complete/findReferences/findDefinition/expandSelection/signatureHelp/analyze/onContentChange
 * bodies used to do directly, before javac moved out of the main dex (ADFA-5053).
 */
class JavaCompilerSessionImpl : IJavaCompilerSession {
	private val completionProvider = CompletionProvider()
	private val diagnosticProvider = JavaDiagnosticProvider()
	private var cachedCompletion: CachedCompletion = CachedCompletion.EMPTY

	private val settings get() = JavaServerSettings.getInstance()

	override fun resetProject(workspace: Workspace) {
		JavaCompilerService.NO_MODULE_COMPILER.destroy()
		SourceFileManager.clearCache()

		// Clear cached JAR file system for R.jar. Using the cached instance will result in
		// completions not being updated for updated resources.
		// TODO Clearing caches for JAR files ending with '/R.jar' is probably not a good idea --
		//  maybe this could be improved by using data from the AndroidModule project model.
		CachingJarFileSystemProvider.clearCachesForPaths { path: String -> path.endsWith("/R.jar") }

		JavaCompilerProvider.getInstance().destroy()

		for (subModule in workspace.subProjects) {
			if (subModule !is ModuleProject || subModule.path == workspace.rootProject.path) {
				continue
			}
			SourceFileManager.forModule(subModule)
		}
	}

	override fun registerCodeActions() {
		LSPEditorActions.ensureActionsMenuRegistered(JavaCodeActionsMenu)
	}

	override fun unregisterCodeActions() {
		LSPEditorActions.ensureActionsMenuUnregistered(JavaCodeActionsMenu)
	}

	override fun close() {
		JavaCompilerProvider.getInstance().destroy()
		SourceFileManager.clearCache()
	}

	private fun getCompiler(file: Path?): JavaCompilerService {
		val module =
			ProjectManagerImpl.getInstance().findModuleForFile(file ?: return JavaCompilerService.NO_MODULE_COMPILER)
				?: return JavaCompilerService.NO_MODULE_COMPILER
		return JavaCompilerProvider.get(module)
	}

	override fun complete(params: CompletionParams): CompletionResult {
		val compiler = getCompiler(params.file)
		if (!completionProvider.canComplete(params.file)) {
			return CompletionResult.EMPTY
		}

		if (diagnosticProvider.isAnalyzing()) {
			log.warn("Cancelling source code analysis due to completion request")
			diagnosticProvider.cancel()
		}

		completionProvider.reset(
			compiler,
			settings,
			cachedCompletion,
		) { updated: CachedCompletion ->
			Objects.requireNonNull(updated)
			cachedCompletion = updated
		}

		return completionProvider.complete(params)
	}

	override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
		val compiler = getCompiler(params.file)
		return ReferenceProvider(compiler, params.cancelChecker).findReferences(params)
	}

	override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
		val compiler = getCompiler(params.file)
		return DefinitionProvider(compiler, settings, params.cancelChecker).findDefinition(params)
	}

	override suspend fun expandSelection(params: ExpandSelectionParams): Range {
		val compiler = getCompiler(params.file)
		return JavaSelectionProvider(compiler).expandSelection(params)
	}

	override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
		val compiler = getCompiler(params.file)
		return SignatureProvider(compiler, params.cancelChecker).signatureHelp(params)
	}

	override suspend fun analyze(file: Path): DiagnosticResult = diagnosticProvider.analyze(file)

	override fun onContentChange(event: DocumentChangeEvent) {
		// TODO Find an alternative to efficiently update changeDelta in JavaCompilerService instance
		JavaCompilerService.NO_MODULE_COMPILER.onDocumentChange(event)
		val module = getInstance().findModuleForFile(event.changedFile)
		if (module != null) {
			JavaCompilerProvider.get(module).onDocumentChange(event)
		}
	}

	override fun formatCode(params: FormatCodeParams?): CodeFormatResult = CodeFormatProvider(settings).format(params)

	override fun handleCompletionFailure(error: Throwable?): Boolean {
		if (isCancelled(error)) {
			return true
		}
		JavaCompilerProvider.getInstance().destroy()
		return true
	}

	override fun onFileClosed(file: Path) {
		diagnosticProvider.clearTimestamp(file)
	}

	override fun findSourceFilePath(module: ModuleProject, className: String): String? {
		val fo = JavaCompilerProvider.get(module).findAnywhere(className).orElse(null) ?: return null
		if (fo.kind != JavaFileObject.Kind.SOURCE || fo !is SourceFileObject) {
			return null
		}
		return fo.name
	}

	companion object {
		private val log = LoggerFactory.getLogger(JavaCompilerSessionImpl::class.java)
	}
}
