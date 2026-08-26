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

package com.itsaky.androidide.lsp.kotlin

import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider
import com.itsaky.androidide.eventbus.events.BuildCompletedEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentSaveEvent
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.api.ILanguageServer
import com.itsaky.androidide.lsp.api.IServerSettings
import com.itsaky.androidide.lsp.kotlin.api.IKotlinCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.api.IKotlinCompilerSession
import com.itsaky.androidide.lsp.kotlin.api.KT_SOURCE_FILE_INDEX_KEY
import com.itsaky.androidide.lsp.kotlin.api.KT_SOURCE_FILE_META_INDEX_KEY
import com.itsaky.androidide.lsp.kotlin.loader.KotlinCompilerLoader
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.lsp.models.DefinitionResult
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.ExpandSelectionParams
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.lsp.models.ReferenceResult
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.tasks.createJobCancelChecker
import com.itsaky.androidide.utils.DocumentUtils
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.ifNotEmpty
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.indexing.jvm.JvmLibraryIndexingService
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.nio.file.Path

class KotlinLanguageServer : ILanguageServer {
	private var _client: ILanguageClient? = null
	private var _settings: IServerSettings? = null
	private var initialized = false
	private var workspace: Workspace? = null

	private val scope =
		CoroutineScope(SupervisorJob() + CoroutineName(KotlinLanguageServer::class.simpleName!!))

	private val loader = KotlinCompilerLoader(BaseApplication.baseInstance)

	override val serverId: String = SERVER_ID

	override val client: ILanguageClient?
		get() = _client

	val settings: IServerSettings
		get() = _settings ?: KotlinServerSettings.getInstance().also { _settings = it }

	companion object {
		const val SERVER_ID = "ide.lsp.kotlin"
		private val logger = LoggerFactory.getLogger(KotlinLanguageServer::class.java)
	}

	init {
		applySettings(KotlinServerSettings.getInstance())

		if (!EventBus.getDefault().isRegistered(this)) {
			EventBus.getDefault().register(this)
		}
	}

	override fun shutdown() {
		EventBus.getDefault().unregister(this)
		scope.cancel("LSP is being shut down")
		// Unregister before closing: once closed, loader.currentSession() is null and the
		// session's action objects (bound to this session's DexClassLoader) would otherwise
		// stay wired into the shared, app-wide editor actions menu -- executable, but with a
		// classloader that no longer matches anything a future session hands out.
		loader.currentSession()?.unregisterCodeActions()
		codeActionsRegistered = false
		loader.close()
		initialized = false
	}

	override fun connectClient(client: ILanguageClient?) {
		this._client = client
		loader.currentSession()?.updateLanguageClient(client)
	}

	override fun applySettings(settings: IServerSettings?) {
		this._settings = settings
	}

	/**
	 * The session, creating it (extracting and DexClassLoader-loading the carrier APK) on
	 * the first call for a Kotlin file -- not eagerly in [setupWithProject], which used to
	 * pay this cost for every project open, Kotlin or not.
	 */
	private fun ensureSession(): IKotlinCompilerSession? {
		val ws = workspace ?: return null
		return loader
			.getOrCreateSession(
				workspace = ws,
				jdkHome = Environment.JAVA_HOME.toPath(),
				jdkRelease = IJdkDistributionProvider.DEFAULT_JAVA_RELEASE,
				jdkVersionString = IJdkDistributionProvider.DEFAULT_JAVA_VERSION,
				languageClient = client,
			).also { session ->
				if (!codeActionsRegistered) {
					session.registerCodeActions()
					codeActionsRegistered = true
				}
			}
	}

	private var codeActionsRegistered = false

	/**
	 * The environment for [file], loading the compiler on first Kotlin-file-gated call if
	 * needed. Public: the code actions that move with the isolated compiler module (e.g.
	 * `OrganizeImportsAction`, `ImplementMembersAction`) reach back through this to get the
	 * concrete environment they need for advanced operations, same as before this refactor.
	 */
	fun compilationEnvironmentFor(file: Path): IKotlinCompilationEnvironment? = ensureSession()?.compilationEnvironmentFor(file)

	override fun setupWithProject(workspace: Workspace) {
		logger.info("setupWithProject called, initialized={}", initialized)

		val context = BaseApplication.baseInstance
		val indexingServiceManager =
			ProjectManagerImpl
				.getInstance()
				.indexingServiceManager

		val indexingRegistry = indexingServiceManager.registry
		indexingRegistry.register(
			key = KT_SOURCE_FILE_INDEX_KEY,
			index =
				JvmSymbolIndex.createSqliteIndex(
					context = context,
					dbName = KT_SOURCE_FILE_INDEX_KEY.name,
					indexName = KT_SOURCE_FILE_INDEX_KEY.name,
				),
		)

		indexingRegistry.register(
			key = KT_SOURCE_FILE_META_INDEX_KEY,
			index =
				KtFileMetadataIndex.sqliteBacked(
					context = context,
					dbName = KT_SOURCE_FILE_META_INDEX_KEY.name,
				),
		)

		val jvmLibraryIndexingService =
			indexingServiceManager.getService(JvmLibraryIndexingService.ID) as? JvmLibraryIndexingService?

		jvmLibraryIndexingService?.refresh()

		this.workspace = workspace

		val session = loader.currentSession()
		if (session != null) {
			// A session already exists (a Kotlin file was opened before this re-setup, e.g. on
			// project reload) -- refresh its project model and pick up files that are already open.
			session.updateProjectModel(workspace)

			FileManager.activeDocuments.ifNotEmpty {
				val activeFiles = map { it.file }
				session.openFileIfNeeded(activeFiles)
			}
		}

		initialized = true
		logger.info("Kotlin project initialized")
	}

	override fun complete(params: CompletionParams?): CompletionResult {
		if (params == null) {
			logger.warn("Cannot complete for null params")
			return CompletionResult.EMPTY
		}

		logger.debug("complete(position={}, file={})", params.position, params.file)
		return compilationEnvironmentFor(params.file)?.complete(params) ?: CompletionResult.EMPTY
	}

	override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
		if (!settings.referencesEnabled()) {
			return ReferenceResult.empty()
		}

		if (!DocumentUtils.isKotlinFile(params.file)) {
			return ReferenceResult.empty()
		}

		logger.debug("findReferences(position={}, file={})", params.position, params.file)
		// stage implemented this against the concrete CompilationEnvironment while this branch was
		// open. It reaches the carrier through the bridge here, like complete/findDefinition/
		// signatureHelp/collectDiagnostics, rather than being lost to the module split.
		return compilationEnvironmentFor(params.file)?.findReferences(params) ?: ReferenceResult.empty()
	}

	override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
		if (!settings.definitionsEnabled()) {
			return DefinitionResult.empty()
		}

		if (!DocumentUtils.isKotlinFile(params.file)) {
			return DefinitionResult.empty()
		}

		logger.debug("findDefinition(position={}, file={})", params.position, params.file)
		return compilationEnvironmentFor(params.file)?.findDefinition(params) ?: DefinitionResult.empty()
	}

	override suspend fun expandSelection(params: ExpandSelectionParams): Range = params.selection

	override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
		if (!settings.signatureHelpEnabled()) {
			return SignatureHelp.empty()
		}

		if (!DocumentUtils.isKotlinFile(params.file)) {
			return SignatureHelp.empty()
		}

		logger.debug("signatureHelp(position={}, file={})", params.position, params.file)
		return compilationEnvironmentFor(params.file)?.signatureHelp(params) ?: SignatureHelp.empty()
	}

	override suspend fun analyze(file: Path): DiagnosticResult {
		logger.debug("analyze(file={})", file)

		if (!settings.diagnosticsEnabled() || !settings.codeAnalysisEnabled()) {
			logger.debug(
				"analyze() skipped: diagnosticsEnabled={}, codeAnalysisEnabled={}",
				settings.diagnosticsEnabled(),
				settings.codeAnalysisEnabled(),
			)
			return DiagnosticResult.NO_UPDATE
		}

		if (!DocumentUtils.isKotlinFile(file)) {
			logger.debug("analyze() skipped: not a Kotlin file")
			return DiagnosticResult.NO_UPDATE
		}

		return compilationEnvironmentFor(file)?.collectDiagnostics(file, createJobCancelChecker()) ?: DiagnosticResult.NO_UPDATE
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentOpen(event: DocumentOpenEvent) {
		if (!DocumentUtils.isKotlinFile(event.openedFile)) {
			return
		}

		compilationEnvironmentFor(event.openedFile)?.onFileOpen(event.openedFile)
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentChange(event: DocumentChangeEvent) {
		if (!DocumentUtils.isKotlinFile(event.changedFile)) {
			return
		}

		compilationEnvironmentFor(event.changedFile)?.onFileContentChanged(event.changedFile)
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentClose(event: DocumentCloseEvent) {
		if (!DocumentUtils.isKotlinFile(event.closedFile)) {
			return
		}

		compilationEnvironmentFor(event.closedFile)?.onFileClosed(event.closedFile)
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentSaved(event: DocumentSaveEvent) {
		if (!DocumentUtils.isKotlinFile(event.savedFile)) {
			return
		}

		compilationEnvironmentFor(event.savedFile)?.onFileSaved(event.savedFile)
	}

	@Subscribe
	@Suppress("unused")
	fun onBuildCompleted(event: BuildCompletedEvent) {
		Sentry.addBreadcrumb("onBuildCompleted: result=${event.result}")
		loader.currentSession()?.refreshSources()
	}

	@Subscribe
	@Suppress("unused")
	fun onFileCreated(event: FileCreationEvent) {
		val path = event.file.toPath()
		if (!DocumentUtils.isKotlinFile(path)) {
			return
		}

		scope.launch {
			runCatching { compilationEnvironmentFor(path) }
				.getOrNull()
				?.onFileCreated(path)
		}
	}

	@Subscribe
	@Suppress("unused")
	fun onFileDeleted(event: FileDeletionEvent) {
		val path = event.file.toPath()
		if (!DocumentUtils.isKotlinFile(path)) {
			return
		}

		scope.launch {
			runCatching { compilationEnvironmentFor(path) }
				.getOrNull()
				?.onFileRemoved(path)
		}
	}

	@Subscribe
	@Suppress("unused")
	fun onFileRenamed(event: FileRenameEvent) {
		val fromPath = event.file.toPath()
		val toPath = event.newFile.toPath()

		scope.launch {
			val oldIsKotlinFile = DocumentUtils.isKotlinFile(fromPath)
			val newIsKotlinFile = DocumentUtils.isKotlinFile(toPath)

			if (!oldIsKotlinFile && newIsKotlinFile) {
				// only the new file is a Kotlin file
				// so just submit it for indexing
				compilationEnvironmentFor(toPath)?.onFileCreated(toPath)
				return@launch
			}

			if (oldIsKotlinFile && !newIsKotlinFile) {
				// only the old file was a Kotlin file
				// so just remove it from the index
				compilationEnvironmentFor(fromPath)?.onFileRemoved(fromPath)
				return@launch
			}

			val fromEnv = runCatching { compilationEnvironmentFor(fromPath) }.getOrNull()
			val toEnv = runCatching { compilationEnvironmentFor(toPath) }.getOrNull()

			if (fromEnv != null && fromEnv === toEnv) {
				// file was renamed within the same compilation environment
				toEnv.onFileMoved(fromPath, toPath)
				return@launch
			}

			// file may have been moved from one compilation environment to another
			// remove from old env's index
			// and submit to the new env for indexing
			fromEnv?.onFileRemoved(fromPath)
			toEnv?.onFileCreated(toPath)
		}
	}
}
