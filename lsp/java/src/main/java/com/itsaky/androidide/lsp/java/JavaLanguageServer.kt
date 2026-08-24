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
package com.itsaky.androidide.lsp.java

import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentSelectedEvent
import com.itsaky.androidide.javac.services.fs.CacheFSInfoSingleton
import com.itsaky.androidide.javac.services.fs.CachingJarFileSystemProvider.clearCache
import com.itsaky.androidide.javac.services.fs.CachingJarFileSystemProvider.clearCachesForPaths
import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.api.ILanguageServer
import com.itsaky.androidide.lsp.api.IServerSettings
import com.itsaky.androidide.lsp.debug.DebugClientConnectionResult
import com.itsaky.androidide.lsp.debug.IDebugAdapter
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.itsaky.androidide.lsp.internal.model.CachedCompletion
import com.itsaky.androidide.lsp.java.actions.JavaCodeActionsMenu
import com.itsaky.androidide.lsp.java.compiler.JavaCompilerService
import com.itsaky.androidide.lsp.java.compiler.SourceFileManager
import com.itsaky.androidide.lsp.java.debug.JavaDebugAdapter
import com.itsaky.androidide.lsp.java.debug.JdwpOptions
import com.itsaky.androidide.lsp.java.models.JavaServerSettings
import com.itsaky.androidide.lsp.java.providers.CodeFormatProvider
import com.itsaky.androidide.lsp.java.providers.CompletionProvider
import com.itsaky.androidide.lsp.java.providers.DefinitionProvider
import com.itsaky.androidide.lsp.java.providers.JavaDiagnosticProvider
import com.itsaky.androidide.lsp.java.providers.JavaSelectionProvider
import com.itsaky.androidide.lsp.java.providers.ReferenceProvider
import com.itsaky.androidide.lsp.java.providers.SignatureProvider
import com.itsaky.androidide.lsp.java.providers.snippet.JavaSnippetRepository
import com.itsaky.androidide.lsp.java.utils.AnalyzeTimer
import com.itsaky.androidide.lsp.java.utils.CancelChecker.Companion.isCancelled
import com.itsaky.androidide.lsp.models.CodeFormatResult
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.lsp.models.DefinitionResult
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.ExpandSelectionParams
import com.itsaky.androidide.lsp.models.FailureType
import com.itsaky.androidide.lsp.models.FormatCodeParams
import com.itsaky.androidide.lsp.models.LSPFailure
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.lsp.models.ReferenceResult
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.lsp.util.LSPEditorActions
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager.getActiveDocumentCount
import com.itsaky.androidide.projects.IProjectManager.Companion.getInstance
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.utils.DocumentUtils
import com.itsaky.androidide.utils.VMUtils
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class JavaLanguageServer : ILanguageServer {
	private val completionProvider: CompletionProvider = CompletionProvider()
	private val diagnosticProvider = JavaDiagnosticProvider()
	override var client: ILanguageClient? = null
		private set

	private var _settings: IServerSettings? = null
	private var selectedFile: Path? = null
	private val timer = AnalyzeTimer { analyzeSelected() }
	private var cachedCompletion: CachedCompletion

	// Lifecycle of the javac-backed compiler state (NO_MODULE_COMPILER, SourceFileManager,
	// JavaCompilerProvider), which setupWithProject() defers instead of building eagerly
	// (ADFA-5052). All reads/writes of pendingWorkspace and compilerLifecycle go through
	// compilerLifecycleLock, held for the *entire* reset/shutdown, not just the decision to
	// run one -- otherwise a concurrent getCompiler()/onContentChange() could use a compiler
	// mid-teardown, or shutdown() could destroy state a reset is still rebuilding.
	private enum class CompilerLifecycle { PENDING, RESETTING, INITIALIZED, SHUTDOWN }

	private val compilerLifecycleLock = ReentrantLock()

	// Guarded by compilerLifecycleLock.
	private var pendingWorkspace: Workspace? = null
	private var compilerLifecycle = CompilerLifecycle.PENDING

	/**
	 * Whether [shutdown] has run. Exposed because the lifecycle is otherwise unobservable from
	 * outside -- every path returns `NO_MODULE_COMPILER` for its own reasons, so a test cannot tell
	 * "refused because shut down" from "no module for this file" without it.
	 */
	@VisibleForTesting
	internal val isShutDown: Boolean
		get() = compilerLifecycleLock.withLock { compilerLifecycle == CompilerLifecycle.SHUTDOWN }

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
			service = JvmLibraryIndexingService(context = BaseApplication.baseInstance),
		)
		projectManager.indexingServiceManager.register(
			service = JvmGeneratedIndexingService(context = BaseApplication.baseInstance),
		)

		JavaSnippetRepository.init()
	}

	override fun shutdown() {
		(this.debugAdapter as? AutoCloseable?)?.close()
		compilerLifecycleLock.withLock {
			// Blocks here if a reset is in flight (RESETTING can only be observed by another
			// thread while the lock is held, never by us once we've acquired it), so this never
			// races ensureProjectReset()'s own destroy/rebuild.
			if (compilerLifecycle == CompilerLifecycle.INITIALIZED) {
				JavaCompilerProvider.getInstance().destroy()
				SourceFileManager.clearCache()
				CacheFSInfoSingleton.clearCache()
				clearCache()
			}
			compilerLifecycle = CompilerLifecycle.SHUTDOWN
		}
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

		(
			ProjectManagerImpl
				.getInstance()
				.indexingServiceManager
				.getService(JvmLibraryIndexingService.ID) as? JvmLibraryIndexingService?
		)?.refresh()

		// Deferred to ensureProjectReset(), run on the first real .java-file interaction instead
		// of here -- this method runs for every project open regardless of language
		// (DefaultLanguageServerRegistry dispatches to all registered servers unconditionally),
		// and JavaCompilerService.NO_MODULE_COMPILER / SourceFileManager.NO_MODULE eagerly
		// construct real javac machinery plus a full android.jar scan at class-init, merely by
		// being referenced (ADFA-5052, mirrors ADFA-5010's KotlinLanguageServer fix).
		compilerLifecycleLock.withLock {
			// SHUTDOWN is terminal. A server whose javac state has been destroyed does not come
			// back because a project happened to open afterwards; reviving it here would rebuild
			// compilers nothing is going to shut down again (found in review).
			if (compilerLifecycle == CompilerLifecycle.SHUTDOWN) {
				log.debug("setupWithProject() ignored: this server has been shut down.")
				return
			}
			pendingWorkspace = workspace
			// Leave RESETTING alone: ensureProjectReset()'s own finally block re-checks
			// pendingWorkspace once it re-acquires the lock, so a project switch mid-reset is
			// picked up as another PENDING round rather than raced here.
			if (compilerLifecycle != CompilerLifecycle.RESETTING) {
				compilerLifecycle = CompilerLifecycle.PENDING
			}
		}
	}

	/**
	 * Runs the javac-specific project reset deferred by [setupWithProject], for the most
	 * recently opened project, the first time a real Java file is actually interacted with.
	 * No-ops if already up to date. Blocks concurrent callers (and [shutdown]) for the entire
	 * reset, not just the decision to run one.
	 */
	private fun ensureProjectReset() {
		compilerLifecycleLock.withLock {
			// PENDING is the only state a reset starts from; SHUTDOWN in particular is terminal.
			if (compilerLifecycle != CompilerLifecycle.PENDING) return
			val workspace = pendingWorkspace ?: return
			pendingWorkspace = null
			compilerLifecycle = CompilerLifecycle.RESETTING

			try {
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
			} catch (e: Exception) {
				// Re-queue the workspace so the next real .java-file interaction retries the
				// reset, instead of a half-destroyed/half-rebuilt state being silently claimed as
				// INITIALIZED (pendingWorkspace is already null by this point).
				log.warn("Failed to reset javac project state; will retry on next interaction", e)
				pendingWorkspace = workspace
				compilerLifecycle = CompilerLifecycle.PENDING
				throw e
			}

			// A newer setupWithProject() may have queued another workspace while we were
			// resetting (see the RESETTING guard above); if so, go back to PENDING instead of
			// claiming INITIALIZED for a project we didn't actually reset for.
			compilerLifecycle =
				if (pendingWorkspace != null) {
					CompilerLifecycle.PENDING
				} else {
					CompilerLifecycle.INITIALIZED
				}
		}
	}

	override fun complete(params: CompletionParams?): CompletionResult {
		val compiler = getCompiler(params!!.file)
		if (!settings.completionsEnabled() || !completionProvider.canComplete(params.file)) {
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

		// diagnosticProvider.analyze() builds its own JavaCompilerService directly (bypassing
		// getCompiler()), and analysis is often the first real .java-file interaction in a
		// session (auto-triggered on file open, ahead of any completion request) -- without this,
		// the R.jar/file-manager caches this reset clears would never get cleared for this
		// project, and diagnostics could resolve against a stale previous project's classpath.
		ensureProjectReset()

		return if (!settings.codeAnalysisEnabled()) {
			DiagnosticResult.NO_UPDATE
		} else {
			diagnosticProvider.analyze(file)
		}
	}

	override fun formatCode(params: FormatCodeParams?): CodeFormatResult = CodeFormatProvider(settings).format(params)

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
		// Held across ensureProjectReset() *and* the provider lookup (ReentrantLock is
		// reentrant, so ensureProjectReset()'s own withLock nests fine): otherwise a concurrent
		// reset for a newer project could destroy() the provider's compilers in the gap between
		// this thread's reset finishing and its JavaCompilerProvider.get() call.
		return compilerLifecycleLock.withLock {
			// Nothing to hand out once the javac state is gone: NO_MODULE_COMPILER is the same
			// answer this returns for a non-Java file, and it does not resurrect what shutdown()
			// destroyed.
			if (compilerLifecycle == CompilerLifecycle.SHUTDOWN) {
				return@withLock JavaCompilerService.NO_MODULE_COMPILER
			}
			ensureProjectReset()
			val module =
				ProjectManagerImpl.getInstance().findModuleForFile(file!!)
					?: return@withLock JavaCompilerService.NO_MODULE_COMPILER
			JavaCompilerProvider.get(module)
		}
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

		// See getCompiler(): held across the reset *and* the provider lookup/use so a concurrent
		// reset can't destroy() these compilers in between.
		compilerLifecycleLock.withLock {
			// A document change after shutdown has no compiler to tell, and must not rebuild one.
			if (compilerLifecycle == CompilerLifecycle.SHUTDOWN) {
				return
			}
			ensureProjectReset()

			// TODO Find an alternative to efficiently update changeDelta in JavaCompilerService instance
			JavaCompilerService.NO_MODULE_COMPILER.onDocumentChange(event)
			val module =
				getInstance()
					.findModuleForFile(event.changedFile)
			if (module != null) {
				val compiler = JavaCompilerProvider.get(module)
				compiler.onDocumentChange(event)
			}
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
