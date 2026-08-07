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

import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentSelectedEvent
import com.itsaky.androidide.javac.services.fs.CacheFSInfoSingleton
import com.itsaky.androidide.javac.services.fs.CachingJarFileSystemProvider.clearCache
import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.api.ILanguageServer
import com.itsaky.androidide.lsp.api.IServerSettings
import com.itsaky.androidide.lsp.debug.DebugClientConnectionResult
import com.itsaky.androidide.lsp.debug.IDebugAdapter
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.itsaky.androidide.lsp.java.api.IJavaCompilerSession
import com.itsaky.androidide.lsp.java.debug.JavaDebugAdapter
import com.itsaky.androidide.lsp.java.debug.JdwpOptions
import com.itsaky.androidide.lsp.java.loader.JavaCompilerLoader
import com.itsaky.androidide.lsp.java.models.JavaServerSettings
import com.itsaky.androidide.lsp.java.providers.snippet.JavaSnippetRepository
import com.itsaky.androidide.lsp.java.utils.AnalyzeTimer
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
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager.getActiveDocumentCount
import com.itsaky.androidide.projects.ProjectManagerImpl
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class JavaLanguageServer : ILanguageServer {
	private val loader = JavaCompilerLoader(BaseApplication.baseInstance)

	override var client: ILanguageClient? = null
		private set

	private var _settings: IServerSettings? = null
	private var selectedFile: Path? = null
	private val timer = AnalyzeTimer { analyzeSelected() }

	// Lifecycle of the isolated javac session (extracted + DexClassLoader-loaded lazily via
	// `loader`), which setupWithProject() defers instead of loading eagerly (ADFA-5052,
	// extended by ADFA-5053 to also gate the carrier-APK load). All reads/writes of
	// pendingWorkspace and compilerLifecycle go through compilerLifecycleLock, held for the
	// *entire* reset/shutdown, not just the decision to run one -- otherwise a concurrent
	// request could use a session mid-teardown, or shutdown() could destroy state a reset is
	// still rebuilding.
	private enum class CompilerLifecycle { PENDING, RESETTING, INITIALIZED, SHUTDOWN }

	private val compilerLifecycleLock = ReentrantLock()

	// Guarded by compilerLifecycleLock.
	private var pendingWorkspace: Workspace? = null
	private var compilerLifecycle = CompilerLifecycle.PENDING
	private var codeActionsRegistered = false

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

		// Independent of javac -- reads its own snippet assets from lsp/java's (resident) assets,
		// so this doesn't need to wait for the carrier.
		JavaSnippetRepository.init()
	}

	override fun shutdown() {
		(this.debugAdapter as? AutoCloseable?)?.close()
		compilerLifecycleLock.withLock {
			// Blocks here if a reset is in flight (RESETTING can only be observed by another
			// thread while the lock is held, never by us once we've acquired it), so this never
			// races ensureProjectReset()'s own destroy/rebuild.
			if (compilerLifecycle == CompilerLifecycle.INITIALIZED) {
				// Unregister before closing: once closed, loader.currentSession() is null and the
				// session's action objects (bound to this session's DexClassLoader) would
				// otherwise stay wired into the shared, app-wide editor actions menu.
				loader.currentSession()?.unregisterCodeActions()
				codeActionsRegistered = false
				loader.close()
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
		(
			ProjectManagerImpl
				.getInstance()
				.indexingServiceManager
				.getService(JvmLibraryIndexingService.ID) as? JvmLibraryIndexingService?
		)?.refresh()

		// Deferred to ensureProjectReset(), run on the first real .java-file interaction instead
		// of here -- this method runs for every project open regardless of language
		// (DefaultLanguageServerRegistry dispatches to all registered servers unconditionally),
		// and loading the javac carrier eagerly here would defeat the point of isolating it
		// (ADFA-5052, extended by ADFA-5053).
		compilerLifecycleLock.withLock {
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
	 * recently opened project, the first time a real Java file is actually interacted with --
	 * extracting and `DexClassLoader`-loading the carrier APK if this is the first interaction
	 * of the whole session. No-ops if already up to date. Blocks concurrent callers (and
	 * [shutdown]) for the entire reset, not just the decision to run one.
	 */
	private fun ensureProjectReset(): IJavaCompilerSession? =
		compilerLifecycleLock.withLock {
			if (compilerLifecycle != CompilerLifecycle.PENDING) return@withLock loader.currentSession()
			val workspace = pendingWorkspace ?: return@withLock loader.currentSession()
			pendingWorkspace = null
			compilerLifecycle = CompilerLifecycle.RESETTING

			val session: IJavaCompilerSession
			try {
				session = loader.getOrCreateSession(workspace)
				session.resetProject(workspace)
				if (!codeActionsRegistered) {
					session.registerCodeActions()
					codeActionsRegistered = true
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

			session
		}

	override fun complete(params: CompletionParams?): CompletionResult {
		if (params == null || !settings.completionsEnabled()) {
			return CompletionResult.EMPTY
		}
		return ensureProjectReset()?.complete(params) ?: CompletionResult.EMPTY
	}

	override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
		if (!settings.referencesEnabled()) {
			return ReferenceResult(emptyList())
		}
		return ensureProjectReset()?.findReferences(params) ?: ReferenceResult(emptyList())
	}

	override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
		if (!settings.definitionsEnabled()) {
			return DefinitionResult(emptyList())
		}
		return ensureProjectReset()?.findDefinition(params) ?: DefinitionResult(emptyList())
	}

	override suspend fun expandSelection(params: ExpandSelectionParams): Range {
		if (!settings.smartSelectionsEnabled()) {
			return params.selection
		}
		return ensureProjectReset()?.expandSelection(params) ?: params.selection
	}

	override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
		if (!settings.signatureHelpEnabled()) {
			return SignatureHelp(emptyList(), -1, -1)
		}
		return ensureProjectReset()?.signatureHelp(params) ?: SignatureHelp(emptyList(), -1, -1)
	}

	override suspend fun analyze(file: Path): DiagnosticResult {
		if (!settings.diagnosticsEnabled() || !DocumentUtils.isJavaFile(file)) {
			return DiagnosticResult.NO_UPDATE
		}

		// analyze() is often the first real .java-file interaction in a session (auto-triggered
		// on file open, ahead of any completion request) -- without this gate, the javac carrier
		// (and the R.jar/file-manager caches its reset clears) would never load for this project,
		// and diagnostics could resolve against a stale previous project's classpath.
		val session = ensureProjectReset() ?: return DiagnosticResult.NO_UPDATE

		return if (!settings.codeAnalysisEnabled()) {
			DiagnosticResult.NO_UPDATE
		} else {
			session.analyze(file)
		}
	}

	override fun formatCode(params: FormatCodeParams?): CodeFormatResult = ensureProjectReset()?.formatCode(params) ?: CodeFormatResult.NONE

	override fun handleFailure(failure: LSPFailure?): Boolean =
		when (failure!!.type) {
			FailureType.COMPLETION -> loader.currentSession()?.handleCompletionFailure(failure.error) ?: true
		}

	/** For [JavaDebugAdapter]'s source-location resolution -- null if the carrier hasn't loaded yet. */
	internal fun currentCompilerSession(): IJavaCompilerSession? = loader.currentSession()

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

		// Held across the reset *and* the actual onContentChange call (ReentrantLock is
		// reentrant, so ensureProjectReset()'s own withLock nests fine): otherwise a concurrent
		// reset for a newer project could destroy() the session's compilers in the gap between
		// this thread's reset finishing and its use.
		compilerLifecycleLock.withLock {
			ensureProjectReset()?.onContentChange(event)
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
		loader.currentSession()?.onFileClosed(event.closedFile)

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
