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
package org.appdevforall.codeonthego.lsp.java

import androidx.annotation.RestrictTo
import org.appdevforall.codeonthego.app.BaseApplication
import org.appdevforall.codeonthego.eventbus.events.editor.DocumentChangeEvent
import org.appdevforall.codeonthego.eventbus.events.editor.DocumentCloseEvent
import org.appdevforall.codeonthego.eventbus.events.editor.DocumentOpenEvent
import org.appdevforall.codeonthego.eventbus.events.editor.DocumentSelectedEvent
import org.appdevforall.codeonthego.javac.services.fs.CacheFSInfoSingleton
import org.appdevforall.codeonthego.javac.services.fs.CachingJarFileSystemProvider.clearCache
import org.appdevforall.codeonthego.javac.services.fs.CachingJarFileSystemProvider.clearCachesForPaths
import org.appdevforall.codeonthego.lsp.api.ILanguageClient
import org.appdevforall.codeonthego.lsp.api.ILanguageServer
import org.appdevforall.codeonthego.lsp.api.IServerSettings
import org.appdevforall.codeonthego.lsp.debug.DebugClientConnectionResult
import org.appdevforall.codeonthego.lsp.debug.IDebugAdapter
import org.appdevforall.codeonthego.lsp.debug.IDebugClient
import org.appdevforall.codeonthego.lsp.internal.model.CachedCompletion
import org.appdevforall.codeonthego.lsp.java.actions.JavaCodeActionsMenu
import org.appdevforall.codeonthego.lsp.java.compiler.JavaCompilerService
import org.appdevforall.codeonthego.lsp.java.compiler.SourceFileManager
import org.appdevforall.codeonthego.lsp.java.debug.JavaDebugAdapter
import org.appdevforall.codeonthego.lsp.java.debug.JdwpOptions
import org.appdevforall.codeonthego.lsp.java.models.JavaServerSettings
import org.appdevforall.codeonthego.lsp.java.providers.CodeFormatProvider
import org.appdevforall.codeonthego.lsp.java.providers.CompletionProvider
import org.appdevforall.codeonthego.lsp.java.providers.DefinitionProvider
import org.appdevforall.codeonthego.lsp.java.providers.JavaDiagnosticProvider
import org.appdevforall.codeonthego.lsp.java.providers.JavaSelectionProvider
import org.appdevforall.codeonthego.lsp.java.providers.ReferenceProvider
import org.appdevforall.codeonthego.lsp.java.providers.SignatureProvider
import org.appdevforall.codeonthego.lsp.java.providers.snippet.JavaSnippetRepository
import org.appdevforall.codeonthego.lsp.java.utils.AnalyzeTimer
import org.appdevforall.codeonthego.lsp.java.utils.CancelChecker.Companion.isCancelled
import org.appdevforall.codeonthego.lsp.models.CodeFormatResult
import org.appdevforall.codeonthego.lsp.models.CompletionParams
import org.appdevforall.codeonthego.lsp.models.CompletionResult
import org.appdevforall.codeonthego.lsp.models.DefinitionParams
import org.appdevforall.codeonthego.lsp.models.DefinitionResult
import org.appdevforall.codeonthego.lsp.models.DiagnosticResult
import org.appdevforall.codeonthego.lsp.models.ExpandSelectionParams
import org.appdevforall.codeonthego.lsp.models.FailureType
import org.appdevforall.codeonthego.lsp.models.FormatCodeParams
import org.appdevforall.codeonthego.lsp.models.LSPFailure
import org.appdevforall.codeonthego.lsp.models.ReferenceParams
import org.appdevforall.codeonthego.lsp.models.ReferenceResult
import org.appdevforall.codeonthego.lsp.models.SignatureHelp
import org.appdevforall.codeonthego.lsp.models.SignatureHelpParams
import org.appdevforall.codeonthego.lsp.util.LSPEditorActions
import org.appdevforall.codeonthego.models.Range
import org.appdevforall.codeonthego.projects.FileManager.getActiveDocumentCount
import org.appdevforall.codeonthego.projects.IProjectManager.Companion.getInstance
import org.appdevforall.codeonthego.projects.ProjectManagerImpl
import org.appdevforall.codeonthego.projects.api.ModuleProject
import org.appdevforall.codeonthego.projects.api.Workspace
import org.appdevforall.codeonthego.utils.DocumentUtils
import org.appdevforall.codeonthego.utils.VMUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.indexing.jvm.JvmGeneratedIndexingService
import org.appdevforall.codeonthego.indexing.jvm.JvmLibraryIndexingService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Objects

class JavaLanguageServer : ILanguageServer {
	private val completionProvider: CompletionProvider = CompletionProvider()
	private val diagnosticProvider = JavaDiagnosticProvider()
	override var client: ILanguageClient? = null
		private set

	private var _settings: IServerSettings? = null
	private var selectedFile: Path? = null
	private val timer = AnalyzeTimer { analyzeSelected() }
	private var cachedCompletion: CachedCompletion

	val settings: IServerSettings
		get() {
			return _settings ?: JavaServerSettings
				.getInstance()
				.also { _settings = it }
		}

	override val serverId: String = SERVER_ID

	override val debugAdapter: IDebugAdapter = JavaDebugAdapter()

	companion object {
		const val SERVER_ID = "ide.lsp.java"
		private val log = LoggerFactory.getLogger(JavaLanguageServer::class.java)
	}

	init {
		cachedCompletion = CachedCompletion.EMPTY

		applySettings(JavaServerSettings.getInstance())

		if (!EventBus.getDefault().isRegistered(this)) {
			EventBus.getDefault().register(this)
		}

		val projectManager = ProjectManagerImpl.getInstance()
		projectManager.indexingServiceManager.register(
			service = JvmLibraryIndexingService(context = BaseApplication.baseInstance)
		)
		projectManager.indexingServiceManager.register(
			service = JvmGeneratedIndexingService(context = BaseApplication.baseInstance)
		)

		JavaSnippetRepository.init()
	}

	override fun shutdown() {
		(this.debugAdapter as? AutoCloseable?)?.close()
		JavaCompilerProvider.getInstance().destroy()
		SourceFileManager.clearCache()
		CacheFSInfoSingleton.clearCache()
		clearCache()
		EventBus.getDefault().unregister(this)
		timer.cancel()
	}

	override fun connectClient(client: ILanguageClient?) {
		this.client = client
	}

	override suspend fun connectDebugClient(client: IDebugClient): DebugClientConnectionResult {
		if (JdwpOptions.JDWP_ENABLED) {
			log.info("Connecting to debug client: {}", client)
			return this.debugAdapter.connectDebugClient(client)
		}

		log.info("Not connecting to debug client. JDWP disabled.")
		return DebugClientConnectionResult.Success
	}

	override fun applySettings(settings: IServerSettings?) {
		this._settings = settings
	}

	override fun setupWithProject(workspace: Workspace) {
		LSPEditorActions.ensureActionsMenuRegistered(JavaCodeActionsMenu)

		(ProjectManagerImpl.getInstance()
			.indexingServiceManager
			.getService(JvmLibraryIndexingService.ID) as? JvmLibraryIndexingService?)
			?.refresh()

		// Once we have project initialized
		// Destory the NO_MODULE_COMPILER instance
		JavaCompilerService.NO_MODULE_COMPILER.destroy()

		// Clear cached file managers
		SourceFileManager.clearCache()

		// Clear cached JAR file system for R.jar
		// Using the cached instance will result in completions not being updated for updated resources
		// TODO Clearing caches for JAR files ending with '/R.jar' is probably not a good idea
		//    Maybe this could be improved by using data from the AndroidModule project model
		clearCachesForPaths { path: String -> path.endsWith("/R.jar") }

		// Clear cached module-specific compilers
		JavaCompilerProvider.getInstance().destroy()

		// Cache classpath locations
		for (subModule in workspace.subProjects) {
			if (subModule !is ModuleProject || subModule.path == workspace.rootProject.path) {
				continue
			}
			SourceFileManager.forModule(subModule)
		}
		startOrRestartAnalyzeTimer()
	}

	override fun complete(params: CompletionParams?): CompletionResult {
		val compiler = getCompiler(params!!.file)
		if (!settings.completionsEnabled() || !completionProvider.canComplete(params.file)
		) {
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
		) { cachedCompletion: CachedCompletion ->
			updateCachedCompletion(cachedCompletion)
		}

		return completionProvider.complete(params)
	}

	override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
		val compiler = getCompiler(params.file)
		return if (!settings.referencesEnabled()) {
			ReferenceResult(emptyList())
		} else {
			ReferenceProvider(compiler, params.cancelChecker).findReferences(params)
		}
	}

	override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
		val compiler = getCompiler(params.file)
		return if (!settings.definitionsEnabled()) {
			DefinitionResult(emptyList())
		} else {
			DefinitionProvider(compiler, settings, params.cancelChecker).findDefinition(params)
		}
	}

	override suspend fun expandSelection(params: ExpandSelectionParams): Range {
		val compiler = getCompiler(params.file)
		return if (!settings.smartSelectionsEnabled()) {
			params.selection
		} else {
			JavaSelectionProvider(compiler).expandSelection(params)
		}
	}

	override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
		val compiler = getCompiler(params.file)
		return if (!settings.signatureHelpEnabled()) {
			SignatureHelp(emptyList(), -1, -1)
		} else {
			SignatureProvider(compiler, params.cancelChecker).signatureHelp(params)
		}
	}

	override suspend fun analyze(file: Path): DiagnosticResult {
		if (!settings.diagnosticsEnabled() || !DocumentUtils.isJavaFile(file)) {
			return DiagnosticResult.NO_UPDATE
		}

		return if (!settings.codeAnalysisEnabled()) {
			DiagnosticResult.NO_UPDATE
		} else {
			diagnosticProvider.analyze(file)
		}
	}

	override fun formatCode(params: FormatCodeParams?): CodeFormatResult =
		CodeFormatProvider(settings).format(params)

	override fun handleFailure(failure: LSPFailure?): Boolean {
		return when (failure!!.type) {
			FailureType.COMPLETION -> {
				if (isCancelled(failure.error)) {
					return true
				}
				JavaCompilerProvider.getInstance().destroy()
				true
			}
		}
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
	fun getCompiler(file: Path?): JavaCompilerService {
		if (!DocumentUtils.isJavaFile(file)) {
			return JavaCompilerService.NO_MODULE_COMPILER
		}
		val module =
			ProjectManagerImpl.getInstance().findModuleForFile(file!!)
				?: return JavaCompilerService.NO_MODULE_COMPILER
		return JavaCompilerProvider.get(module)
	}

	private fun updateCachedCompletion(cachedCompletion: CachedCompletion) {
		Objects.requireNonNull(cachedCompletion)
		this.cachedCompletion = cachedCompletion
	}

	private fun startOrRestartAnalyzeTimer() {
		if (VMUtils.isJvm) {
			return
		}
		if (!timer.isStarted) {
			timer.start()
		} else {
			timer.restart()
		}
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onContentChange(event: DocumentChangeEvent) {
		if (!DocumentUtils.isJavaFile(event.changedFile)) {
			return
		}

		// TODO Find an alternative to efficiently update changeDelta in JavaCompilerService instance
		JavaCompilerService.NO_MODULE_COMPILER.onDocumentChange(event)
		val module =
			getInstance()
				.findModuleForFile(event.changedFile)
		if (module != null) {
			val compiler = JavaCompilerProvider.get(module)
			compiler.onDocumentChange(event)
		}
		startOrRestartAnalyzeTimer()
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onFileSelected(event: DocumentSelectedEvent) {
		selectedFile = event.selectedFile
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onFileOpened(event: DocumentOpenEvent) {
		selectedFile = event.openedFile
		startOrRestartAnalyzeTimer()
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onFileClosed(event: DocumentCloseEvent) {
		diagnosticProvider.clearTimestamp(event.closedFile)

		if (getActiveDocumentCount() == 0) {
			selectedFile = null
			timer.cancel()
		}
	}

	private fun analyzeSelected() {
		val file = selectedFile ?: return
		if (client == null) return

		if (!Files.exists(file)) return

		CoroutineScope(Dispatchers.Default).launch {
			val result = analyze(selectedFile!!)
			withContext(Dispatchers.Main) {
				client?.publishDiagnostics(result)
			}
		}
	}
}
